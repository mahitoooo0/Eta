package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderTypes
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GptServiceTierTest {
    private fun config(mode: GptSpeedMode? = null) = AgentModelClient.ModelConfig(
        baseUrl = "https://relay.example.invalid/v1", apiKey = "", model = "gpt-99-relay",
        systemPrompt = "", gptSpeedMode = mode,
    )

    private fun build(config: AgentModelClient.ModelConfig, responses: Boolean): JSONObject =
        if (responses) ResponsesRequestBuilder.build(
            config.copy(openAiEndpointMode = OpenAiEndpointMode.RESPONSES), JSONArray(), JSONArray(),
        ) else OpenAiChatCompletionsProvider.buildRequestJson(config, JSONArray(), JSONArray())

    @Test fun modelConfigJsonRoundTripsModesAndOldJsonDefaultsToNull() {
        GptSpeedMode.entries.forEach { mode ->
            val original = config(mode)
            val encoded = Json.encodeToString(original)
            assertTrue(encoded.contains("\"gptSpeedMode\":\"${mode.name}\""))
            assertEquals(original, Json.decodeFromString<AgentModelClient.ModelConfig>(encoded))
        }
        val legacy = Json.encodeToString(config())
        assertFalse(legacy.contains("gptSpeedMode"))
        assertNull(Json.decodeFromString<AgentModelClient.ModelConfig>(legacy).gptSpeedMode)
        val explicitNull = JSONObject(legacy).put("gptSpeedMode", JSONObject.NULL).toString()
        assertNull(Json.decodeFromString<AgentModelClient.ModelConfig>(explicitNull).gptSpeedMode)
    }

    @Test fun bothProtocolsOverrideCustomTiersOnlyWhenModeIsExplicit() {
        val tiers = mapOf(GptSpeedMode.NORMAL to "default", GptSpeedMode.FAST to "fast",
            GptSpeedMode.ULTRA_FAST to "ultrafast")
        listOf(false, true).forEach { responses ->
            tiers.forEach { (mode, tier) ->
                val request = build(config(mode).copy(
                    extraBodyJson = "{\"service_tier\":\"auto\"}",
                    customBody = listOf(CustomBody("service_tier", JsonPrimitive("priority"))),
                ), responses)
                assertEquals(tier, request.getString("service_tier"))
                assertEquals("gpt-99-relay", request.getString("model"))
            }
            assertFalse(build(config(), responses).has("service_tier"))
            assertEquals("priority", build(config().copy(
                customBody = listOf(CustomBody("service_tier", JsonPrimitive("priority"))),
            ), responses).getString("service_tier"))
        }
    }

    @Test fun nonGptModelsNeverGetAnInjectedTier() {
        listOf(false, true).forEach { responses ->
            listOf("o3", "codex", "foo-gpt-5", "claude-sonnet", "deepseek-chat").forEach { model ->
                assertFalse(model, build(config(GptSpeedMode.FAST).copy(model = model), responses)
                    .has("service_tier"))
            }
        }
        val request = JSONObject().put("model", "gpt-5")
        GptServiceTier.apply(request, config(GptSpeedMode.FAST).copy(providerType = ProviderTypes.ANTHROPIC))
        assertEquals("fast", request.getString("service_tier"))
    }

    @Test fun finalActualModelRatherThanConfiguredLabelControlsInjection() {
        val toNonGpt = config(GptSpeedMode.FAST).copy(
            customBody = listOf(CustomBody("model", JsonPrimitive("o3"))),
        )
        assertFalse(build(toNonGpt, false).has("service_tier"))
        // Responses protects its model field from custom-body overrides.
        assertEquals("fast", build(toNonGpt, true).getString("service_tier"))
        val toGpt = config(GptSpeedMode.ULTRA_FAST).copy(model = "o3",
            customBody = listOf(CustomBody("model", JsonPrimitive("OpenAI/GPT-99-relay"))),
        )
        assertEquals("ultrafast", build(toGpt, false).getString("service_tier"))
        assertFalse(build(toGpt, true).has("service_tier"))
    }

    @Test fun endpointDoesNotRestrictGptTier() {
        val request = JSONObject().put("model", "gpt-6-astra")
        GptServiceTier.apply(request, config(GptSpeedMode.FAST).copy(openAiEndpointMode = "unknown"))
        assertEquals("fast", request.getString("service_tier"))
    }

    @Test fun helperChangesOnlyServiceTierAndDoesNotMutateSnapshots() {
        val original = config(GptSpeedMode.NORMAL)
        val request = JSONObject("{\"model\":\"gpt-5\",\"reasoning\":{\"effort\":\"high\"},\"reasoning_effort\":\"high\"}")
        GptServiceTier.apply(request, original)
        val sent = request.toString()
        val next = original.copy(gptSpeedMode = original.gptSpeedMode!!.next())
        assertEquals(GptSpeedMode.NORMAL, original.gptSpeedMode)
        assertEquals(sent, request.toString())
        assertEquals("fast", build(next, false).getString("service_tier"))
        assertEquals("gpt-5", request.getString("model"))
        assertEquals("high", request.getJSONObject("reasoning").getString("effort"))
        assertEquals("high", request.getString("reasoning_effort"))
        assertEquals(4, request.length())
    }
}
