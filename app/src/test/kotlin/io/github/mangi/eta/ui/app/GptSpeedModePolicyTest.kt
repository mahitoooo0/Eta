package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.*
import org.junit.Test

class GptSpeedModePolicyTest {
    @Test fun switchingAwayAndBackDoesNotRestoreSpeed() {
        for (active in listOf(GptSpeedMode.FAST, GptSpeedMode.ULTRA_FAST)) {
            val away = GptSpeedModePolicy.forBinding(active, "deepseek-chat")
            assertEquals(GptSpeedMode.NORMAL, away)
            assertEquals(GptSpeedMode.NORMAL, GptSpeedModePolicy.forBinding(away, "gpt-6-astra"))
        }
    }

    @Test fun missingModelUnsupportedProtocolAndNonTextModelsReset() {
        assertEquals(GptSpeedMode.NORMAL, GptSpeedModePolicy.forBinding(GptSpeedMode.FAST, ""))
        assertEquals(GptSpeedMode.NORMAL, GptSpeedModePolicy.forBinding(GptSpeedMode.FAST, "claude-opus"))
        assertEquals(GptSpeedMode.NORMAL, GptSpeedModePolicy.forBinding(GptSpeedMode.FAST, "gpt-6-astra", false))
        assertEquals(GptSpeedMode.FAST, GptSpeedModePolicy.forBinding(GptSpeedMode.FAST, "gpt-image-1"))
        assertEquals(GptSpeedMode.NORMAL, GptSpeedModePolicy.cycle(GptSpeedMode.NORMAL, "grok-4"))
    }

    @Test fun normalFastUltraNormalKeepsReasoningIndependent() {
        val first = GptSpeedModePolicy.cycle(GptSpeedMode.NORMAL, "gpt-6-astra")
        val second = GptSpeedModePolicy.cycle(first, "gpt-6-astra")
        assertEquals(GptSpeedMode.FAST, first)
        assertEquals(GptSpeedMode.ULTRA_FAST, second)
        assertEquals(GptSpeedMode.NORMAL, GptSpeedModePolicy.cycle(second, "gpt-6-astra"))
        val raw = config().copy(reasoningEffort = ReasoningEffort.HIGH, thinkingEnabled = true)
        val frozen = GptSpeedModePolicy.snapshot(raw, first)
        val later = GptSpeedModePolicy.snapshot(raw, second)
        assertEquals(GptSpeedMode.FAST, frozen.gptSpeedMode)
        assertEquals(GptSpeedMode.ULTRA_FAST, later.gptSpeedMode)
        assertEquals(ReasoningEffort.HIGH, frozen.reasoningEffort)
        assertNull(raw.gptSpeedMode)
    }

    @Test fun snapshotNeverCarriesTheOldTierIntoAnotherModelOrMedia() {
        val fast = GptSpeedModePolicy.snapshot(config(), GptSpeedMode.FAST)
        assertNull(GptSpeedModePolicy.snapshot(fast.copy(model = "deepseek-chat"), GptSpeedMode.FAST).gptSpeedMode)
        assertEquals(GptSpeedMode.FAST, GptSpeedModePolicy.snapshot(fast.copy(providerType = ProviderTypes.ANTHROPIC), GptSpeedMode.FAST).gptSpeedMode)
        assertNull(GptSpeedModePolicy.snapshot(fast, GptSpeedMode.FAST, false).gptSpeedMode)
        assertEquals(GptSpeedMode.NORMAL, GptSpeedModePolicy.snapshot(config(), GptSpeedMode.NORMAL).gptSpeedMode)
    }

    @Test fun backgroundBindingCannotRecoverItsOldModeAfterSettingsReturn() {
        val model = Model(id = "m", modelId = "gpt-6-astra", displayName = "GPT")
        val provider = OpenAiCompatibleProviderSetting(id = "p", name = "relay",
            baseUrl = "https://example.invalid", models = listOf(model))
        val states = mutableMapOf("foreground" to GptSpeedMode.FAST, "background" to GptSpeedMode.ULTRA_FAST)
        val away = provider.copy(models = listOf(model.copy(modelId = "deepseek-chat")))
        states.keys.toList().forEach { id ->
            states[id] = GptSpeedModePolicy.forSelection(states.getValue(id), "p", "m", listOf(away))
        }
        states.keys.toList().forEach { id ->
            assertEquals(GptSpeedMode.NORMAL,
                GptSpeedModePolicy.forSelection(states.getValue(id), "p", "m", listOf(provider)))
        }
        assertEquals(GptSpeedMode.NORMAL,
            GptSpeedModePolicy.forSelection(GptSpeedMode.FAST, "p", "m", emptyList()))
        assertEquals(GptSpeedMode.NORMAL,
            GptSpeedModePolicy.forSelection(GptSpeedMode.FAST, "p", "m", listOf(provider.copy(models = emptyList()))))
        assertEquals(GptSpeedMode.FAST, GptSpeedModePolicy.snapshot(config().copy(openAiEndpointMode = "unknown"), GptSpeedMode.FAST).gptSpeedMode)
    }

    @Test fun customResponsesBindingProjectsAndFreezesChatSpeed() {
        val model = Model("m", "gpt-6-astra", "GPT")
        val provider = io.github.mangi.eta.data.model.CustomProviderSetting("p", "GPT", "https://example.invalid",
            endpointMode = io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES, models = listOf(model))
        val picker = io.github.mangi.eta.ui.model.AgentModelPickerProjector.project(listOf(provider), "p", "m")
        assertTrue(requireNotNull(picker.selectedModel).gptSpeedSupported)
        val mode = GptSpeedModePolicy.cycle(GptSpeedMode.NORMAL, model.modelId,
            io.github.mangi.eta.data.model.supportsGptSpeedBinding(provider, model))
        assertEquals(GptSpeedMode.FAST, mode)
        assertEquals(mode, GptSpeedModePolicy.forSelection(mode, "p", "m", listOf(provider)))
        val runtime = io.github.mangi.eta.data.repository.RuntimeConfigRepository.buildRuntimeConfig(provider, model)
        assertEquals(mode, GptSpeedModePolicy.snapshot(runtime, mode).gptSpeedMode)
    }

    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "", model = "gpt-6-astra", systemPrompt = "",
    )
}
