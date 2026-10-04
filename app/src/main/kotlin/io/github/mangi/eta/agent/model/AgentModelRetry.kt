package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException

/** Retries only a failed model request, never a run, committed history, or local tools. */
internal class AgentModelRetry(
    private val timing: ReconnectTiming = SystemReconnectTiming,
    private val waitBeforeRetry: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
) {
    data class Result(
        val round: Int,
        val response: ProviderResponse,
        val toolDiagnosticAttempt: AgentToolCallDiagnostics.Attempt? = null,
    )

    fun complete(
        initialRound: Int,
        request: ProviderRequest,
        provider: AgentProviderClient,
        controller: AgentRunController,
        onEvent: (AgentEvent) -> Unit,
        onProviderEvent: (Int, ProviderEvent) -> Unit,
        discardAttemptReasoning: () -> Unit,
    ): Result {
        var round = initialRound
        var envelopeRetries = 0
        var attemptRequest = request
        var reconnect: ModelErrorReconnect? = null
        var reconnectBinding: AgentRunController.ResourceBinding? = null
        val prefix = StringBuilder()
        fun beginReconnect(reason: AgentModelFailure): ModelErrorReconnect {
            val current = reconnect
            if (current != null) {
                current.updateReason(reason)
                return current
            }
            return ModelErrorReconnect(initialRound,
                ErrorReconnectPolicy.fromPersistedValue(request.config.errorReconnectPolicy).windowMillis,
                timing, onEvent, reason, listOf(request.config.apiKey)).also {
                reconnect = it
                // Stop emits its terminal marker immediately, including while waiting/in flight.
                reconnectBinding = controller.register { it.finish("stopped") }
                it.start()
            }
        }
        try {
            while (true) {
                controller.throwIfCancelled()
                reconnect?.check()
                onEvent(AgentEvent.RoundStarted(round, attemptRequest.messages.length()))
                var toolDeliveryPossible = false
                var callbackFailure: Throwable? = null
                var sawCompleted = false
                val textBlocks = linkedMapOf<Int, StringBuilder>()
                val textFilter = AgentContinuationTextEvents(prefix.toString())
                val deliveryGate = ProviderEventDeliveryGate()
                val repetitionGuard = ReasoningRepetitionGuard()
                val scope = controller.newTransportScope()
                reconnect?.attach(scope)
                val toolAttempt = request.toolDiagnostics?.beginAttempt(round, provider.id)
                    ?: request.toolDiagnosticAttempt
                val diagnosticRequest = if (toolAttempt == null) attemptRequest
                    else attemptRequest.copy(toolDiagnosticAttempt = toolAttempt)
                fun deliver(event: ProviderEvent) {
                    if (event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.TEXT) {
                        textBlocks.getOrPut(event.index) { StringBuilder() }.append(event.delta)
                    }
                    if (event is ProviderEvent.BlockEnd && event.kind == AssistantBlockKind.TEXT &&
                        (event.replaceContent || textBlocks[event.index].isNullOrEmpty())) {
                        textBlocks[event.index] = StringBuilder(event.content)
                    }
                    onProviderEvent(round, event)
                }
                try {
                    val response = try {
                        scope.run {
                            provider.complete(diagnosticRequest, controller) { event ->
                                deliveryGate.deliver {
                                    if (scope.isExpired || controller.isCancelled) return@deliver
                                    if (when (event) {
                                            is ProviderEvent.HostedToolStarted, is ProviderEvent.HostedToolFinished -> true
                                            is ProviderEvent.BlockStart -> event.kind == AssistantBlockKind.TOOL_CALL
                                            is ProviderEvent.BlockDelta -> event.kind == AssistantBlockKind.TOOL_CALL
                                            is ProviderEvent.BlockEnd -> event.kind == AssistantBlockKind.TOOL_CALL
                                            else -> false
                                        }) toolDeliveryPossible = true
                                    if (event is ProviderEvent.Completed) sawCompleted = true
                                    try {
                                        callbackFailure?.let { throw it }
                                        when {
                                            event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.THINKING -> {
                                                if (repetitionGuard.append(event.delta)) throw AgentModelFailure(
                                                    "MODEL_REPETITIVE_REASONING", false,
                                                    "检测到模型思考持续高度重复，已中止本次请求，且不会自动重试；此前工具结果已保留。")
                                            }
                                            event is ProviderEvent.HostedToolStarted || event is ProviderEvent.HostedToolFinished ||
                                                (event is ProviderEvent.BlockStart && event.kind == AssistantBlockKind.TOOL_CALL) ||
                                                (event is ProviderEvent.BlockDelta && event.kind != AssistantBlockKind.THINKING && event.delta.isNotBlank()) -> repetitionGuard.reset()
                                        }
                                        textFilter.map(event).forEach(::deliver)
                                    } catch (failure: Throwable) {
                                        callbackFailure = failure
                                        throw failure
                                    }
                                }
                            }
                        }
                    } finally {
                        deliveryGate.close()
                        reconnect?.detach(scope)
                    }
                    callbackFailure?.let { throw it }
                    reconnect?.check()
                    // Let AgentLoop inspect a provider response that raced with manual stop.
                    // It records sensitive tool-call ids before its own cancellation gate, so
                    // durable history can redact the unexecuted delegation without publishing
                    // generated output or running a tool.
                    if (!controller.isCancelled) textFilter.finish().forEach(::deliver)
                    if (prefix.isNotEmpty()) {
                        val tail = textFilter.normalize(response.assistantMessage.optString("content").takeUnless { it == "null" }.orEmpty())
                        response.assistantMessage.put("content", prefix.toString() + tail)
                    }
                    toolAttempt?.providerParsed(response.assistantMessage)
                    // An intentional steering/pause draft is NOT a recovered complete response.
                    reconnect?.finish(if (response.stopReason == AssistantStopReason.INTERRUPTED) "stopped" else "succeeded")
                    return Result(round, response, toolAttempt)
                } catch (failure: Exception) {
                    toolAttempt?.failed((failure as? AgentModelFailure)?.code ?: "PROVIDER_EXCEPTION")
                    callbackFailure?.let { throw it }
                    reconnect?.check()
                    controller.throwIfCancelled()
                    if (failure is CancellationException || failure is AgentRunCancelledException ||
                        failure is InterruptedException || Thread.currentThread().isInterrupted) throw failure
                    val partial = textBlocks.values.joinToString("") { it.toString() }
                    if ((controller.hasPendingImmediateSteering || controller.hasPausedInterrupt) &&
                        !toolDeliveryPossible && !sawCompleted && failure !is AgentModelFailure) {
                        reconnect?.finish("stopped")
                        return Result(round, ProviderResponse(interruptedAssistantMessage(prefix.toString() + partial, "")))
                    }
                    val classified = AgentModelFailure.transport(failure) ?: throw failure
                    if (classified.code == "CONTEXT_WINDOW_EXCEEDED") throw classified
                    val envelopeRejected = classified.code == ResponsesToolEnvelopeRecovery.CODE
                    val correctionAllowed = envelopeRejected && classified.envelopeCorrectionAllowed &&
                        provider.capabilities.endpoint == EndpointKind.RESPONSES
                    val unsafeHostedReplay = request.config.hostedWebSearchEnabled &&
                        !classified.code.startsWith("HTTP_")
                    val guarded = classified.code in setOf("MODEL_REPETITIVE_REASONING",
                        "RESPONSES_TOOL_CALL_INCOMPLETE", "RESPONSES_TOOL_ARGUMENTS_INCOMPLETE")
                    if (toolDeliveryPossible || sawCompleted || unsafeHostedReplay || guarded ||
                        (envelopeRejected && !correctionAllowed)) {
                        val protected = if (!guarded && (toolDeliveryPossible || unsafeHostedReplay)) AgentModelFailure(
                            "UNSAFE_TOOL_REPLAY", false,
                            "请求中已有工具证据或远端工具执行状态未知，未重发以免重复副作用；已保留此前正文和工具证据。", classified)
                        else classified
                        beginReconnect(protected).finish("failed")
                        throw protected
                    }
                    if (envelopeRejected) {
                        if (envelopeRetries >= ResponsesToolEnvelopeRecovery.MAX_RETRIES) throw AgentModelFailure(
                            classified.code, false,
                            "工具封装 JSON 校验连续失败，停止纠错；未执行被拒绝的工具调用。", classified)
                        envelopeRetries++
                        attemptRequest = ResponsesToolEnvelopeRecovery.corrected(request)
                        val delay = 2_000L shl (envelopeRetries - 1)
                        onEvent(AgentEvent.ModelRetryScheduled(round, envelopeRetries,
                            ResponsesToolEnvelopeRecovery.MAX_RETRIES, delay.toInt(), classified.code,
                            AgentHttpFailureDiagnostics.safe(classified.message.orEmpty(), listOf(request.config.apiKey), 600)))
                        waitBeforeRetry(controller, minOf(delay, reconnect?.remainingMs() ?: delay))
                    } else {
                        val state = beginReconnect(classified)
                        if (state.remainingMs() == 0L) throw classified
                        state.check()
                        // No retryable whitelist: safe HTTP auth/parameter errors and transports use the same policy.
                        val delay = minOf(1_000L, state.remainingMs() ?: 1_000L)
                        waitBeforeRetry(controller, delay)
                        controller.throwIfCancelled()
                        state.check()
                        textFilter.finish().forEach(::deliver)
                        prefix.append(textBlocks.values.joinToString("") { it.toString() })
                        if (prefix.isNotEmpty()) {
                            // Fresh text-only draft: no opaque hosted items, rejected tool calls, or invented results.
                            val messages = JSONArray(request.messages.toString())
                                .put(JSONObject().put("role", "assistant").put("content", prefix.toString()))
                                .put(JSONObject().put("role", "user").put("content",
                                    "Continue the interrupted answer from exactly where it stopped. Do not repeat the previous text. Do not replay any completed tools."))
                            attemptRequest = request.copy(messages = messages)
                        }
                    }
                    controller.throwIfCancelled()
                    reconnect?.check()
                    discardAttemptReasoning()
                    round++
                }
            }
        } catch (failure: Throwable) {
            val stopped = controller.isCancelled || failure is AgentRunCancelledException ||
                failure is CancellationException || failure is InterruptedException
            if (!stopped && reconnect == null && failure is AgentModelFailure &&
                failure.code != "CONTEXT_WINDOW_EXCEEDED") beginReconnect(failure)
            reconnect?.finish(if (stopped) "stopped" else "failed")
            throw failure
        } finally {
            reconnectBinding?.close()
        }
    }
}
