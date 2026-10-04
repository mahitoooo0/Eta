package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.model.AgentModelFailure

/** Called only after AgentModelRetry/Loop has thrown, never for a retry-scheduled event. */
internal object AgentParentNetworkFailure {
    private val networkCodes = setOf(
        "MODEL_TIMEOUT", "MODEL_CONNECTION_FAILED", "STREAM_INCOMPLETE", "PROVIDER_STREAM_ERROR",
        "HTTP_408", "HTTP_429", "HTTP_500", "HTTP_502", "HTTP_503", "HTTP_504", "HTTP_524", "HTTP_529",
    )

    fun isFinal(throwable: Throwable, cancelled: Boolean): Boolean {
        if (cancelled || throwable is AgentRunCancelledException) return false
        val failure = when (throwable) {
            is AgentModelExecutionException -> throwable.cause as? AgentModelFailure
            is AgentModelFailure -> throwable
            else -> null
        } ?: return false
        if (failure.code == "ERROR_RECONNECT_DEADLINE") return true
        // Replay protection changes whether a request may be resent, not child disposition.
        if (failure.code == "UNSAFE_TOOL_REPLAY") {
            val underlying = failure.cause as? AgentModelFailure ?: return false
            return isFinal(underlying, cancelled = false)
        }
        // Exhaustion wraps the retryable failure in a non-retryable one with the same code.
        // Permanent quota/auth/config errors and rejected tool envelopes are not network outages.
        return failure.code in networkCodes &&
            (failure.retryable || (failure.cause as? AgentModelFailure)?.let {
                it.code == failure.code && it.retryable
            } == true)
    }
}
