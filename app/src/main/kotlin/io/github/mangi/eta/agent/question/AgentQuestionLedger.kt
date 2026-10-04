package io.github.mangi.eta.agent.question

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Independent of transcript/checkpoint/outbox retention. All methods are blocking, worker-only. */
internal class AgentQuestionLedger(private val storage: Storage) {
    interface Storage {
        /** Same canonical file => same key, including across Service/store instances. */
        val key: String
        fun read(): String?
        fun write(json: String)
    }

    private class Domain {
        val lock = Any()
        @Volatile var failed = false
        var seenFile = false
        // Only lightweight identities, never sessions. A recreated Service cannot retire another owner.
        val liveOwners = java.util.concurrent.ConcurrentHashMap<String, String>()
    }
    private val domain = synchronized(domains) { domains.getOrPut(storage.key) { Domain() } }
    private data class Record(val snapshot: AgentQuestionSnapshot, val owner: String)
    val isAvailable: Boolean get() = !domain.failed

    fun registerOwner(runId: String, generation: String) {
        require(runId.isNotBlank() && generation.isNotBlank())
        domain.liveOwners[generation] = runId
    }

    fun retireOwner(generation: String) { domain.liveOwners.remove(generation) }

    /** An accepted persistence prerequisite failed outside this store (e.g. checkpoint). */
    fun invalidate() = synchronized(domain.lock) { domain.failed = true }

    /** Persistence is a prerequisite for accepting and publishing this evidence. */
    fun accept(generation: String, snapshot: AgentQuestionSnapshot) = guarded {
        require(snapshot.hasValidIdentity() && generation.isNotBlank() && generation.length <= 1024)
        if (domain.liveOwners[generation] != snapshot.runId) throw AgentQuestionInterruptedException()
        require(snapshot.status == AgentQuestionStatus.Waiting && snapshot.answer == null ||
            validQuestionResolution(snapshot.status, snapshot.answer))
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.snapshot.runId == snapshot.runId && it.snapshot.questionId == snapshot.questionId }
        if (index >= 0) {
            val previous = records[index]
            if (previous.owner != generation || !previous.snapshot.sameIdentity(snapshot)) throw AgentQuestionInterruptedException()
            // Requested never resets a resolution or replaces its owner.
            if (snapshot.status == AgentQuestionStatus.Waiting) return@guarded
            records[index] = Record(snapshot, generation)
        } else {
            if (snapshot.status != AgentQuestionStatus.Waiting) throw AgentQuestionInterruptedException()
            while (records.size >= MAX_RECORDS) {
                val evict = records.indexOfFirst { it.snapshot.status != AgentQuestionStatus.Waiting || !domain.liveOwners.containsKey(it.owner) }
                if (evict < 0) throw AgentQuestionInterruptedException()
                records.removeAt(evict)
            }
            records += Record(snapshot, generation)
        }
        save(records)
    }

    /** Close only Waiting; accepted corrective Interrupted has already replaced Answered in order. */
    fun sealOwner(generation: String) = guarded {
        val records = load()
        val sealed = records.map { record ->
            if (record.owner == generation && record.snapshot.status == AgentQuestionStatus.Waiting)
                record.copy(snapshot = record.snapshot.copy(status = AgentQuestionStatus.Interrupted))
            else record
        }
        if (sealed != records) save(sealed)
    }

    /** Unknown/corrupt/I/O failure => null, never an invented answer or interruption. No replay. */
    fun query(conversationId: String, runId: String, questionId: String, toolCallId: String): AgentQuestionSnapshot? =
        runCatching {
            guarded {
                val record = load().firstOrNull { it.snapshot.conversationId == conversationId &&
                    it.snapshot.runId == runId && it.snapshot.questionId == questionId && it.snapshot.toolCallId == toolCallId }
                    ?: return@guarded null
                if (record.snapshot.status == AgentQuestionStatus.Waiting && !domain.liveOwners.containsKey(record.owner))
                    record.snapshot.copy(status = AgentQuestionStatus.Interrupted)
                else record.snapshot
            }
        }.getOrNull()

    private fun <T> guarded(block: () -> T): T = synchronized(domain.lock) {
        if (domain.failed) throw AgentQuestionInterruptedException()
        try { block() }
        catch (rejected: AgentQuestionInterruptedException) { throw rejected }
        catch (failure: Throwable) {
            domain.failed = true
            throw AgentQuestionInterruptedException(failure)
        }
    }

    private fun load(): List<Record> {
        val raw = storage.read() ?: run {
            check(!domain.seenFile) { "Question ledger disappeared" }
            return emptyList()
        }
        domain.seenFile = true
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val tokener = JSONTokener(raw)
        val root = tokener.nextValue() as? JSONObject ?: error("Invalid question ledger")
        require(tokener.nextClean() == '\u0000' && root.opt("version") == 1)
        val entries = root.opt("entries") as? JSONArray ?: error("Invalid question entries")
        require(entries.length() <= MAX_RECORDS)
        val seen = hashSetOf<Pair<String, String>>()
        return (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            fun id(key: String): String = (item.opt(key) as? String)?.takeIf { it.isNotBlank() && it.length <= 1024 }
                ?: error("Invalid question identity")
            val status = AgentQuestionStatus.valueOf(id("status"))
            val answer = if (item.has("answer")) AgentQuestionCodec.answerFromJson(item.getJSONObject("answer")) else null
            require(status == AgentQuestionStatus.Waiting && answer == null || validQuestionResolution(status, answer))
            val snapshot = AgentQuestionSnapshot(id("conversation_id"), id("run_id"), id("question_id"), id("tool_call_id"), status, answer)
            require(seen.add(snapshot.runId to snapshot.questionId))
            Record(snapshot, id("owner_generation"))
        }
    }

    private fun save(records: List<Record>) {
        val entries = JSONArray()
        records.forEach { (snapshot, owner) ->
            entries.put(JSONObject().put("conversation_id", snapshot.conversationId).put("run_id", snapshot.runId)
                .put("question_id", snapshot.questionId).put("tool_call_id", snapshot.toolCallId)
                .put("owner_generation", owner).put("status", snapshot.status.name)
                .also { if (snapshot.answer != null) it.put("answer", AgentQuestionCodec.answerToJson(snapshot.answer)) })
        }
        val json = JSONObject().put("version", 1).put("entries", entries).toString()
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        storage.write(json)
        domain.seenFile = true
    }

    companion object {
        const val MAX_RECORDS = 256
        const val MAX_BYTES = 4 * 1024 * 1024
        private val domains = mutableMapOf<String, Domain>()
    }
}
