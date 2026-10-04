package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.question.AgentQuestionLedger
import io.github.mangi.eta.agent.question.AgentQuestionSnapshot

/** The Service query policy, independent of Android IPC and delivery/checkpoint/archive lifetime. */
internal object AgentQuestionAuthority {
    fun query(sessions: AgentRuntimeSessionRegistry, ledger: AgentQuestionLedger,
        conversationId: String, runId: String, questionId: String, toolCallId: String): AgentQuestionSnapshot? {
        if (!listOf(conversationId, runId, questionId, toolCallId).all { it.isNotBlank() && it.length <= 1024 }) return null
        return sessions.get(runId)?.questionSnapshot(conversationId, questionId, toolCallId)
            ?: ledger.query(conversationId, runId, questionId, toolCallId)
    }
}
