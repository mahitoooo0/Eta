package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.question.AgentQuestionCodec
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
import org.json.JSONObject

internal object AgentQuestionPersistence {
    const val TYPE = "question"
    fun encode(m: AgentQuestionMessageUi): String = JSONObject().apply {
        put("version", 1)
        put("request", AgentQuestionCodec.requestToJson(m.request))
        put("status", m.status.name)
        m.answer?.let { put("answer", AgentQuestionCodec.answerToJson(it)) }
        m.selectedOptionId?.let { put("selectedOptionId", it) }
        put("answerKind", m.answerKind)
        put("otherText", m.otherText)
        put("note", m.note)
    }.toString()

    fun decode(id: String, conversationId: String, content: String): AgentQuestionMessageUi? = runCatching {
        val json = JSONObject(content)
        require(json.optInt("version") == 1) { "Unknown question storage version" }
        val r = AgentQuestionCodec.requestFromJson(json.getJSONObject("request"))
        require(r.conversationId == conversationId && r.runId.isNotBlank() && r.toolCallId.isNotBlank())
        var status = AgentQuestionStatus.entries.firstOrNull { it.name == json.optString("status") }
            ?: AgentQuestionStatus.Interrupted
        val answer = json.optJSONObject("answer")?.let(AgentQuestionCodec::answerFromJson)
        if (status == AgentQuestionStatus.Answered &&
            (answer == null || !AgentQuestionCodec.validateAnswer(r, answer).accepted)) status = AgentQuestionStatus.Interrupted
        val rawKind = json.optString("answerKind", "option")
        val kind = when {
            rawKind == "other" && r.allowOther -> "other"
            rawKind == "delegate" && r.allowDelegation -> "delegate"
            else -> "option"
        }
        AgentQuestionMessageUi(id, r, status, answer.takeIf { status == AgentQuestionStatus.Answered },
            json.optString("selectedOptionId").takeIf { selected -> r.options.any { it.id == selected } },
            kind, json.optString("otherText", "").take(2000),
            if (r.allowNote) json.optString("note", "").take(2000) else "", submitting = false)
    }.getOrNull()
}
