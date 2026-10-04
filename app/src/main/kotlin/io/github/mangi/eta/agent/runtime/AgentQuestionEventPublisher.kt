package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.question.AgentQuestionInterruptedException

/** Shared production gate: a Coordinator Unit callback must never turn rejection into success. */
internal object AgentQuestionEventPublisher {
    fun publish(session: AgentRuntimeSession, event: AgentEvent, beforeDispatch: () -> Unit = {}) {
        try {
            if (!session.emitQuestion(event, beforeDispatch)) throw AgentQuestionInterruptedException()
        } catch (failure: AgentQuestionInterruptedException) {
            throw failure
        } catch (failure: Throwable) {
            throw AgentQuestionInterruptedException(failure)
        }
    }
}
