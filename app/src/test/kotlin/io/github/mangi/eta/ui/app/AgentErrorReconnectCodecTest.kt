package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.ErrorReconnectMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectStatus
import io.github.mangi.eta.ui.model.errorReconnectMessageId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentErrorReconnectCodecTest {
    @Test fun serializationPreservesEachStatusIdentityAndUnboundedDiagnostic() {
        val detail = "\"quoted\"\nUnicode 错误：" + "diagnostic\n".repeat(2_000)
        ErrorReconnectStatus.entries.forEach { status ->
            val marker = ErrorReconnectMessageUi(
                id = errorReconnectMessageId("run:1", "disconnect:1"), runId = "run:1", reconnectId = "disconnect:1",
                round = 7, status = status, elapsedMs = Long.MAX_VALUE, reasonCode = "HTTP_502", reasonDetail = detail,
            )
            assertEquals(marker, AgentErrorReconnectCodec.decode(marker.id, AgentErrorReconnectCodec.encode(marker), restoreTerminal = false))
            val restarted = AgentErrorReconnectCodec.decode(marker.id, AgentErrorReconnectCodec.encode(marker))
            assertEquals(marker.copy(status = if (status == ErrorReconnectStatus.Running) ErrorReconnectStatus.Stopped else status), restarted)
        }
    }

    @Test fun malformedAndOldPayloadsHaveConservativeDefaults() {
        assertNull(AgentErrorReconnectCodec.decode("id", "not json"))
        val restored = requireNotNull(AgentErrorReconnectCodec.decode("old-id", "{\"status\":\"failed\",\"elapsedMs\":-1}"))
        assertEquals("old-id", restored.id)
        assertEquals(0L, restored.elapsedMs)
        assertEquals(ErrorReconnectStatus.Failed, restored.status)
        assertEquals("", restored.reasonDetail)
        val unknown = requireNotNull(AgentErrorReconnectCodec.decode("id", "{\"status\":\"future\"}"))
        assertEquals(ErrorReconnectStatus.Failed, unknown.status)
    }
}
