package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.decodeUserMessageImages

/** 将 Runtime outbox 的结果幂等折叠回 App 会话。 */
internal object AgentPendingResultRecovery {
    data class Outcome(
        val state: AgentChatHomeUiState,
        val alreadyApplied: Boolean,
    )

    fun apply(
        state: AgentChatHomeUiState,
        runId: String,
        result: AgentRuntimeWire.RunResult,
        promptSupplement: AgentUiHandoffPayload.Supplement? = null,
        supplements: List<AgentUiHandoffPayload.Supplement>,
        generatedAtMillis: Long? = null,
    ): Outcome {
        val content = result.content.takeIf { result.ok && it.isNotBlank() }
        val history = AgentRuntimeHistoryReducer.apply(
            state = state,
            runId = runId,
            additions = listOfNotNull(
                promptSupplement?.let { supplement ->
                    AgentModelClient.buildUserHistoryMessage(
                        text = supplement.text,
                        images = emptyList(),
                    )
                }
            ) + result.transcript,
        )
        if (history.alreadyApplied) {
            val closed = AgentQuestionProjection.interruptWaiting(runId, state.messages)
            return Outcome(state.copy(messages = closed,
                isWaitingForAnswer = AgentQuestionProjection.hasWaiting(closed)), alreadyApplied = true)
        }

        val messagesWithResult = state.messages
            .filterNot { it is SystemNoticeMessageUi && it.id == interruptedNoticeId(runId) }
            .toMutableList()
            .also { messages ->
            if (!result.ok && result.error != "已停止") {
                val projected = AgentRunMessageProjector(nowElapsedRealtime = { 0L })
                    .terminalFailure(runId, result.error.orEmpty(), messages)
                messages.clear()
                messages.addAll(projected)
                return@also
            }
            if (!result.ok) {
                val stopped = AgentRunMessageProjector(nowElapsedRealtime = { 0L }).runStopped(runId, messages)
                messages.clear()
                messages.addAll(stopped)
            }
            val assistantIndex = AgentRunMessageProjector.resultTargetIndex(runId, messages, includeNotices = true)
            val resultId = AgentRunMessageProjector.resultFallbackId(runId, messages)
            val targetRound = (messages.getOrNull(assistantIndex) as? AgentMessageUi)
                ?.id
                ?.assistantRound(runId)
            val sameRoundBlocks = targetRound?.let { round ->
                messages.count { message ->
                    message is AgentMessageUi && message.id.assistantRound(runId) == round
                }
            } ?: 0
            val partial = messages.getOrNull(assistantIndex) as? AgentMessageUi
            val resultTail = content?.let {
                AgentRunMessageProjector.completedResultTail(runId, messages, assistantIndex, it)
            }
            val completedMessage: AgentChatMessageUi = when {
                content != null -> AgentMessageUi(
                    id = resultId,
                    content = if (sameRoundBlocks > 1) {
                        (messages[assistantIndex] as AgentMessageUi).content.ifBlank { resultTail.orEmpty() }
                    } else {
                        resultTail.orEmpty()
                    },
                    isStreaming = false,
                    renderMarkdown = true,
                    generatedAtMillis = (messages.getOrNull(assistantIndex) as? AgentMessageUi)?.generatedAtMillis
                        ?: generatedAtMillis?.takeIf { it > 0L },
                )
                VirtualCompletionNotice.confirmed(result) && partial != null && partial.content.isNotBlank() ->
                    partial.copy(isStreaming = false)
                result.ok -> SystemNoticeMessageUi(
                    id = resultId,
                    code = SystemNoticeCode.EmptyResult,
                )
                else -> SystemNoticeMessageUi(
                    id = resultId,
                    code = if (result.error == "已停止") SystemNoticeCode.Stopped else SystemNoticeCode.RuntimeFailed,
                    detail = result.error,
                )
            }
            if (!result.ok && partial != null && partial.content.isNotBlank()) {
                messages[assistantIndex] = partial.copy(isStreaming = false)
                messages += completedMessage.copyWithId(interruptedNoticeId(runId))
            } else if (assistantIndex >= 0) {
                messages[assistantIndex] = completedMessage.copyWithId(messages[assistantIndex].id)
            } else {
                messages += completedMessage
            }
        }
        val finalMessages = mergeSupplements(
            runId = runId,
            supplements = listOfNotNull(promptSupplement) + supplements,
            messages = AgentQuestionProjection.interruptWaiting(runId,
                VirtualCompletionNotice.append(messagesWithResult, runId, result)),
            beforeLatestAssistant = true,
        )
        return Outcome(
            state = state.copy(
                messages = finalMessages,
                history = history.state.history,
                appliedRuntimeRunIds = history.state.appliedRuntimeRunIds,
                isStreaming = false,
                isPaused = false,
                isWaitingForAnswer = AgentQuestionProjection.hasWaiting(finalMessages),
            ),
            alreadyApplied = false,
        )
    }

    /** Caller compares the final published state with its input, not with recovery's output.
     * alreadyApplied describes history only: closing question cards may still require a save.
     */
    fun stateToPublish(beforeRecovery: AgentChatHomeUiState, recovery: Outcome,
        finalMessages: List<AgentChatMessageUi>): AgentChatHomeUiState? {
        val next = recovery.state.copy(messages = finalMessages,
            isWaitingForAnswer = AgentQuestionProjection.hasWaiting(finalMessages))
        return next.takeIf { it != beforeRecovery }
    }

    private fun AgentChatMessageUi.copyWithId(id: String): AgentChatMessageUi = when (this) {
        is AgentMessageUi -> copy(id = id)
        is SystemNoticeMessageUi -> copy(id = id)
        else -> this
    }

    fun mergeSupplements(
        runId: String,
        supplements: List<AgentUiHandoffPayload.Supplement>,
        messages: List<AgentChatMessageUi>,
        beforeLatestAssistant: Boolean = false,
    ): List<AgentChatMessageUi> {
        var updated = messages
        supplements.sortedBy { it.index }.forEach { supplement ->
            val id = supplementMessageId(runId, supplement.index)
            val media = decodeUserMessageImages(supplement.imagesJson)
            val existing = updated.indexOfFirst { it.id == id }
            if (existing >= 0) {
                val previous = updated[existing] as? UserMessageUi
                if (previous != null && previous.images.isEmpty() && media.previews.isNotEmpty()) {
                    updated = updated.toMutableList().also { it[existing] = previous.copy(
                        images = media.previews, imageSources = media.sources,
                        imageIsVideo = media.videoFlags, imageDurationsMs = media.durationsMs,
                    ) }
                }
                return@forEach
            }
            val userMessage = UserMessageUi(id = id, content = supplement.text,
                images = media.previews, imageSources = media.sources,
                imageIsVideo = media.videoFlags, imageDurationsMs = media.durationsMs)
            // 实时追加必须接到当前列表末尾：steering 在本 turn 结束后才注入，
            // 用户消息应出现在正在生成的回答下面。插到流式助手前面时，
            // 跟底滚动会把补充挡在上一条用户消息下面，要等生成完才看得见。
            // 恢复路径才需要插到最终助手之前，对齐已完成的 transcript。
            if (!beforeLatestAssistant) {
                updated = updated + userMessage
                return@forEach
            }
            val assistantIndex = updated.indexOfLast {
                it is AgentMessageUi && it.isAssistantForRun(runId)
            }
            updated = if (assistantIndex >= 0) {
                updated.toMutableList().also { it.add(assistantIndex, userMessage) }
            } else {
                updated + userMessage
            }
        }
        return updated
    }

    private fun AgentChatMessageUi.isAssistantForRun(runId: String): Boolean =
        this is AgentMessageUi &&
            (id == "assistant-$runId" || id.startsWith(assistantMessagePrefix(runId)))

    private fun assistantMessagePrefix(runId: String): String = "assistant-$runId-"

    private fun String.assistantRound(runId: String): Int? =
        removePrefix(assistantMessagePrefix(runId))
            .takeIf { it != this }
            ?.substringBefore('-')
            ?.toIntOrNull()

    internal fun supplementMessageId(runId: String, index: Int): String =
        "user-$runId-supplement-$index"

    private fun interruptedNoticeId(runId: String): String = "interrupted-$runId"

}
