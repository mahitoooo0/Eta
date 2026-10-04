package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent

/** Historical retry notices are not a live retry wait. Track the event state per run. */
internal class AgentRunRetryState {
    private val waiting = mutableSetOf<String>()
    private val reconnecting = mutableMapOf<String, MutableSet<String>>()

    fun accept(runId: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.ModelRetryScheduled -> waiting.add(runId)
            is AgentEvent.ErrorReconnectChanged -> {
                if (event.status == "running") reconnecting.getOrPut(runId) { mutableSetOf() }.add(event.reconnectId)
                else reconnecting[runId]?.remove(event.reconnectId)
            }
            is AgentEvent.ProviderRequestStarted -> waiting.remove(runId)
            is AgentEvent.RunFinished,
            is AgentEvent.RunFailed -> clear(runId)
            else -> Unit
        }
    }

    fun isWaiting(runId: String): Boolean = runId in waiting
    fun isReconnecting(runId: String): Boolean = reconnecting[runId]?.isNotEmpty() == true
    fun clear(runId: String) {
        waiting.remove(runId)
        reconnecting.remove(runId)
    }
}
