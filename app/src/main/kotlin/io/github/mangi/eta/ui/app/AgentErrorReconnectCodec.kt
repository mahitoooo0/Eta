package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.ErrorReconnectMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectStatus
import org.json.JSONObject

/** Reuses the message payload column, so old databases need no schema migration. */
internal object AgentErrorReconnectCodec {
    fun encode(message: ErrorReconnectMessageUi): String = JSONObject().apply {
        put("version", 1)
        put("runId", message.runId)
        put("reconnectId", message.reconnectId)
        put("round", message.round)
        put("status", message.status.wireValue)
        put("elapsedMs", message.elapsedMs.coerceAtLeast(0L))
        put("reasonCode", message.reasonCode)
        put("reasonDetail", message.reasonDetail)
        put("isReconnect", message.isReconnect)
    }.toString()

    fun decode(id: String, payload: String, restoreTerminal: Boolean = true): ErrorReconnectMessageUi? = runCatching {
        val json = JSONObject(payload)
        val status = ErrorReconnectStatus.fromWireValue(json.optString("status")) ?: ErrorReconnectStatus.Failed
        ErrorReconnectMessageUi(
            id = id,
            runId = json.optString("runId"),
            reconnectId = json.optString("reconnectId"),
            round = json.optInt("round"),
            // Stored UI alone cannot prove a retry is still alive after restart. Active
            // runtime event replay replaces this snapshot, including the same identity.
            status = if (restoreTerminal && status == ErrorReconnectStatus.Running) ErrorReconnectStatus.Stopped else status,
            elapsedMs = json.optLong("elapsedMs").coerceAtLeast(0L),
            reasonCode = json.optString("reasonCode"),
            reasonDetail = json.optString("reasonDetail"),
            isReconnect = json.optBoolean("isReconnect", true),
        )
    }.getOrNull()
}
