package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentParentNetworkFailureTest {
    @Test fun reconnectDeadlinePreservesOriginalChildDecisionFlow() {
        val deadline = AgentModelFailure("ERROR_RECONNECT_DEADLINE", false, "deadline",
            AgentModelFailure("HTTP_401", false, "authentication"))
        assertTrue(AgentParentNetworkFailure.isFinal(deadline, cancelled = false))
        assertFalse(AgentParentNetworkFailure.isFinal(deadline, cancelled = true))
    }

    @Test fun guardedNetworkFailureStillOffersChildDecisionWithoutReplayingTools() {
        val guarded = AgentModelFailure("UNSAFE_TOOL_REPLAY", false, "protected",
            AgentModelFailure("STREAM_INCOMPLETE", true, "connection lost"))
        assertTrue(AgentParentNetworkFailure.isFinal(guarded, cancelled = false))
        assertFalse(AgentParentNetworkFailure.isFinal(guarded, cancelled = true))
        val permanent = AgentModelFailure("UNSAFE_TOOL_REPLAY", false, "protected",
            AgentModelFailure("HTTP_401", false, "authentication"))
        assertFalse(AgentParentNetworkFailure.isFinal(permanent, cancelled = false))
    }

    @Test fun exhaustedRetryWrapperIsFinalNetworkFailure() {
        val connection = AgentModelFailure("MODEL_CONNECTION_FAILED", true, "connection")
        val exhausted = AgentModelFailure(connection.code, false, "exhausted", connection)
        assertTrue(AgentParentNetworkFailure.isFinal(exhausted, cancelled = false))
    }

    @Test fun cancellationNeverPromptsEvenIfLastAttemptWasNetworkFailure() {
        val timeout = AgentModelFailure("MODEL_TIMEOUT", true, "timeout")
        assertFalse(AgentParentNetworkFailure.isFinal(timeout, cancelled = true))
        assertFalse(AgentParentNetworkFailure.isFinal(InterruptedException(), cancelled = false))
    }

    @Test fun finalPartialStreamFailureDoesNotRequireReplayingRequest() {
        assertTrue(AgentParentNetworkFailure.isFinal(
            AgentModelFailure("STREAM_INCOMPLETE", true, "incomplete"), cancelled = false,
        ))
    }

    @Test fun permanentProviderAndToolFailuresAreNotNetworkFailures() {
        for (code in listOf("HTTP_401", "HTTP_400", "HTTP_429", "CONTEXT_WINDOW_EXCEEDED", "TOOL_ENVELOPE_REJECTED")) {
            assertFalse(code, AgentParentNetworkFailure.isFinal(
                AgentModelFailure(code, false, "permanent"), cancelled = false,
            ))
        }
        assertFalse(AgentParentNetworkFailure.isFinal(IllegalStateException("tool failed"), cancelled = false))
    }
}
