package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.question.AgentQuestionCoordinator
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAskUserBatchBarrierTest {
    @Test fun mixedAskUserBatchRejectsEveryCallAndExecutesNone() {
        val executed = mutableListOf<String>()
        val messages = runBatch(
            calls = listOf("other" to "get_current_context", "ask" to AgentQuestionCoordinator.TOOL_NAME),
            executed = executed,
        )
        assertTrue(executed.isEmpty())
        assertPairedBarrier(messages, setOf("other", "ask"))
    }

    @Test fun multipleAskUserCallsRejectTheWholeBatch() {
        val executed = mutableListOf<String>()
        val messages = runBatch(
            calls = listOf("first" to AgentQuestionCoordinator.TOOL_NAME, "second" to AgentQuestionCoordinator.TOOL_NAME),
            executed = executed,
        )
        assertTrue(executed.isEmpty())
        assertPairedBarrier(messages, setOf("first", "second"))
    }

    @Test fun singleAskUserStillReachesTheExecutor() {
        val executed = mutableListOf<String>()
        runBatch(
            calls = listOf("only" to AgentQuestionCoordinator.TOOL_NAME),
            executed = executed,
        )
        assertEquals(listOf("only"), executed)
    }

    private fun runBatch(calls: List<Pair<String, String>>, executed: MutableList<String>): JSONArray {
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("task"))
        var rounds = 0
        val provider = object : AgentProviderClient {
            override val id = "ask-user-barrier"
            override val capabilities = ProviderCapabilities(
                EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false,
            )
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                rounds++
                if (rounds > 1) {
                    return ProviderResponse(
                        JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"),
                    )
                }
                val toolCalls = JSONArray()
                calls.forEach { (id, name) ->
                    toolCalls.put(
                        JSONObject().put("id", id).put("type", "function").put(
                            "function",
                            JSONObject().put("name", name).put("arguments", "{}"),
                        ),
                    )
                }
                return ProviderResponse(
                    JSONObject().put("role", "assistant").put("content", "")
                        .put("tool_calls", toolCalls).put("finish_reason", "tool_calls"),
                )
            }
        }
        val names = calls.map { it.second }.distinct()
        val result = AgentLoop(
            AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
                browserTools = false,
            ),
            messages,
            declaredTools(names),
            provider,
            AgentModelClient.ToolExecutor { call ->
                executed += call.id
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
            AgentRunController(),
            AgentTraceFormatter(),
            {},
        ).run()
        assertEquals("done", result.content)
        return messages
    }

    private fun assertPairedBarrier(messages: JSONArray, ids: Set<String>) {
        val tools = (0 until messages.length()).map { messages.getJSONObject(it) }
            .filter { it.optString("role") == "tool" }
        assertEquals(ids, tools.map { it.optString("tool_call_id") }.toSet())
        assertEquals(ids.size, tools.size)
        assertTrue(tools.all { it.optString("content").contains("ASK_USER_BATCH_BARRIER") })
        assertTrue(tools.all { it.optString("content").contains("\"ok\":false") })
    }

    private fun declaredTools(names: List<String>): JSONArray = JSONArray().apply {
        names.forEach { name ->
            put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", name).put(
                        "parameters",
                        JSONObject().put("type", "object").put("additionalProperties", true),
                    ),
                ),
            )
        }
    }
}
