package io.github.mangi.eta.agent.question

/** A bounded, authoritative runtime read; null at the client means transport uncertainty. */
internal data class AgentQuestionSnapshot(
    val conversationId: String,
    val runId: String,
    val questionId: String,
    val toolCallId: String,
    val status: AgentQuestionStatus,
    val answer: AgentQuestionAnswer? = null,
)
