package io.github.mangi.eta.agent.question

import io.github.mangi.eta.agent.runtime.AgentEvent

/** Only ownership and accepted evidence, never prompt text or ordinary event replay. Caller serializes access. */
internal class AgentQuestionStateIndex(private val runId: String) {
    private val questions = linkedMapOf<String, AgentQuestionSnapshot>()

    fun prepare(event: AgentEvent): AgentQuestionSnapshot? = when (event) {
        is AgentEvent.QuestionRequested -> {
            val request = event.request
            val candidate = AgentQuestionSnapshot(request.conversationId, request.runId,
                request.questionId, request.toolCallId, AgentQuestionStatus.Waiting)
            if (request.runId != runId || !candidate.hasValidIdentity()) null
            else questions[request.questionId]?.let { previous ->
                // A duplicate is evidence of the SAME owner, not a new Waiting transition.
                previous.takeIf { it.sameIdentity(candidate) }
            } ?: candidate.takeIf { request.questionId !in questions }
        }
        is AgentEvent.QuestionResolved -> {
            val previous = questions[event.questionId]
            if (event.runId != runId || previous == null ||
                !validQuestionResolution(event.status, event.answer)) null
            else previous.copy(status = event.status, answer = event.answer)
        }
        else -> null
    }

    fun accept(snapshot: AgentQuestionSnapshot) { questions[snapshot.questionId] = snapshot }

    fun snapshot(conversationId: String, questionId: String, toolCallId: String): AgentQuestionSnapshot? =
        questions[questionId]?.takeIf { it.conversationId == conversationId && it.toolCallId == toolCallId }

    fun seal() {
        questions.replaceAll { _, snapshot ->
            if (snapshot.status == AgentQuestionStatus.Waiting) snapshot.copy(status = AgentQuestionStatus.Interrupted)
            else snapshot
        }
    }
}

internal fun AgentQuestionSnapshot.hasValidIdentity(): Boolean =
    listOf(conversationId, runId, questionId, toolCallId).all { it.isNotBlank() && it.length <= 1024 }

internal fun AgentQuestionSnapshot.sameIdentity(other: AgentQuestionSnapshot): Boolean =
    conversationId == other.conversationId && runId == other.runId &&
        questionId == other.questionId && toolCallId == other.toolCallId

/** Strict bounded persisted answer shape; the Coordinator validates against the original options. */
internal fun validQuestionResolution(status: AgentQuestionStatus, answer: AgentQuestionAnswer?): Boolean {
    if (status == AgentQuestionStatus.Waiting) return false
    if (status != AgentQuestionStatus.Answered) return answer == null
    answer ?: return false
    if (answer.note.length > AgentQuestionCodec.MAX_NOTE_CHARS ||
        answer.otherText.length > AgentQuestionCodec.MAX_OTHER_TEXT_CHARS) return false
    return when (answer.kind) {
        AgentQuestionAnswer.KIND_OPTION -> !answer.optionId.isNullOrBlank() &&
            answer.optionId.length <= AgentQuestionCodec.MAX_OPTION_ID_CHARS && answer.otherText.isBlank()
        AgentQuestionAnswer.KIND_OTHER -> answer.optionId.isNullOrBlank() && answer.otherText.isNotBlank()
        AgentQuestionAnswer.KIND_DELEGATE -> answer.optionId.isNullOrBlank() && answer.otherText.isBlank()
        else -> false
    }
}
