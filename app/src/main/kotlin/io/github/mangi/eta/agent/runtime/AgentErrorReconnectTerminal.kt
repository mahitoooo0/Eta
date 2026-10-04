package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentHttpFailureDiagnostics
import io.github.mangi.eta.agent.model.AgentModelFailure
import java.util.UUID

/** Final executor failures still have a marker even if they failed before a provider retry boundary. */
internal object AgentErrorReconnectTerminal {
    fun failureEvent(events: List<AgentEvent>, failure: Throwable, apiKey: String): AgentEvent.ErrorReconnectChanged? {
        val last = events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().lastOrNull()
        if (last?.status == "failed" || last?.status == "stopped") return null
        val round = events.filterIsInstance<AgentEvent.RoundStarted>().lastOrNull()?.round ?: 0
        val cause = generateSequence(failure) { it.cause }.filterIsInstance<AgentModelFailure>().firstOrNull()
        return if (last?.status == "running") last.copy(status = "failed")
        else AgentEvent.ErrorReconnectChanged(round, UUID.randomUUID().toString(), "failed", 0,
            cause?.code ?: "RUN_FAILED",
            AgentHttpFailureDiagnostics.safe(cause?.message ?: failure.message.orEmpty(), listOf(apiKey), 600))
    }
}
