package io.github.mangi.eta.data.model

import org.junit.Assert.*
import org.junit.Test

class GptSpeedModeTest {
    @Test fun cyclesThroughAllThreeModes() {
        assertEquals(GptSpeedMode.FAST, GptSpeedMode.NORMAL.next())
        assertEquals(GptSpeedMode.ULTRA_FAST, GptSpeedMode.FAST.next())
        assertEquals(GptSpeedMode.NORMAL, GptSpeedMode.ULTRA_FAST.next())
    }

    @Test fun recognizesGptByActualModelIdOnly() {
        listOf("gpt-6-astra", "gpt-4o", "gpt-oss-120b", "gpt-image-1", "gpt-audio",
            "gpt-realtime", " OpenAI/GPT-6-ASTRA ", "provider/team/gpt-5-mini:free").forEach {
            assertTrue(it, isGptSpeedModel(it))
        }
        listOf("", "gpt", "claude-sonnet", "o3", "my gpt-5", "foo-gpt-5", "provider/gpt-5/chat").forEach {
            assertFalse(it, isGptSpeedModel(it))
        }
    }

    @Test fun providerTypeEndpointAndMetadataDoNotRestrictGpt() {
        val model = Model("m", "gpt-6-astra", "Other display name")
        val providers = listOf<ProviderSetting>(
            OpenAiCompatibleProviderSetting("p", "relay", "https://example.invalid", endpointMode = "unknown"),
            CustomProviderSetting("p", "relay", "https://example.invalid", endpointMode = OpenAiEndpointMode.RESPONSES),
            AnthropicProviderSetting("p", "relay", "https://example.invalid"),
        )
        for (provider in providers) {
            assertTrue(supportsGptSpeedBinding(provider, model))
            assertTrue(supportsGptSpeedBinding(provider, model.copy(outputModalities = listOf(Model.IMAGE_MODALITY))))
            assertFalse(supportsGptSpeedBinding(provider, model.copy(isEnabled = false)))
            assertFalse(supportsGptSpeedBinding(provider, model.copy(modelId = "deepseek-chat", displayName = "gpt-6-astra")))
        }
        assertFalse(supportsGptSpeedBinding(null, model))
        assertFalse(supportsGptSpeedBinding(providers.first(), null))
        assertFalse(supportsGptSpeedBinding((providers.first() as OpenAiCompatibleProviderSetting).copy(isEnabled = false), model))
    }
}
