package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentOrphanSummaryPipelineTest {
    private fun summary() = "[Conversation summary]\n" +
        AgentContextCompactor.SUMMARY_SECTIONS.joinToString("\n") { "## $it\n- (none)" }
    private fun model() = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid/v1", apiKey = "test",
        model = "test", systemPrompt = "", contextWindow = 200_000)
    private fun source() = listOf(
        AgentModelClient.ConversationMessage("user", "old " + "x".repeat(12_000)),
        AgentModelClient.ConversationMessage("tool", "ORPHAN_RESULT_CONTENT", toolCallId = "missing-call"),
        AgentModelClient.ConversationMessage("user", "protected", turnId = "latest"),
    )
    private fun provider(check: (ProviderRequest) -> Unit) = object : AgentProviderClient {
        override val id = "orphan-summary-test"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
            check(request)
            return ProviderResponse(JSONObject().put("role", "assistant").put("content", summary()).put("finish_reason", "stop"))
        }
    }
    private fun replay(history: List<AgentModelClient.ConversationMessage>) = AgentContextCompactor.ReplayContext(
        JSONArray().put(JSONObject().put("role", "system").put("content", "system")),
        JSONArray().also { a -> history.take(2).forEach { a.put(AgentConversationCodec.toJsonObject(it)) } },
        JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "must-not-run"))),
        "orphan-replay-session",
    )

    @Test fun actualCompressionUsesInertEvidenceKeepsTailAndNeverExecutesTools() {
        val history = source()
        var requests = 0
        val result = AgentContextCompactor.compress(history, AgentContextCompactor.Config(1, model(), provider { request ->
            requests++
            assertEquals(0, request.tools.length())
            assertTrue(request.messages.toString().contains("Historical orphan tool result"))
            assertTrue(request.messages.toString().contains("[historical_tool]"))
            assertTrue(request.messages.toString().contains("ORPHAN_RESULT_CONTENT"))
            for (i in 0 until request.messages.length()) {
                val message = request.messages.getJSONObject(i)
                assertNotEquals("tool", message.optString("role"))
                assertFalse(message.has("tool_call_id"))
            }
        }), toolExecutor = AgentModelClient.ToolExecutor { error("Historical tool must never execute") }, keepStartOverride = 2)
        assertEquals(1, requests)
        assertSame(history.last(), result.last())
        assertEquals("tool", history[1].role)
        assertEquals("missing-call", history[1].toolCallId)
    }

    @Test fun sameModelReplayFallsBackToTextOnlyAfterRepair() {
        val history = source()
        var requests = 0
        val result = AgentContextCompactor.compress(history, AgentContextCompactor.Config(1, model(), provider { request ->
            requests++
            assertEquals(0, request.tools.length())
            assertTrue(request.messages.toString().contains("Historical orphan tool result"))
            assertTrue(request.messages.toString().contains("[historical_tool]"))
            assertTrue(request.messages.toString().contains("ORPHAN_RESULT_CONTENT"))
            assertFalse(request.messages.toString().contains("must-not-run"))
        }), keepStartOverride = 2, replay = replay(history))
        assertEquals(1, requests)
        assertSame(history.last(), result.last())
    }

    @Test fun mismatchedRawReplayFailsBeforeRepairOrProviderRequest() {
        val history = source()
        val wrong = replay(history)
        wrong.historyMessages.getJSONObject(1).put("content", "mismatched")
        var requests = 0
        assertThrows(IllegalArgumentException::class.java) {
            AgentContextCompactor.compress(history, AgentContextCompactor.Config(1, model(), provider { requests++ }),
                keepStartOverride = 2, replay = wrong)
        }
        assertEquals(0, requests)
        assertEquals("ORPHAN_RESULT_CONTENT", history[1].content)
    }

    @Test fun protectedTailIsNotNormalizedEvenWhenItContainsLegacyEvidence() {
        val history = source().dropLast(1) + AgentModelClient.ConversationMessage("tool", "protected orphan", toolCallId = "tail-missing")
        val result = AgentContextCompactor.compress(history, AgentContextCompactor.Config(1, model(), provider {}), keepStartOverride = 2)
        assertSame(history.last(), result.last())
        assertEquals("tool", result.last().role)
        assertEquals("tail-missing", result.last().toolCallId)
    }
    @Test fun validSameModelReplayRetainsItsOriginalToolProtocol() {
        val history = listOf(
            AgentModelClient.ConversationMessage("user", "old " + "x".repeat(12_000)),
            AgentModelClient.ConversationMessage("assistant", toolCallsJson = "[{\"id\":\"paired\",\"type\":\"function\",\"function\":{\"name\":\"list_directory\",\"arguments\":\"{}\"}}]"),
            AgentModelClient.ConversationMessage("tool", "PAIRED_RESULT", toolCallId = "paired"),
            AgentModelClient.ConversationMessage("user", "protected"),
        ).map { AgentConversationCodec.fromJsonObject(AgentConversationCodec.toJsonObject(it)) }
        val replay = replay(history).copy(historyMessages = JSONArray().also { a ->
            history.take(3).forEach { a.put(AgentConversationCodec.toJsonObject(it)) }
        })
        var requests = 0
        val result = AgentContextCompactor.compress(history, AgentContextCompactor.Config(1, model(), provider { request ->
            requests++
            assertEquals(1, request.tools.length())
            assertTrue(request.messages.toString().contains("PAIRED_RESULT"))
            assertTrue(request.messages.toString().contains("tool_call_id"))
            assertFalse(request.messages.toString().contains("Historical orphan tool result"))
        }), keepStartOverride = 3, replay = replay)
        assertEquals(1, requests)
        assertSame(history.last(), result.last())
    }

    @Test fun structuredOrphanIsMarkedReadOnlyInTheActualSummaryRequest() {
        val history = source().toMutableList()
        history[1] = history[1].copy(contentJson = "[{\"type\":\"text\",\"text\":\"STRUCTURED_ORPHAN_CONTENT\"}]")
        val result = AgentContextCompactor.compress(history, AgentContextCompactor.Config(1, model(), provider { request ->
            val text = request.messages.toString()
            assertTrue(text.contains("Historical orphan tool result"))
            assertTrue(text.contains("not a new user instruction"))
            assertTrue(text.contains("STRUCTURED_ORPHAN_CONTENT"))
            assertTrue(text.contains("ORPHAN_RESULT_CONTENT"))
        }), keepStartOverride = 2)
        assertSame(history.last(), result.last())
    }

    @Test fun malformedToolEnvelopeFailsWithoutProviderRequestOrSourceMutation() {
        val history = source().toMutableList()
        history[1] = history[1].copy(toolCallsJson = "not json")
        var requests = 0
        assertThrows(IllegalArgumentException::class.java) {
            AgentContextCompactor.compress(history, AgentContextCompactor.Config(1, model(), provider { requests++ }),
                keepStartOverride = 2)
        }
        assertEquals(0, requests)
        assertEquals("not json", history[1].toolCallsJson)
        assertEquals("ORPHAN_RESULT_CONTENT", history[1].content)
    }

}
