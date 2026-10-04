package io.github.mangi.eta.agent.delegation

import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.ImageResolutionTier
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.ReasoningEffort
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

internal sealed class SubAgentConfigKey {
    abstract val value: String
    data class Conversation(override val value: String) : SubAgentConfigKey() { init { require(value.isNotBlank()) } }
    data class Draft(override val value: String) : SubAgentConfigKey() { init { require(value.isNotBlank()) } }
}

/** The legacy hash and new pool key both use provider ID + API model (not model selection ID). */
internal data class SubAgentParallelModel(val providerId: String, val apiModel: String) {
    init { require(providerId.isNotBlank() && apiModel.isNotBlank()) }
    fun legacyKey(): String = "agent_model_parallel_" + MessageDigest.getInstance("SHA-256")
        .digest((providerId + "\u0000" + apiModel).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal data class ConversationSubAgentConfig(
    val profiles: List<SubAgentProfile>,
    val enabled: Boolean = true,
    val parallelLimits: Map<SubAgentParallelModel, Int> = emptyMap(),
    val diagnosticsEnabled: Boolean = false,
    val legacyParallelLimits: Map<String, Int> = emptyMap(),
) {
    fun detached(): ConversationSubAgentConfig = copy(
        profiles = profiles.map { it.copy(reasoningByModel = it.reasoningByModel.toMap(), gptSpeedByModel = it.gptSpeedByModel.toMap()) }.toList(),
        parallelLimits = parallelLimits.toMap(), legacyParallelLimits = legacyParallelLimits.toMap())
    fun parallelLimit(model: SubAgentParallelModel): Int =
        parallelLimits[model] ?: legacyParallelLimits[model.legacyKey()] ?: 1
    fun validate() {
        require(profiles.map { it.id }.distinct().size == profiles.size) { "Duplicate profile IDs" }
        profiles.forEach { profile ->
            require(profile.role == "implementation" || profile.tier == null)
            require(profile.imageResolution == null || profile.imageResolution in ImageResolutionTier.values) {
                "Invalid profile image resolution"
            }
            require(profile.reasoningByModel.keys.all { key ->
                val parts = key.split('\u0000')
                parts.size == 2 && parts.all { it.isNotBlank() }
            }) { "Invalid reasoning-memory model key" }
            require(profile.gptSpeedByModel.keys.all { key ->
                val parts = key.split('\u0000')
                parts.size == 2 && parts.all { it.isNotBlank() }
            }) { "Invalid GPT-speed-memory model key" }
        }
        require(parallelLimits.values.all { it >= 0 })
        require(legacyParallelLimits.all { (key, limit) -> key.matches(Regex("agent_model_parallel_[0-9a-f]{64}")) && limit >= 0 })
    }
    fun poolKey(owner: SubAgentConfigKey, model: SubAgentParallelModel): String =
        "subagent:v1:" + (if (owner is SubAgentConfigKey.Conversation) "c:" else "d:") +
            Base64.getUrlEncoder().withoutPadding().encodeToString(owner.value.toByteArray(Charsets.UTF_8)) + ":" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(model.providerId.toByteArray(Charsets.UTF_8)) + ":" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(model.apiModel.toByteArray(Charsets.UTF_8))
}

/** Instances using the same preferences share a lock, durability fence, and per-owner versions. */
internal class ConversationSubAgentPreferences(
    private val preferences: SharedPreferences = requireNotNull(Prefs.localAgentPreferences()) { "Initialize Prefs first" },
    private val canEdit: (SubAgentConfigKey) -> Boolean = { true },
) {
    companion object {
        internal const val SEED_KEY = "agent_conversation_child_seed_v1"
        internal const val OWNER_PREFIX = "agent_conversation_child_owner_v1_"
        private const val BIND_PREFIX = "agent_conversation_child_binding_v1_"
        private const val VERSION = 1
        private val lock = Any()
        private class SharedState {
            val changes = MutableStateFlow(0L)
            val owners = mutableMapOf<SubAgentConfigKey, MutableStateFlow<Long>>()
            // The original string values remain available even when both the write and rollback fail.
            var pending: Map<String, String?>? = null
        }
        private val states = java.util.WeakHashMap<SharedPreferences, SharedState>()
        private fun stateFor(preferences: SharedPreferences): SharedState = synchronized(lock) {
            states.getOrPut(preferences) { SharedState() }
        }
    }
    sealed class WriteResult {
        data class Saved(val revision: Long, val config: ConversationSubAgentConfig) : WriteResult()
        object Rejected : WriteResult()
    }
    private val state = stateFor(preferences)
    /** Global notification only; use revision(owner) for owner-specific concurrency checks. */
    val revision: StateFlow<Long> get() = state.changes
    fun revision(owner: SubAgentConfigKey): StateFlow<Long> = synchronized(lock) {
        state.owners.getOrPut(owner) { MutableStateFlow(0L) }
    }
    fun flow(owner: SubAgentConfigKey) = state.changes.map { snapshot(owner) }.distinctUntilChanged()
    private fun key(owner: SubAgentConfigKey): String = OWNER_PREFIX +
        (if (owner is SubAgentConfigKey.Conversation) "c_" else "d_") +
        Base64.getUrlEncoder().withoutPadding().encodeToString(owner.value.toByteArray(Charsets.UTF_8))
    private fun binding(conversation: SubAgentConfigKey.Conversation) = BIND_PREFIX + key(conversation).removePrefix(OWNER_PREFIX)
    private fun ensureClean() { check(state.pending == null) { "Sub-agent storage durability unknown; call recoverDurability() before accessing configs" } }

    /** Explicit retry of the ORIGINAL values; never treat an in-memory contains() as proof of durability. */
    fun recoverDurability(): Boolean = synchronized(lock) {
        val original = state.pending ?: return@synchronized true
        val restored = try {
            val edit = preferences.edit()
            original.forEach { (name, value) -> if (value == null) edit.remove(name) else edit.putString(name, value) }
            edit.commit() && original.all { (name, value) -> storedUnchecked(name) == value }
        } catch (_: Exception) { false }
        if (restored) state.pending = null
        restored
    }
    private fun storedUnchecked(name: String): String? {
        if (!preferences.contains(name)) return null
        return preferences.getString(name, null) ?: error("Invalid config value type: $name")
    }
    private fun stored(name: String): String? { ensureClean(); return storedUnchecked(name) }
    private fun transaction(changes: Map<String, String?>, vararg owners: SubAgentConfigKey) {
        ensureClean()
        val old = changes.keys.associateWith(::storedUnchecked)
        try {
            val edit = preferences.edit()
            changes.forEach { (name, value) -> if (value == null) edit.remove(name) else edit.putString(name, value) }
            check(edit.commit()) { "Sub-agent config commit failed" }
        } catch (failure: Exception) {
            state.pending = old
            // commit(false) may already have changed memory. A rollback is best effort;
            // only explicit recoverDurability() with a successful commit lifts the fence.
            try {
                val rollback = preferences.edit()
                old.forEach { (name, value) -> if (value == null) rollback.remove(name) else rollback.putString(name, value) }
                rollback.commit()
            } catch (_: Exception) { /* pending originals still held */ }
            throw failure
        }
        owners.distinct().forEach { owner ->
            val flow = state.owners.getOrPut(owner) { MutableStateFlow(0L) }
            flow.value = flow.value + 1
        }
        state.changes.value = state.changes.value + 1
    }
    private fun seed(persist: Boolean = true): ConversationSubAgentConfig {
        stored(SEED_KEY)?.let { return decode(it) }
        // Legacy profile records are migrated using their historical tolerant reader, NOT archive validation.
        val profiles = if (preferences.contains(SubAgentPreferences.PROFILES_KEY)) {
            val json = JSONObject(stored(SubAgentPreferences.PROFILES_KEY)!!)
            require(json.getInt("version") == 1)
            val array = json.getJSONArray("agents")
            (0 until array.length()).map { SubAgentProfile.fromJson(array.getJSONObject(it)) }
        } else listOf(0, 2, 3, 1).map { slot ->
            SubAgentProfile("legacy-$slot", when (slot) {
                0 -> "执行代理 1"; 1 -> "审查／总结代理"; 2 -> "执行代理 2"; else -> "执行代理 3"
            }, if (slot == 1) "review" else "implementation",
                providerId = preferences.getString("agent_child_${slot}_provider", "").orEmpty(),
                modelId = preferences.getString("agent_child_${slot}_model", "").orEmpty(),
                reasoning = ReasoningEffort.fromWireValue(preferences.getString("agent_child_${slot}_reasoning", "").orEmpty()),
                tier = if (slot == 1) null else SubAgentTaskTier.fromWireValue(preferences.getString("agent_child_${slot}_task_tier", "").orEmpty()))
        }
        val legacy = preferences.all.filterKeys { it.matches(Regex("agent_model_parallel_[0-9a-f]{64}")) }
            .mapValues { (_, raw) -> when (raw) {
                is Int -> raw
                is String -> raw.toIntOrNull() ?: error("Invalid legacy parallel limit")
                else -> error("Invalid legacy parallel limit type")
            }.also { require(it >= 0) } }
        val result = ConversationSubAgentConfig(profiles, legacyParallelLimits = legacy)
        result.validate()
        if (persist) transaction(mapOf(SEED_KEY to encode(result)))
        return result.detached()
    }
    private fun initial(owner: SubAgentConfigKey, persistSeed: Boolean = true): ConversationSubAgentConfig {
        val base = seed(persist = persistSeed).detached()
        if (owner is SubAgentConfigKey.Conversation) {
            val old = stored("agent_collaboration_${owner.value}")
            if (old != null) {
                require(old == "true" || old == "false") { "Invalid legacy collaboration switch" }
                return base.copy(enabled = old == "true")
            }
        }
        return base
    }
    private fun read(owner: SubAgentConfigKey): ConversationSubAgentConfig = stored(key(owner))?.let(::decode) ?: initial(owner)
    fun snapshot(owner: SubAgentConfigKey): ConversationSubAgentConfig = synchronized(lock) { read(owner).detached() }
    /** Same owner/legacy selection as runtime, without initializing or persisting the seed. */
    fun previewSnapshot(owner: SubAgentConfigKey): ConversationSubAgentConfig = synchronized(lock) {
        (stored(key(owner))?.let(::decode) ?: initial(owner, persistSeed = false)).detached()
    }
    /** No seed fallback: pointer recovery must distinguish absence from unreadable storage. */
    fun existingDraftOrNull(owner: SubAgentConfigKey.Draft): ConversationSubAgentConfig? = synchronized(lock) {
        stored(key(owner))?.let { decode(it).detached() }
    }

    fun createConversation(owner: SubAgentConfigKey.Conversation, source: SubAgentConfigKey? = null): ConversationSubAgentConfig = synchronized(lock) {
        stored(key(owner))?.let { return@synchronized decode(it).detached() }
        val config = (source?.let { read(it) } ?: initial(owner)).detached()
        transaction(mapOf(key(owner) to encode(config)), owner)
        config.detached()
    }
    fun createDraft(source: SubAgentConfigKey? = null): SubAgentConfigKey.Draft = synchronized(lock) {
        val config = (source?.let { read(it) } ?: seed()).detached()
        val owner = SubAgentConfigKey.Draft(UUID.randomUUID().toString())
        transaction(mapOf(key(owner) to encode(config)), owner)
        owner
    }
    private fun bindings(): Map<String, String> = preferences.all.keys.filter { it.startsWith(BIND_PREFIX) }
        .associateWith { stored(it) ?: error("Missing binding") }
    private fun assertUnshared(draft: SubAgentConfigKey.Draft, conversation: SubAgentConfigKey.Conversation) {
        require(bindings().none { (name, value) -> value == draft.value && name != binding(conversation) }) {
            "Draft is already bound to a different conversation"
        }
    }
    /** Persist owner and association atomically; only caller may confirm after Room commit. */
    fun bindDraft(draft: SubAgentConfigKey.Draft, conversation: SubAgentConfigKey.Conversation): ConversationSubAgentConfig = synchronized(lock) {
        ensureClean()
        assertUnshared(draft, conversation)
        val existingBinding = stored(binding(conversation))
        if (existingBinding != null) require(existingBinding == draft.value) { "Conversation bound to another draft" }
        stored(key(conversation))?.let {
            require(existingBinding == draft.value) { "Existing conversation is not bound to this draft" }
            require(stored(key(draft)) != null) { "Bound draft missing" }
            return@synchronized decode(it).detached()
        }
        require(existingBinding == null) { "Bound conversation config missing" }
        val data = stored(key(draft)) ?: error("Draft is absent: ${draft.value}")
        val config = decode(data)
        transaction(mapOf(key(conversation) to encode(config), binding(conversation) to draft.value), conversation)
        config.detached()
    }
    fun confirmBoundDraft(draft: SubAgentConfigKey.Draft, conversation: SubAgentConfigKey.Conversation): Boolean = synchronized(lock) {
        ensureClean()
        if (stored(binding(conversation)) != draft.value) return@synchronized false
        assertUnshared(draft, conversation)
        decode(stored(key(conversation)) ?: error("Bound conversation config missing"))
        decode(stored(key(draft)) ?: error("Draft config missing"))
        transaction(mapOf(key(draft) to null, binding(conversation) to null), draft, conversation)
        true
    }
    fun update(owner: SubAgentConfigKey, change: (ConversationSubAgentConfig) -> ConversationSubAgentConfig): WriteResult = synchronized(lock) {
        ensureClean()
        if (!canEdit(owner)) return@synchronized WriteResult.Rejected
        val next = change(read(owner).detached()).detached()
        next.validate()
        transaction(mapOf(key(owner) to encode(next)), owner)
        WriteResult.Saved(revision(owner).value, next.detached())
    }
    fun delete(owner: SubAgentConfigKey): Boolean = synchronized(lock) {
        ensureClean()
        if (owner is SubAgentConfigKey.Draft) require(bindings().values.none { it == owner.value }) { "Draft still bound" }
        if (stored(key(owner)) == null) return@synchronized false
        val names = mutableMapOf(key(owner) to null as String?)
        if (owner is SubAgentConfigKey.Conversation && stored(binding(owner)) != null) names[binding(owner)] = null
        transaction(names, owner)
        true
    }
    fun export(owner: SubAgentConfigKey): String = synchronized(lock) { encode(read(owner)) }
    /** Strict, read-only archive validation; no seed creation or writes. */
    fun validateArchive(raw: String) { decode(raw) }
    fun importOwner(owner: SubAgentConfigKey, archive: String, overwrite: Boolean = false): Boolean = synchronized(lock) {
        ensureClean()
        val config = decode(archive)
        if (!overwrite && stored(key(owner)) != null) {
            decode(stored(key(owner))!!)
            return@synchronized false
        }
        transaction(mapOf(key(owner) to encode(config.detached())), owner)
        true
    }
    private fun ownerFromSuffix(suffix: String): SubAgentConfigKey {
        require(suffix.startsWith("c_") || suffix.startsWith("d_")) { "Invalid owner key" }
        val encoded = suffix.substring(2)
        val value = String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        require(value.isNotBlank() && Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8)) == encoded)
        return if (suffix.startsWith("c_")) SubAgentConfigKey.Conversation(value) else SubAgentConfigKey.Draft(value)
    }
    fun validateRestoredPreferences() = synchronized(lock) {
        ensureClean()
        stored(SEED_KEY)?.let(::decode)
        val keys = preferences.all.keys
        keys.filter { it.startsWith(OWNER_PREFIX) }.forEach { name ->
            ownerFromSuffix(name.removePrefix(OWNER_PREFIX))
            decode(stored(name) ?: error("Missing owner payload: $name"))
        }
        val boundDrafts = mutableSetOf<String>()
        keys.filter { it.startsWith(BIND_PREFIX) }.forEach { name ->
            val owner = ownerFromSuffix(name.removePrefix(BIND_PREFIX))
            require(owner is SubAgentConfigKey.Conversation && stored(key(owner)) != null) { "Bound conversation missing" }
            val draftId = stored(name)
            require(!draftId.isNullOrBlank() && boundDrafts.add(draftId) && stored(key(SubAgentConfigKey.Draft(draftId))) != null) {
                "Invalid or shared bound draft association"
            }
        }
    }
    fun refreshAfterRestore() = synchronized(lock) {
        validateRestoredPreferences()
        state.changes.value = state.changes.value + 1
        // Restore is a global replacement; invalidate all previously observed owner versions.
        state.owners.values.forEach { it.value = it.value + 1 }
    }
    private fun encode(config: ConversationSubAgentConfig): String {
        config.validate()
        return JSONObject().put("version", VERSION).put("enabled", config.enabled)
            .put("diagnostics_enabled", config.diagnosticsEnabled)
            .put("agents", JSONArray(config.profiles.map { it.toJson() }))
            .put("parallel_limits", JSONArray(config.parallelLimits.map { (model, limit) ->
                JSONObject().put("provider", model.providerId).put("api_model", model.apiModel).put("limit", limit)
            }))
            .put("legacy_parallel_limits", JSONArray(config.legacyParallelLimits.map { (hash, limit) ->
                JSONObject().put("hash", hash).put("limit", limit)
            })).toString()
    }
    private fun string(j: JSONObject, name: String): String {
        require(j.has(name) && j.opt(name) is String) { "Invalid string: $name" }
        return j.getString(name)
    }
    private fun bool(j: JSONObject, name: String): Boolean {
        require(j.has(name) && j.opt(name) is Boolean) { "Invalid boolean: $name" }
        return j.getBoolean(name)
    }
    private fun int(j: JSONObject, name: String): Int {
        require(j.has(name) && j.opt(name) is Int) { "Invalid integer: $name" }
        return j.getInt(name)
    }
    private fun array(j: JSONObject, name: String): JSONArray {
        require(j.has(name) && j.opt(name) is JSONArray) { "Invalid array: $name" }
        return j.getJSONArray(name)
    }
    private fun profile(j: JSONObject): SubAgentProfile {
        require(string(j, "id").isNotBlank() && string(j, "name").isNotBlank())
        require(string(j, "role") in setOf("implementation", "review", "image_generation", "video_generation"))
        bool(j, "enabled")
        string(j, "provider"); string(j, "model")
        val tier = string(j, "tier")
        require(tier.isEmpty() || (j.getString("role") == "implementation" && SubAgentTaskTier.fromWireValue(tier) != null))
        val effort = string(j, "reasoning")
        require(effort.isEmpty() || ReasoningEffort.fromWireValue(effort) != null)
        val resolution = string(j, "image_resolution")
        require(resolution.isEmpty() || resolution in ImageResolutionTier.values)
        val memory = array(j, "reasoning_memory")
        val pairs = mutableSetOf<Pair<String, String>>()
        for (i in 0 until memory.length()) {
            val item = memory.getJSONObject(i)
            val provider = string(item, "provider"); val model = string(item, "model")
            require(provider.isNotBlank() && model.isNotBlank() && '\u0000' !in provider && '\u0000' !in model)
            require(pairs.add(provider to model))
            require(ReasoningEffort.fromWireValue(string(item, "reasoning")) != null)
        }
        // Absent in older archives. Present malformed fields must not silently lose user settings.
        if (j.has("gpt_speed_memory")) {
            val speeds = array(j, "gpt_speed_memory")
            val speedPairs = mutableSetOf<Pair<String, String>>()
            for (i in 0 until speeds.length()) {
                val item = speeds.getJSONObject(i)
                val provider = string(item, "provider"); val model = string(item, "model")
                require(provider.isNotBlank() && model.isNotBlank() && '\u0000' !in provider && '\u0000' !in model)
                require(speedPairs.add(provider to model))
                val speed = string(item, "speed")
                require(io.github.mangi.eta.data.model.GptSpeedMode.entries.any { it.name == speed })
            }
        }
        return SubAgentProfile.fromJson(j)
    }
    private fun decode(raw: String): ConversationSubAgentConfig {
        val json = JSONObject(raw)
        require(int(json, "version") == VERSION) { "Unsupported sub-agent config version" }
        val agents = array(json, "agents")
        val limits = array(json, "parallel_limits")
        val models = (0 until limits.length()).map { index ->
            val item = limits.getJSONObject(index)
            SubAgentParallelModel(string(item, "provider"), string(item, "api_model")) to int(item, "limit")
        }
        require(models.map { it.first }.distinct().size == models.size)
        // Earlier seed archives did not include this field; wrong-typed PRESENT values are never ignored.
        val legacy = if (json.has("legacy_parallel_limits")) array(json, "legacy_parallel_limits") else JSONArray()
        val hashes = (0 until legacy.length()).map { index ->
            val item = legacy.getJSONObject(index)
            string(item, "hash") to int(item, "limit")
        }
        require(hashes.map { it.first }.distinct().size == hashes.size)
        val config = ConversationSubAgentConfig(
            profiles = (0 until agents.length()).map { index -> profile(agents.getJSONObject(index)) },
            enabled = bool(json, "enabled"), parallelLimits = models.toMap(),
            diagnosticsEnabled = bool(json, "diagnostics_enabled"), legacyParallelLimits = hashes.toMap())
        config.validate()
        return config.detached()
    }
}
