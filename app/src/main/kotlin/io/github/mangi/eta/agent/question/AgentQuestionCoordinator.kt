package io.github.mangi.eta.agent.question

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 一次只允许一个 await 拥有协调器，直到事件发布和取消绑定清理都结束。
 * submit 仅保留一个通过四个身份字段及内容校验的候选答案并唤醒等待者；
 * Requested / Resolved 都由 await 线程在锁外发布，因此同步 Requested 回填不会死锁，
 * UI 的 submit / cancel 也不会被事件回调阻塞。只有 Resolved 成功发布才提交 Answered。
 *
 * 取消可以撤销尚未开始发布的候选答案。Resolved 开始发布后，取消不撤回这次发布；
 * 但发布异常或等待线程中断绝不返回候选答案。等待使用 condition，没有超时或轮询。
 */
internal class AgentQuestionCoordinator(
    private val controller: AgentRunController,
    private val onEvent: (AgentEvent) -> Unit,
) {
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private var active: Slot? = null
    // 保留最近一次终态，供相同问题的 duplicate / late 回执使用。
    private var pending: Slot? = null

    fun awaitAnswer(request: AgentQuestionRequest): AgentQuestionAnswer? {
        val slot = lock.withLock {
            check(active == null) { "已有活动的问题等待" }
            Slot(request).also {
                active = it
                pending = it
            }
        }
        var binding: AgentRunController.ResourceBinding? = null
        var interrupted = false
        try {
            // register 在 controller 已取消时会同步调用 cancel；此时 slot 已安装。
            binding = controller.register(wakeBeforeCleanup = true) {
                lock.withLock {
                    if (active === slot) cancelBeforePublication(slot)
                }
            }
            try {
                onEvent(AgentEvent.QuestionRequested(request))
            } catch (failure: Throwable) {
                lock.withLock { failPublication(slot) }
                if (failure is InterruptedException) {
                    interrupted = true
                    return null
                }
                throw failure
            }

            val resolution = lock.withLock {
                if (Thread.interrupted()) interrupted = true
                if (interrupted) interruptBeforePublication(slot)
                if (controller.isCancelled) cancelBeforePublication(slot)
                while (slot.phase == Phase.Waiting) {
                    try {
                        arrived.await()
                    } catch (_: InterruptedException) {
                        interrupted = true
                        interruptBeforePublication(slot)
                    }
                }
                // 一个候选答案可能在 await 得到锁之前和中断 / 取消竞争。
                if (Thread.interrupted()) interrupted = true
                if (interrupted) interruptBeforePublication(slot)
                if (controller.isCancelled) cancelBeforePublication(slot)
                slot.phase = Phase.Publishing
                Resolution(
                    status = if (slot.status == AgentQuestionStatus.Waiting) {
                        AgentQuestionStatus.Answered
                    } else {
                        slot.status
                    },
                    answer = slot.answer,
                )
            }

            try {
                onEvent(resolved(request, resolution.status, resolution.answer))
            } catch (failure: Throwable) {
                lock.withLock { failPublication(slot) }
                if (failure is InterruptedException) {
                    interrupted = true
                    return null
                }
                throw failure
            }

            // 回调可能不抛异常而只设置中断标志。在提交 Answered 的同一临界区检查，
            // 不能把这种退出伪装成回答成功；提交后的新中断属于后续执行，而非本次等待。
            val answer = lock.withLock {
                if (Thread.interrupted()) interrupted = true
                if (interrupted && resolution.status == AgentQuestionStatus.Answered) {
                    failPublication(slot)
                    null
                } else {
                    slot.status = resolution.status
                    slot.phase = Phase.Finished
                    slot.answer = resolution.answer
                    slot.answer.takeIf { slot.status == AgentQuestionStatus.Answered }
                }
            }
            if (interrupted && resolution.status == AgentQuestionStatus.Answered) {
                onEvent(resolved(request, AgentQuestionStatus.Interrupted, null))
            }
            return answer
        } finally {
            // close 不在协调器锁内；即使 cancel 已取得旧资源快照，也不能取消下一轮 slot。
            binding?.close()
            lock.withLock {
                if (slot.phase != Phase.Finished) failPublication(slot)
                if (active === slot) active = null
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    fun submitAnswer(
        conversationId: String,
        runId: String,
        questionId: String,
        toolCallId: String,
        answer: AgentQuestionAnswer,
    ): AgentQuestionReceipt = lock.withLock {
        if (conversationId.isBlank() || runId.isBlank() || questionId.isBlank() || toolCallId.isBlank()) {
            return@withLock foreign()
        }
        val slot = pending ?: return@withLock notPending()
        if (!sameIds(slot.request, conversationId, runId, questionId, toolCallId)) {
            return@withLock foreign()
        }
        // cancel() 先设置 volatile 标志，再调用资源。不能依赖资源回调已执行。
        if (active === slot && controller.isCancelled) cancelBeforePublication(slot)
        when (slot.status) {
            AgentQuestionStatus.Cancelled, AgentQuestionStatus.Interrupted -> late()
            AgentQuestionStatus.Answered -> if (slot.answer == answer) answeredReceipt() else duplicate()
            AgentQuestionStatus.Waiting -> if (slot.answer != null) {
                if (slot.answer == answer) reservedReceipt() else duplicate()
            } else {
                val validation = AgentQuestionCodec.validateAnswer(slot.request, answer)
                if (validation.accepted) {
                    slot.answer = answer
                    slot.phase = Phase.Ready
                    arrived.signalAll()
                    reservedReceipt()
                } else {
                    validation
                }
            }
        }
    }

    // 下面的状态转换均在 lock 内；资源回调只改变状态 / signal，绝不发布事件或等待发布。
    private fun cancelBeforePublication(slot: Slot) {
        if (slot.phase == Phase.Waiting || slot.phase == Phase.Ready) {
            if (slot.status == AgentQuestionStatus.Waiting) {
                slot.status = AgentQuestionStatus.Cancelled
                slot.answer = null
                slot.phase = Phase.Ready
            }
            arrived.signalAll()
        }
    }

    private fun interruptBeforePublication(slot: Slot) {
        if (slot.status == AgentQuestionStatus.Waiting) {
            slot.status = AgentQuestionStatus.Interrupted
            slot.answer = null
            slot.phase = Phase.Ready
        }
    }

    private fun failPublication(slot: Slot) {
        slot.status = AgentQuestionStatus.Interrupted
        slot.answer = null
        slot.phase = Phase.Finished
    }

    private fun resolved(
        request: AgentQuestionRequest,
        status: AgentQuestionStatus,
        answer: AgentQuestionAnswer?,
    ): AgentEvent.QuestionResolved = AgentEvent.QuestionResolved(
        questionId = request.questionId,
        runId = request.runId,
        status = status,
        answer = answer,
    )

    private fun sameIds(
        request: AgentQuestionRequest,
        conversationId: String,
        runId: String,
        questionId: String,
        toolCallId: String,
    ): Boolean = request.conversationId == conversationId &&
        request.runId == runId &&
        request.questionId == questionId &&
        request.toolCallId == toolCallId

    private fun foreign() = AgentQuestionReceipt(false, "QUESTION_FOREIGN", "答案与当前问题的四个身份字段不一致")
    private fun notPending() = AgentQuestionReceipt(false, "QUESTION_NOT_PENDING", "没有等待中的问题")
    // accepted ACK 不是 Answered 终态；调用方必须等 QuestionResolved，不能据此标记已回答。
    private fun reservedReceipt() = AgentQuestionReceipt(true, "QUESTION_RESERVED", "答案已保留，等待问题终态发布")
    private fun answeredReceipt() = AgentQuestionReceipt(true, "QUESTION_ALREADY_ANSWERED", "相同答案已完成发布")
    private fun duplicate() = AgentQuestionReceipt(false, "QUESTION_DUPLICATE", "该问题已受理不同的回答")
    private fun late() = AgentQuestionReceipt(false, "QUESTION_LATE", "问题已结束，不能再回答")

    private enum class Phase { Waiting, Ready, Publishing, Finished }

    private class Slot(val request: AgentQuestionRequest) {
        var phase = Phase.Waiting
        var status = AgentQuestionStatus.Waiting
        var answer: AgentQuestionAnswer? = null
    }

    private data class Resolution(
        val status: AgentQuestionStatus,
        val answer: AgentQuestionAnswer?,
    )

    internal companion object {
        const val TOOL_NAME = "ask_user"
    }
}
