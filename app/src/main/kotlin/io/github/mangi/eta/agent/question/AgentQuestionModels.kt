package io.github.mangi.eta.agent.question

/**
 * ask_user 的模型可见契约：候选选项、带稳定归属的提问请求、用户回答、生命周期状态与受理回执。
 *
 * 这里的字段名与语义就是跨进程契约；Runtime 按 [AgentQuestionCodec] 编码/解码，UI 只负责展示与回填，
 * 因此不能在不通知双方的情况下改名或改类型。类型保持 public，好让 UI 公开的会话消息模型直接持有它们；
 * 解析与受理仍收在 internal 的 [AgentQuestionCodec] 里。
 */
data class AgentQuestionOption(
    val id: String,
    val label: String,
    val description: String = "",
)

data class AgentQuestionRequest(
    val questionId: String,
    val conversationId: String,
    val runId: String,
    val toolCallId: String,
    val title: String,
    val question: String,
    val options: List<AgentQuestionOption>,
    val recommendedOptionId: String? = null,
    val allowOther: Boolean = true,
    val allowDelegation: Boolean = true,
    val allowNote: Boolean = true,
    val createdAtMillis: Long = 0,
)

data class AgentQuestionAnswer(
    val kind: String,
    val optionId: String? = null,
    val otherText: String = "",
    val note: String = "",
) {
    companion object {
        const val KIND_OPTION = "option"
        const val KIND_OTHER = "other"
        const val KIND_DELEGATE = "delegate"
        val KINDS: Set<String> = setOf(KIND_OPTION, KIND_OTHER, KIND_DELEGATE)
    }
}

enum class AgentQuestionStatus { Waiting, Answered, Cancelled, Interrupted }

data class AgentQuestionReceipt(
    val accepted: Boolean,
    val code: String,
    val message: String = "",
)
