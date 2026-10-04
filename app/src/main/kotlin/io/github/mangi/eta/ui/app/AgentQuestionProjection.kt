package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.question.*
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi

/** Pure projection; drafts belong to their exact conversation/run/call/question. */
internal object AgentQuestionProjection {
    fun sameOwner(a: AgentQuestionRequest, b: AgentQuestionRequest): Boolean =
        a.conversationId == b.conversationId && a.runId == b.runId &&
        a.toolCallId == b.toolCallId && a.questionId == b.questionId

    fun messageId(r: AgentQuestionRequest): String = "question-" +
        listOf(r.conversationId, r.runId, r.questionId, r.toolCallId).joinToString("") { "${it.length}:$it" }

    fun requested(conversationId: String, runId: String, request: AgentQuestionRequest,
        messages: List<AgentChatMessageUi>, acceptNew: Boolean = true, replaying: Boolean = false): List<AgentChatMessageUi> {
        if (request.conversationId != conversationId || request.runId != runId) return messages
        val index = messages.indexOfFirst { it is AgentQuestionMessageUi && sameOwner(it.request, request) }
        if (index >= 0) return messages // Replay never reactivates or moves existing cards.

        return if (acceptNew) messages + AgentQuestionMessageUi(messageId(request), request) else messages
    }

    fun resolved(conversationId: String, runId: String, event: AgentEvent.QuestionResolved,
        messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> {
        if (event.runId != runId || event.status == AgentQuestionStatus.Waiting) return messages
        val index = messages.indices.filter { i ->
            val m = messages[i] as? AgentQuestionMessageUi
            m != null && m.request.conversationId == conversationId && m.request.runId == runId &&
                m.request.questionId == event.questionId
        }.singleOrNull() ?: return messages
        val current = messages[index] as AgentQuestionMessageUi
        if (current.status == AgentQuestionStatus.Answered) return messages
        if (event.status == AgentQuestionStatus.Answered &&
            (event.answer == null || !AgentQuestionCodec.validateAnswer(current.request, event.answer).accepted)) return messages
        return messages.mapIndexed { i, m -> if (i == index) current.copy(status = event.status,
            answer = event.answer.takeIf { event.status == AgentQuestionStatus.Answered },
            submitting = false, error = null) else m }
    }

    fun interruptWaiting(runId: String, messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> =
        messages.map { m -> if (m is AgentQuestionMessageUi && m.request.runId == runId &&
            m.status == AgentQuestionStatus.Waiting) m.copy(status = AgentQuestionStatus.Interrupted,
                submitting = false, error = null) else m }

    fun hasWaiting(messages: List<AgentChatMessageUi>): Boolean = messages.any {
        it is AgentQuestionMessageUi && it.status == AgentQuestionStatus.Waiting
    }

    fun draftAnswer(m: AgentQuestionMessageUi): AgentQuestionAnswer = AgentQuestionAnswer(m.answerKind,
        m.selectedOptionId.takeIf { m.answerKind == "option" },
        if (m.answerKind == "other") m.otherText else "", if (m.request.allowNote) m.note else "")

    /** A terminal event can beat the receipt. Closed, non-Answered cards still need a read. */
    fun needsAuthoritativeQuery(messages: List<AgentChatMessageUi>, owner: AgentQuestionRequest): Boolean =
        messages.any { it is AgentQuestionMessageUi && sameOwner(it.request, owner) &&
            it.status != AgentQuestionStatus.Answered }

    fun reconcileMessages(messages: List<AgentChatMessageUi>, owner: AgentQuestionRequest,
        snapshot: AgentQuestionSnapshot?): List<AgentChatMessageUi> = messages.map { m ->
        if (m is AgentQuestionMessageUi && sameOwner(m.request, owner)) reconcile(m, snapshot) else m
    }

    /** Only this conversation's exact owners may be queried, including locally closed cards. */
    fun recoveryOwners(conversationId: String, messages: List<AgentChatMessageUi>): List<AgentQuestionRequest> =
        messages.filterIsInstance<AgentQuestionMessageUi>()
            .filter { it.request.conversationId == conversationId && it.status != AgentQuestionStatus.Answered }
            .map { it.request }.distinctBy(::messageId)

    data class RecoveryQueryBatch(val owners: List<AgentQuestionRequest>, val nextOffset: Int)

    /** Finite work per recovery pass. Rotate on subsequent passes; omitted cards are not dead. */
    fun recoveryQueryBatch(owners: List<AgentQuestionRequest>, offset: Int,
        limit: Int = MAX_RECOVERY_QUERIES): RecoveryQueryBatch {
        require(limit in 1..MAX_RECOVERY_QUERIES)
        val unique = owners.distinctBy(::messageId)
        if (unique.isEmpty()) return RecoveryQueryBatch(emptyList(), 0)
        val start = offset.mod(unique.size)
        val count = minOf(limit, unique.size)
        return RecoveryQueryBatch(List(count) { unique[(start + it) % unique.size] },
            (start + count) % unique.size)
    }

    const val MAX_RECOVERY_QUERIES = 8
    const val RECOVERY_QUERY_CONCURRENCY = 4
    const val RECOVERY_QUERY_TIMEOUT_MILLIS = 5_000L

    /** Branch history cannot submit into the source run, even after ownership is rewritten. */
    fun freezeForBranch(current: AgentQuestionMessageUi, conversationId: String): AgentQuestionMessageUi =
        current.copy(request = current.request.copy(conversationId = conversationId),
            status = if (current.status == AgentQuestionStatus.Waiting) AgentQuestionStatus.Interrupted else current.status,
            submitting = false, error = null)

    fun reconcile(current: AgentQuestionMessageUi, snapshot: AgentQuestionSnapshot?): AgentQuestionMessageUi {
        // Unknown transport is not evidence of a run ending, nor of an answer being consumed.
        if (snapshot == null) return current.copy(submitting = false)
        val r = current.request
        if (snapshot.conversationId != r.conversationId || snapshot.runId != r.runId ||
            snapshot.questionId != r.questionId || snapshot.toolCallId != r.toolCallId) return current

        // Authority order: validated Answered corrects even local Interrupted/Cancelled;
        // an existing Answered then dominates all non-Answered snapshots; other authoritative
        // terminal states may correct each other. Waiting never reopens a closed card.
        if (snapshot.status == AgentQuestionStatus.Answered) {
            val answer = snapshot.answer
            if (answer == null || !AgentQuestionCodec.validateAnswer(r, answer).accepted) {
                return current.copy(submitting = false)
            }
            return current.copy(status = AgentQuestionStatus.Answered, answer = answer,
                submitting = false, error = null)
        }
        if (snapshot.answer != null) return current.copy(submitting = false) // Malformed non-Answered snapshot.
        if (current.status == AgentQuestionStatus.Answered) return current.copy(submitting = false)
        if (snapshot.status == AgentQuestionStatus.Waiting) {
            return if (current.status == AgentQuestionStatus.Waiting) current.copy(submitting = false, error = null)
                else current.copy(submitting = false)
        }
        return current.copy(status = snapshot.status, answer = null, submitting = false, error = null)
    }

    fun acknowledged(current: AgentQuestionMessageUi, answer: AgentQuestionAnswer,
        receipt: AgentQuestionReceipt): AgentQuestionMessageUi {
        if (current.status != AgentQuestionStatus.Waiting) return current.copy(submitting = false)
        if (!receipt.accepted) {
            // Even an ended/not-pending ACK may have raced an already consumed answer.
            // Only the subsequent authoritative read may close or correct this card.
            return current.copy(submitting = false, error = receipt.message.ifBlank { receipt.code })
        }
        // Acceptance is not publication. Never synthesize an Answered event from a receipt.
        return current.copy(submitting = true, error = null)
    }
}
