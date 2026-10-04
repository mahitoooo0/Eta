package io.github.mangi.eta.agent.runtime

import android.os.Bundle
import android.os.ParcelFileDescriptor
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.GptSpeedMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GptSpeedModeWireTest {
    private fun bundle(mode: GptSpeedMode?, legacyEncoder: Boolean = false): Bundle {
        val request = AgentRuntimeWire.RunRequest(
            runId = "speed-mode", prompt = "hello", images = emptyList(),
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid/v1",
                apiKey = "", model = "gpt-5", systemPrompt = "", gptSpeedMode = mode),
        )
        val pipe = ParcelFileDescriptor.createPipe()
        return try {
            val encoded = if (legacyEncoder) AgentRuntimeWire.toLegacyBundle(request, pipe[0])
                else AgentRuntimeWire.toBundle(request, emptyList(), pipe[0])
            // No history in these fixtures; detach before closing both pipe ends.
            encoded.remove(AgentRuntimeWire.KEY_HISTORY_FD)
            encoded
        } finally {
            pipe[0].close()
            pipe[1].close()
        }
    }

    @Test fun everyModeSurvivesBothBundleEncoders() {
        listOf(false, true).forEach { legacy ->
            GptSpeedMode.entries.forEach { mode ->
                val encoded = bundle(mode, legacy)
                assertEquals(mode.name, encoded.getString("gpt_speed_mode"))
                assertEquals(mode, AgentRuntimeWire.runRequestFromBundle(encoded).config.gptSpeedMode)
            }
        }
    }

    @Test fun nullMissingAndUnknownFieldsDoNotInventAMode() {
        val noMode = bundle(null)
        assertFalse(noMode.containsKey("gpt_speed_mode"))
        assertNull(AgentRuntimeWire.runRequestFromBundle(noMode).config.gptSpeedMode)
        val oldBundle = bundle(GptSpeedMode.FAST).apply { remove("gpt_speed_mode") }
        assertNull(AgentRuntimeWire.runRequestFromBundle(oldBundle).config.gptSpeedMode)
        oldBundle.putString("gpt_speed_mode", "FUTURE_MODE")
        assertNull(AgentRuntimeWire.runRequestFromBundle(oldBundle).config.gptSpeedMode)
    }
}
