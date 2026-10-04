package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

/** Wire values are persisted; localized labels and diagnostic details never become answer text. */
enum class ErrorReconnectStatus(val wireValue: String) {
    Running("running"), Succeeded("succeeded"), Failed("failed"), Stopped("stopped");

    companion object {
        fun fromWireValue(value: String): ErrorReconnectStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

@Immutable
data class ErrorReconnectMessageUi(
    override val id: String,
    val runId: String,
    val reconnectId: String,
    val round: Int,
    val status: ErrorReconnectStatus,
    val elapsedMs: Long = 0L,
    val reasonCode: String = "",
    val reasonDetail: String = "",
    /** A terminal error without a reconnect attempt uses the ordinary failure label. */
    val isReconnect: Boolean = true,
) : AgentChatMessageUi

/** Locale-independent elapsed duration, without wall-clock or Int overflow. */
fun formatReconnectElapsed(elapsedMs: Long): String {
    val seconds = elapsedMs.coerceAtLeast(0L) / 1_000L
    val minutes = seconds / 60L
    val remainder = (seconds % 60L).toString().padStart(2, '0')
    return if (minutes < 60L) "$minutes:$remainder"
    else "${minutes / 60L}:${(minutes % 60L).toString().padStart(2, '0')}:$remainder"
}

/** Length-delimited identity prevents distinct runs/disconnections from sharing a lazy-list key. */
fun errorReconnectMessageId(runId: String, reconnectId: String): String =
    "error-reconnect:${runId.length}:$runId:${reconnectId.length}:$reconnectId"

internal fun ErrorReconnectMessageUi.isRetryableFailure(): Boolean =
    status == ErrorReconnectStatus.Failed || status == ErrorReconnectStatus.Stopped
