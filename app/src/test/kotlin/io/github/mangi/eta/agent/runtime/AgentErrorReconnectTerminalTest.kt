package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelFailure
import org.junit.Assert.*
import org.junit.Test

class AgentErrorReconnectTerminalTest {
    @Test fun finalExceptionWithoutReconnectStillHasFailedMarkerAndDoesNotLeakCredential() {
        val marker = requireNotNull(AgentErrorReconnectTerminal.failureEvent(listOf(AgentEvent.RoundStarted(4, 2)),
            IllegalStateException("failed secret-key"), "secret-key"))
        assertEquals("failed", marker.status)
        assertEquals(4, marker.round)
        assertFalse(marker.reasonDetail.contains("secret-key"))
    }
    @Test fun runningFailureKeepsStableIdAndElapsedAndTerminalIsNotDuplicated() {
        val running = AgentEvent.ErrorReconnectChanged(2, "stable", "running", 4000, "HTTP_401", "detail")
        val failure = AgentModelFailure("ERROR_RECONNECT_DEADLINE", false, "deadline")
        val marker = requireNotNull(AgentErrorReconnectTerminal.failureEvent(listOf(running), failure, ""))
        assertEquals(running.copy(status = "failed"), marker)
        assertNull(AgentErrorReconnectTerminal.failureEvent(listOf(marker), failure, ""))
        assertNull(AgentErrorReconnectTerminal.failureEvent(listOf(marker.copy(status = "stopped")), failure, ""))
    }
    @Test fun failureAfterPreviouslySucceededRequestGetsNewDisconnectId() {
        val succeeded = AgentEvent.ErrorReconnectChanged(1, "old", "succeeded", 1000)
        val marker = requireNotNull(AgentErrorReconnectTerminal.failureEvent(listOf(succeeded), IllegalStateException(), ""))
        assertEquals("failed", marker.status)
        assertNotEquals(succeeded.reconnectId, marker.reconnectId)
    }
}
