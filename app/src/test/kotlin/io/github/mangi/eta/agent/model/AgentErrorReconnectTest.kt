package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

/** No sleeps or real network: the same clock advances waits AND in-flight request work. */
class AgentErrorReconnectTest {
    private class Clock : ReconnectTiming {
        private data class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        private val tasks = mutableListOf<Task>()
        override fun nowMs() = now
        override fun schedule(delayMs: Long, action: () -> Unit): AutoCloseable {
            val task = Task(now + delayMs, action)
            tasks += task
            return AutoCloseable { task.cancelled = true }
        }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val task = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                tasks.remove(task)
                now = task.at
                task.action()
            }
            now = end
        }
    }
    private fun config(policy: ErrorReconnectPolicy) = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "never-in-events", model = "test", systemPrompt = "",
        errorReconnectPolicy = policy.persistedValue)
    private fun ok(text: String = "done") = ProviderResponse(JSONObject().put("role", "assistant")
        .put("content", text).put("finish_reason", "stop"))
    private fun provider(action: (ProviderRequest, AgentRunController, (ProviderEvent) -> Unit) -> ProviderResponse) =
        object : AgentProviderClient {
            override val id = "fake"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) =
                action(request, runController, onEvent)
        }
    private fun run(clock: Clock, policy: ErrorReconnectPolicy, provider: AgentProviderClient,
        events: MutableList<AgentEvent> = mutableListOf(), controller: AgentRunController = AgentRunController(),
        providerEvents: MutableList<ProviderEvent> = mutableListOf(), hosted: Boolean = false,
        wait: ((AgentRunController, Long) -> Unit)? = null,
    ) = AgentModelRetry(
        waitBeforeRetry = { control, ms -> (wait ?: { _: AgentRunController, delay: Long -> clock.advance(delay) })(control, ms) },
        timing = clock,
    ).complete(
        1, ProviderRequest(config(policy).copy(hostedWebSearchEnabled = hosted), JSONArray(), JSONArray()), provider,
        controller, events::add, { _, event -> providerEvents += event }, {})

    @Test fun noneDoesNotHaveLegacyThreeNetworkRetries() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        assertThrows(AgentModelFailure::class.java) {
            run(clock, ErrorReconnectPolicy.NONE, provider { _, _, _ -> calls++; throw IOException() }, events,
                wait = { _, _ -> fail("none cannot wait") })
        }
        assertEquals(1, calls)
        assertEquals(listOf("failed"), events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().map { it.status })
        assertTrue(events.none { it is AgentEvent.ModelRetryScheduled })
    }

    @Test fun finitePoliciesExpireFromFirstErrorNotEachRetryAndTickEverySecond() {
        for (policy in listOf(ErrorReconnectPolicy.WINDOW_30S, ErrorReconnectPolicy.WINDOW_1M, ErrorReconnectPolicy.WINDOW_5M)) {
            val clock = Clock()
            val events = mutableListOf<AgentEvent>()
            var calls = 0
            val failure = assertThrows(AgentModelFailure::class.java) {
                run(clock, policy, provider { _, _, _ ->
                    calls++
                    if (calls == 1) clock.advance(7_000) // Not part of reconnect budget before first error.
                    else clock.advance(1_700)
                    throw AgentModelFailure.http(401, "")
                }, events)
            }
            assertEquals("ERROR_RECONNECT_DEADLINE", failure.code)
            val changed = events.filterIsInstance<AgentEvent.ErrorReconnectChanged>()
            assertEquals(1, changed.map { it.reconnectId }.distinct().size)
            assertEquals("failed", changed.last().status)
            assertTrue(changed.zipWithNext().all { (a, b) -> a.elapsedMs <= b.elapsedMs })
            assertEquals((0 until policy.windowMillis!! step 1_000).toList(), changed.filter { it.status == "running" }.map { it.elapsedMs })
            assertTrue(clock.now < 7_000 + policy.windowMillis!! + 1_700)
            assertTrue(calls > 4) // No fixed network retry counter.
        }
    }

    @Test fun deadlineCancelsInflightTransportButNotTheRunAndRejectsLateSuccess() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        val controller = AgentRunController()
        val delivered = mutableListOf<ProviderEvent>()
        var calls = 0
        var cancelled = false
        var late: ((ProviderEvent) -> Unit)? = null
        val failure = assertThrows(AgentModelFailure::class.java) {
            run(clock, ErrorReconnectPolicy.WINDOW_30S, provider { _, control, emit ->
                if (calls++ == 0) throw IOException()
                val binding = control.register(interruptible = true) { cancelled = true }
                try {
                    late = emit
                    clock.advance(29_000)
                    assertTrue(cancelled)
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "late answer"))
                    ok("late answer") // A provider ignoring cancellation cannot win.
                } finally { binding.close() }
            }, events, controller, delivered)
        }
        assertEquals("ERROR_RECONNECT_DEADLINE", failure.code)
        assertFalse(controller.isCancelled)
        late!!(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 999)))
        assertTrue(delivered.isEmpty())
        assertEquals("failed", events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().status)
    }

    @Test fun continuousRunsPastFiveMinutesAndSucceedsOnlyAfterCompleteReturn() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        val response = run(clock, ErrorReconnectPolicy.CONTINUOUS, provider { _, _, emit ->
            if (calls++ == 0) throw AgentModelFailure.http(400, "")
            clock.advance(305_000)
            emit(ProviderEvent.Completed("stop"))
            assertTrue(events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().none { it.status == "succeeded" })
            ok()
        }, events)
        assertEquals("done", response.response.assistantMessage.getString("content"))
        assertEquals("succeeded", events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().status)
        assertEquals(306_000L, clock.now)
    }

    @Test fun stopWhileWaitingCancelsImmediatelyAndDoesNotStartAnotherRequest() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        val controller = AgentRunController()
        var calls = 0
        assertThrows(AgentRunCancelledException::class.java) {
            run(clock, ErrorReconnectPolicy.CONTINUOUS, provider { _, _, _ -> calls++; throw IOException() },
                events, controller, wait = { control, _ -> control.cancel() })
        }
        assertEquals(1, calls)
        assertEquals(listOf("running", "stopped"), events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().map { it.status })
    }

    @Test fun stopInflightClosesTransportAndDropsLateCallbacks() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        val delivered = mutableListOf<ProviderEvent>()
        var calls = 0
        var cancelled = false
        var late: ((ProviderEvent) -> Unit)? = null
        assertThrows(AgentRunCancelledException::class.java) {
            run(clock, ErrorReconnectPolicy.CONTINUOUS, provider { _, control, emit ->
                if (calls++ == 0) throw IOException()
                val binding = control.register(interruptible = true) { cancelled = true }
                try { late = emit; control.cancel(); ok() } finally { binding.close() }
            }, events, providerEvents = delivered)
        }
        assertTrue(cancelled)
        late!!(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 999)))
        assertTrue(delivered.isEmpty())
        assertEquals("stopped", events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().status)
    }

    @Test fun partialTextContinuesWithDraftAndDoesNotRepeatStreamOrFinalBody() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        val delivered = mutableListOf<ProviderEvent>()
        var calls = 0
        val result = run(clock, ErrorReconnectPolicy.WINDOW_30S, provider { request, _, emit ->
            if (calls++ == 0) {
                emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "Hello world"))
                throw IOException()
            }
            assertEquals("Hello world", request.messages.getJSONObject(0).getString("content"))
            assertFalse(request.messages.toString().contains("tool_calls"))
            emit(ProviderEvent.RequestStarted)
            emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "world"))
            emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, " again"))
            ok("world again")
        }, events, providerEvents = delivered)
        assertEquals("Hello world again", result.response.assistantMessage.getString("content"))
        assertEquals("Hello world again", delivered.filterIsInstance<ProviderEvent.BlockDelta>().joinToString("") { it.delta })
        assertEquals(listOf("running", "running", "succeeded"), events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().map { it.status })
    }

    @Test fun localToolAndHostedEvidenceNeverReplayAndUnknownHostedStateFailsClosed() {
        for (event in listOf<ProviderEvent>(ProviderEvent.HostedToolStarted("h", "remote"),
            ProviderEvent.BlockDelta(AssistantBlockKind.TOOL_CALL, 0, "{}"))) {
            var calls = 0
            val events = mutableListOf<AgentEvent>()
            val failure = assertThrows(AgentModelFailure::class.java) {
                run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { _, _, emit -> calls++; emit(event); throw IOException() }, events,
                    wait = { _, _ -> fail("must not replay tools") })
            }
            assertEquals(1, calls)
            assertEquals("UNSAFE_TOOL_REPLAY", failure.code)
            assertEquals("failed", events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().status)
        }
        var calls = 0
        assertThrows(AgentModelFailure::class.java) {
            run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { _, _, _ -> calls++; throw IOException() }, hosted = true,
                wait = { _, _ -> fail("remote side effect is unknown") })
        }
        assertEquals(1, calls)
    }

    @Test fun cancellationErrorAndContextMaintenanceAreNotNetworkRetryLoops() {
        val error = AssertionError("provider bug")
        assertSame(error, assertThrows(AssertionError::class.java) {
            run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { _, _, _ -> throw error })
        })
        assertThrows(CancellationException::class.java) {
            run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { _, _, _ -> throw CancellationException() })
        }
        val events = mutableListOf<AgentEvent>()
        val failure = assertThrows(AgentModelFailure::class.java) {
            run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { _, _, _ ->
                throw AgentModelFailure("CONTEXT_WINDOW_EXCEEDED", false, "maintain") }, events)
        }
        assertEquals("CONTEXT_WINDOW_EXCEEDED", failure.code)
        assertTrue(events.none { it is AgentEvent.ErrorReconnectChanged })
    }
}
