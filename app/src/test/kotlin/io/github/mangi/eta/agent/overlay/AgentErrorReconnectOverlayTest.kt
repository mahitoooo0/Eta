package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.*
import org.junit.Test

class AgentErrorReconnectOverlayTest {
    @Test fun reconnectEventsHideDiagnosticsAndDoNotClaimAnAnswer() {
        val running = AgentOverlayState.Initial.applyEvent(
            AgentEvent.ErrorReconnectChanged(2, "disconnect", "running", 1000, "HTTP_500", "private diagnostic"))
        assertEquals(AgentOverlayPhase.RUNNING, running.phase)
        assertEquals(AgentOverlayStatus.RequestingModel, running.status)
        assertEquals("", running.detailText)
        val stopped = running.applyEvent(
            AgentEvent.ErrorReconnectChanged(2, "disconnect", "stopped", 1500, "HTTP_500", "private diagnostic"))
        assertEquals(AgentOverlayPhase.FINISHED, stopped.phase)
        assertEquals(AgentOverlayStatus.Stopped, stopped.status)
        assertEquals("", stopped.detailText)
        val failed = running.applyEvent(
            AgentEvent.ErrorReconnectChanged(2, "disconnect", "failed", 30000, "HTTP_500", "private diagnostic"))
        assertEquals(AgentOverlayPhase.FAILED, failed.phase)
        assertEquals("", failed.detailText)
    }
}
