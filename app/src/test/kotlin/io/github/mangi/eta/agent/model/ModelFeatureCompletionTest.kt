package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ModelFeatureCompletionTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://example.com/v1", apiKey = "test",
        model = "model", systemPrompt = "", hostedWebSearchEnabled = true)
    private fun provider(block: (ProviderRequest, AgentRunController) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fake"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, false, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = block(request, runController)
    }
    private fun response(text: String, reason: String = "stop") = ProviderResponse(JSONObject()
        .put("role", "assistant").put("content", text).put("finish_reason", reason))

    @Test fun requestsAreToolFreeAndUseExplicitOutputBudget() {
        val result = ModelFeatureCompletion.complete(config, JSONArray(), AgentRunController(), "test", providerOverride = provider { request, _ ->
            assertEquals(0, request.tools.length())
            assertFalse(request.config.hostedWebSearchEnabled)
            assertEquals(2048, request.config.summaryOutputLimit)
            response("猫")
        })
        assertEquals("猫", result)
    }

    @Test fun auxiliaryRequestKeepsExplicitConversationOwner() {
        ModelFeatureCompletion.complete(config, JSONArray(), AgentRunController(), "private-vision-session",
            usageConversationId = "conversation-owner", providerOverride = provider { request, _ ->
                assertEquals("private-vision-session", request.sessionId)
                assertEquals("conversation-owner", request.usageConversationId)
                response("description")
            })
    }

    @Test fun truncatedAndToolCallingResponsesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelFeatureCompletion.complete(config, JSONArray(), AgentRunController(), "test",
                providerOverride = provider { _, _ -> response("partial", "length") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelFeatureCompletion.complete(config, JSONArray(), AgentRunController(), "test",
                providerOverride = provider { _, _ -> response("text").also {
                    it.assistantMessage.put("tool_calls", JSONArray().put(JSONObject().put("id", "call")))
                } })
        }
    }

    @Test fun reconnectPolicyDoesNotUseOldWholeFeatureDeadline() {
        val events = mutableListOf<io.github.mangi.eta.agent.runtime.AgentEvent.ErrorReconnectChanged>()
        var calls = 0
        val result = ModelFeatureCompletion.complete(config.copy(errorReconnectPolicy = "window_5m"),
            JSONArray(), AgentRunController(), "test", timeoutMs = 50,
            onErrorReconnect = events::add, providerOverride = provider { _, controller ->
                calls++
                if (calls == 1) throw java.io.IOException("offline")
                assertFalse(controller.isCancelled)
                response("recovered")
            })
        assertEquals("recovered", result)
        assertEquals(2, calls)
        assertEquals("running", events.first().status)
        assertEquals("succeeded", events.last().status)
    }

    @Test fun totalDeadlineCancelsProviderResource() {
        val cancelled = CountDownLatch(1)
        assertThrows(IllegalStateException::class.java) {
            ModelFeatureCompletion.complete(config, JSONArray(), AgentRunController(), "test", timeoutMs = 100,
                providerOverride = provider { _, controller ->
                    val binding = controller.register { cancelled.countDown() }
                    try {
                        assertTrue(cancelled.await(3, TimeUnit.SECONDS))
                        response("late")
                    } finally { binding.close() }
                })
        }
    }

    @Test fun steeringCancelsAuxiliaryRequestWithoutSealingMainRun() {
        val parent = AgentRunController()
        assertThrows(IllegalStateException::class.java) {
            ModelFeatureCompletion.complete(config, JSONArray(), parent, "test", providerOverride = provider { _, child ->
                parent.steer("new question")
                assertTrue(child.isCancelled)
                response("stale")
            })
        }
        assertFalse(parent.isCancelled)
        assertTrue(parent.hasPendingSteering)
    }
}
