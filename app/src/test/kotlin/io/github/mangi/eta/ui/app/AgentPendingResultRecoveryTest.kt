package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.question.AgentQuestionAnswer
import io.github.mangi.eta.agent.question.AgentQuestionOption
import io.github.mangi.eta.agent.question.AgentQuestionRequest
import io.github.mangi.eta.agent.question.AgentQuestionSnapshot
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPendingResultRecoveryTest {
    private fun question(run: String = "run-question", q: String = "q") = AgentQuestionMessageUi(
        id = "question-$run-$q",
        request = AgentQuestionRequest(q, "chat", run, "call-$q", "Decide", "Choose",
            listOf(AgentQuestionOption("a", "A"), AgentQuestionOption("b", "B"))),
        selectedOptionId = "a", note = "draft", submitting = true,
    )

    @Test fun alreadyAppliedQuestionClosureStillPublishesAndRequiresSave() {
        val pending = question()
        val history = listOf(AgentModelClient.ConversationMessage("assistant", "already committed"))
        val before = AgentChatUiState(messages = listOf(pending), input = "", isStreaming = false,
            thinkingEnabled = false, history = history, appliedRuntimeRunIds = listOf("run-question"),
            isWaitingForAnswer = true)
        val outcome = AgentPendingResultRecovery.apply(before, "run-question",
            AgentRuntimeWire.RunResult("run-question", true, "not appended twice"), supplements = emptyList())
        assertTrue(outcome.alreadyApplied)
        // This is the production caller helper. Identical ordering to recovery's output must
        // not suppress a changed card just because its history run was already applied.
        val publish = AgentPendingResultRecovery.stateToPublish(before, outcome, outcome.state.messages)
        org.junit.Assert.assertNotNull(publish)
        val next = publish!!
        assertEquals(history, next.history)
        assertEquals(listOf("run-question"), next.appliedRuntimeRunIds)
        val closed = next.messages.single() as AgentQuestionMessageUi
        assertEquals(AgentQuestionStatus.Interrupted, closed.status)
        assertEquals("draft", closed.note)
        assertFalse(closed.submitting)
        assertFalse(next.isWaitingForAnswer)
        val replay = AgentPendingResultRecovery.apply(next, "run-question",
            AgentRuntimeWire.RunResult("run-question", true, "ignored"), supplements = emptyList())
        org.junit.Assert.assertNull(AgentPendingResultRecovery.stateToPublish(next, replay, replay.state.messages))
    }

    @Test fun alreadyAppliedClosureIsCorrectedByAuthoritativeReadBeforeFinalPublication() {
        val pending = question()
        val before = AgentChatUiState(messages = listOf(pending), input = "", isStreaming = false,
            thinkingEnabled = false, appliedRuntimeRunIds = listOf("run-question"), isWaitingForAnswer = true)
        val outcome = AgentPendingResultRecovery.apply(before, "run-question",
            AgentRuntimeWire.RunResult("run-question", true, "ignored"), supplements = emptyList())
        val owner = AgentQuestionProjection.recoveryOwners("chat", outcome.state.messages).single()
        val finalMessages = AgentQuestionProjection.reconcileMessages(outcome.state.messages, owner,
            AgentQuestionSnapshot("chat", "run-question", "q", "call-q", AgentQuestionStatus.Answered,
                AgentQuestionAnswer("option", "a", note = "consumed")))
        val next = AgentPendingResultRecovery.stateToPublish(before, outcome, finalMessages)!!
        assertTrue(outcome.alreadyApplied)
        assertEquals(AgentQuestionStatus.Answered, (next.messages.single() as AgentQuestionMessageUi).status)
        assertEquals("consumed", (next.messages.single() as AgentQuestionMessageUi).answer!!.note)
        assertFalse(next.isWaitingForAnswer)
    }

    @Test fun nonAppliedRecoveryAndFinalPublicationDeriveWaitingFromActualFinalMessages() {
        val before = AgentChatUiState(messages = listOf(question()), input = "", isStreaming = true,
            thinkingEnabled = false, isWaitingForAnswer = true)
        val outcome = AgentPendingResultRecovery.apply(before, "run-question",
            AgentRuntimeWire.RunResult("run-question", true, "answer"),
            supplements = listOf(AgentUiHandoffPayload.Supplement(1, "accepted supplement", 1L)))
        assertFalse(outcome.alreadyApplied)
        assertEquals(AgentQuestionStatus.Interrupted,
            outcome.state.messages.filterIsInstance<AgentQuestionMessageUi>().single().status)
        assertTrue(outcome.state.messages.any { it is UserMessageUi })
        assertFalse(outcome.state.isWaitingForAnswer)
        assertEquals(AgentQuestionProjection.hasWaiting(outcome.state.messages), outcome.state.isWaitingForAnswer)

        val waiting = question(run = "still-active", q = "other")
        val next = AgentPendingResultRecovery.stateToPublish(before, outcome, outcome.state.messages + waiting)!!
        assertTrue(next.isWaitingForAnswer)
        val finalAnswered = AgentQuestionProjection.reconcileMessages(next.messages, waiting.request,
            AgentQuestionSnapshot("chat", "still-active", "other", "call-other", AgentQuestionStatus.Answered,
                AgentQuestionAnswer("option", "a")))
        val corrected = AgentPendingResultRecovery.stateToPublish(next, outcome, finalAnswered)!!
        assertFalse(corrected.isWaitingForAnswer)
        assertEquals(AgentQuestionProjection.hasWaiting(corrected.messages), corrected.isWaitingForAnswer)
    }

    @Test fun closingOneRunDoesNotDropAnotherRunsQuestionWait() {
        for (applied in listOf(false, true)) {
            val pending = question()
            val active = question(run = "active", q = "active")
            val before = AgentChatUiState(messages = listOf(pending, active), input = "", isStreaming = false,
                thinkingEnabled = false, appliedRuntimeRunIds = if (applied) listOf("run-question") else emptyList(),
                isWaitingForAnswer = false)
            val outcome = AgentPendingResultRecovery.apply(before, "run-question",
                AgentRuntimeWire.RunResult("run-question", false, "", "stopped"), supplements = emptyList())
            val next = AgentPendingResultRecovery.stateToPublish(before, outcome, outcome.state.messages)!!
            val cards = next.messages.filterIsInstance<AgentQuestionMessageUi>()
            assertEquals(AgentQuestionStatus.Interrupted, cards.first().status)
            assertEquals(active, cards.last())
            assertTrue(next.isWaitingForAnswer)
            assertEquals(AgentQuestionProjection.hasWaiting(next.messages), next.isWaitingForAnswer)
        }
    }

    @Test fun recoveryKeepsCompletionTimeInsteadOfUsingRecoveryTime() {
        val state = AgentChatUiState(messages = listOf(
            UserMessageUi("user-run-time", "question"),
            AgentMessageUi("assistant-run-time-1", "answer", generatedAtMillis = 1000L)),
            input = "", isStreaming = false, thinkingEnabled = false)
        val result = AgentRuntimeWire.RunResult("run-time", true, "answer")
        val recovered = AgentPendingResultRecovery.apply(state, "run-time", result,
            supplements = emptyList(), generatedAtMillis = 2000L)
        assertEquals(1000L, recovered.state.messages.filterIsInstance<AgentMessageUi>().single().generatedAtMillis)
        val noTimestamp = state.copy(messages = listOf(UserMessageUi("user-run-time", "question")))
        val stamped = AgentPendingResultRecovery.apply(noTimestamp, "run-time", result,
            supplements = emptyList(), generatedAtMillis = 2000L)
        assertEquals(2000L, stamped.state.messages.filterIsInstance<AgentMessageUi>().single().generatedAtMillis)
        val legacy = AgentPendingResultRecovery.apply(noTimestamp, "run-time", result, supplements = emptyList())
        assertEquals(null, legacy.state.messages.filterIsInstance<AgentMessageUi>().single().generatedAtMillis)
    }

    @Test fun stoppedRecoveryKeepsPartialAndFullTranscriptAndIsIdempotent() {
        val initial = AgentModelClient.ConversationMessage("user", "task", turnId = "run-stop")
        val additions = listOf(
            AgentModelClient.ConversationMessage("assistant", "completed work", turnId = "run-stop"),
            AgentModelClient.ConversationMessage("user", "accepted supplement", turnId = "run-stop"),
        )
        val state = AgentChatUiState(messages = listOf(
            UserMessageUi("user-run-stop", "task"),
            AgentMessageUi("assistant-run-stop-1", "completed work", isStreaming = true),
        ), input = "", isStreaming = true, isPaused = true, thinkingEnabled = false, history = listOf(initial))
        val result = AgentRuntimeWire.RunResult("run-stop", false, "", "已停止", transcript = additions)
        val first = AgentPendingResultRecovery.apply(state, "run-stop", result, supplements = emptyList())
        assertEquals(listOf(initial) + additions, first.state.history)
        assertEquals("completed work", first.state.messages.filterIsInstance<AgentMessageUi>().single().content)
        assertEquals(SystemNoticeCode.Stopped, first.state.messages.filterIsInstance<SystemNoticeMessageUi>().single().code)
        org.junit.Assert.assertFalse(first.state.isPaused)
        org.junit.Assert.assertFalse(first.state.isStreaming)
        val repeated = AgentPendingResultRecovery.apply(first.state, "run-stop", result, supplements = emptyList())
        org.junit.Assert.assertTrue(repeated.alreadyApplied)
        assertEquals(first.state, repeated.state)
    }

    @Test
    fun recoveryDoesNotReplaceFailedAttemptOrRetryNotice() {
        val partial = AgentMessageUi(id = "assistant-retry-run-1-0", content = "半截回答")
        val notice = SystemNoticeMessageUi(
            id = "assistant-retry-run-retry-1",
            code = SystemNoticeCode.ModelRetry,
            detail = "正在重试",
        )
        val state = AgentChatUiState(
            messages = listOf(partial, notice), input = "", isStreaming = true, thinkingEnabled = false,
        )
        for (ok in listOf(true, false)) {
            val recovered = AgentPendingResultRecovery.apply(
                state = state,
                runId = "retry-run",
                result = AgentRuntimeWire.RunResult(
                    runId = "retry-run", ok = ok, content = if (ok) "最终回答" else "",
                    error = if (ok) null else "重试耗尽",
                ),
                supplements = emptyList(),
            )
            assertEquals(listOf(partial, notice), recovered.state.messages.take(2))
            if (ok) {
                assertEquals("assistant-retry-run-2-result", recovered.state.messages.last().id)
            } else {
                assertEquals(
                    io.github.mangi.eta.ui.model.errorReconnectMessageId("retry-run", "terminal-failure"),
                    recovered.state.messages.last().id,
                )
                assertEquals(
                    io.github.mangi.eta.ui.model.ErrorReconnectStatus.Failed,
                    (recovered.state.messages.last() as io.github.mangi.eta.ui.model.ErrorReconnectMessageUi).status,
                )
            }
            assertEquals(3, recovered.state.messages.size)
        }
    }

    @Test
    fun recoveryFinalizesLatestRoundAndAppendsTranscriptExactlyOnce() {
        val transcript = listOf(
            AgentModelClient.ConversationMessage(role = "assistant", content = "最终结果")
        )
        val state = AgentChatUiState(
            messages = listOf(
                AgentMessageUi(
                    id = "assistant-run-1-1",
                    content = "中间结果",
                    isStreaming = false,
                    renderMarkdown = false,
                ),
                AgentMessageUi(
                    id = "assistant-run-1-2",
                    content = "部分",
                    isStreaming = true,
                    renderMarkdown = false,
                ),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )
        val supplement = AgentUiHandoffPayload.Supplement(
            index = 1,
            text = "补充条件",
            createdAt = 1L,
        )
        val result = AgentRuntimeWire.RunResult(
            runId = "run-1",
            ok = true,
            content = "最终结果",
            transcript = transcript,
        )

        val recovered = AgentPendingResultRecovery.apply(
            state = state,
            runId = "run-1",
            result = result,
            supplements = listOf(supplement),
        )

        assertFalse(recovered.alreadyApplied)
        assertEquals(
            listOf(
                "assistant-run-1-1",
                "user-run-1-supplement-1",
                "assistant-run-1-2",
            ),
            recovered.state.messages.map { it.id },
        )
        val latest = recovered.state.messages.last() as AgentMessageUi
        assertEquals("最终结果", latest.content)
        assertFalse(latest.isStreaming)
        assertTrue(latest.renderMarkdown)
        assertEquals(transcript, recovered.state.history)
        assertFalse(recovered.state.isStreaming)

        val replay = AgentPendingResultRecovery.apply(
            state = recovered.state,
            runId = "run-1",
            result = result,
            supplements = listOf(supplement),
        )
        assertTrue(replay.alreadyApplied)
        assertEquals(recovered.state, replay.state)
    }

    @Test fun failedOutboxRetainsPartialTextToolAndDisconnectedMarkerIdentity() {
        val partial = AgentMessageUi("assistant-run-failed-1-0", "partial response", isStreaming = true)
        val tool = ToolActivityMessageUi("run-failed-tool-1-call", "read_file", ToolActivityStatusUi.Success, "{}")
        val marker = io.github.mangi.eta.ui.model.ErrorReconnectMessageUi(
            id = io.github.mangi.eta.ui.model.errorReconnectMessageId("run-failed", "disconnect"),
            runId = "run-failed", reconnectId = "disconnect", round = 1,
            status = io.github.mangi.eta.ui.model.ErrorReconnectStatus.Running,
            reasonCode = "HTTP_502", reasonDetail = "provider diagnostic",
        )
        val state = AgentChatUiState(messages = listOf(partial, tool, marker), input = "",
            isStreaming = true, thinkingEnabled = false)
        val result = AgentRuntimeWire.RunResult("run-failed", ok = false, content = "", error = "terminal error")
        val recovered = AgentPendingResultRecovery.apply(state, "run-failed", result, supplements = emptyList())
        assertEquals(listOf(partial.id, tool.id, marker.id), recovered.state.messages.map { it.id })
        assertEquals("partial response", (recovered.state.messages.first() as AgentMessageUi).content)
        assertEquals(tool, recovered.state.messages[1])
        assertEquals(marker.copy(status = io.github.mangi.eta.ui.model.ErrorReconnectStatus.Failed), recovered.state.messages.last())
        val repeated = AgentPendingResultRecovery.apply(recovered.state, "run-failed", result, supplements = emptyList())
        assertTrue(repeated.alreadyApplied)
        assertEquals(recovered.state, repeated.state)
    }

    @Test
    fun recoveryCreatesAssistantWhenStreamingPlaceholderWasNeverPersisted() {
        val state = AgentChatUiState(
            messages = emptyList(),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )

        val recovered = AgentPendingResultRecovery.apply(
            state = state,
            runId = "run-2",
            result = AgentRuntimeWire.RunResult(
                runId = "run-2",
                ok = false,
                content = "",
                error = "失败原因",
            ),
            supplements = emptyList(),
        )

        val message = recovered.state.messages.single() as io.github.mangi.eta.ui.model.ErrorReconnectMessageUi
        assertEquals(io.github.mangi.eta.ui.model.errorReconnectMessageId("run-2", "terminal-failure"), message.id)
        assertEquals(io.github.mangi.eta.ui.model.ErrorReconnectStatus.Failed, message.status)
        assertEquals("失败原因", message.reasonDetail)
        org.junit.Assert.assertFalse(message.isReconnect)
    }

    @Test
    fun recoveryDoesNotMergeEarlierTextBlockIntoSameRoundFinalBlock() {
        val state = AgentChatUiState(
            messages = listOf(
                AgentMessageUi(
                    id = "assistant-run-blocks-1-1",
                    content = "我先搜索。",
                    isStreaming = false,
                    renderMarkdown = true,
                ),
                AgentMessageUi(
                    id = "assistant-run-blocks-1-3",
                    content = "这是最终答案。",
                    isStreaming = false,
                    renderMarkdown = true,
                ),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )

        val recovered = AgentPendingResultRecovery.apply(
            state = state,
            runId = "run-blocks",
            result = AgentRuntimeWire.RunResult(
                runId = "run-blocks",
                ok = true,
                content = "我先搜索。这是最终答案。",
                transcript = listOf(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "我先搜索。这是最终答案。",
                    ),
                ),
            ),
            supplements = emptyList(),
        )

        assertEquals(
            listOf("我先搜索。", "这是最终答案。"),
            recovered.state.messages.filterIsInstance<AgentMessageUi>().map { it.content },
        )
    }

    @Test
    fun recoveryUsesSemanticNoticeForSuccessfulRunWithoutText() {
        val recovered = AgentPendingResultRecovery.apply(
            state = AgentChatUiState(
                messages = emptyList(),
                input = "",
                isStreaming = true,
                thinkingEnabled = false,
            ),
            runId = "run-empty",
            result = AgentRuntimeWire.RunResult(
                runId = "run-empty",
                ok = true,
                content = "",
            ),
            supplements = emptyList(),
        )

        val message = recovered.state.messages.single() as SystemNoticeMessageUi
        assertEquals(SystemNoticeCode.EmptyResult, message.code)
        assertEquals(null, message.detail)
    }

    @Test
    fun completedOutboxResultKeepsToolTraceAndRemovesInterruptedNotice() {
        val tool = ToolActivityMessageUi(
            id = "run-tool-tool-1-call-1",
            toolName = "run_command",
            status = ToolActivityStatusUi.Success,
            argumentsSummary = "执行命令 · Android · root",
            command = "uptime",
            resultSummary = "完成",
        )
        val recovered = AgentPendingResultRecovery.apply(
            state = AgentChatUiState(
                messages = listOf(
                    tool,
                    SystemNoticeMessageUi(
                        id = "interrupted-run-tool",
                        code = SystemNoticeCode.Interrupted,
                    ),
                ),
                input = "",
                isStreaming = false,
                thinkingEnabled = false,
            ),
            runId = "run-tool",
            result = AgentRuntimeWire.RunResult(
                runId = "run-tool",
                ok = true,
                content = "最终结果",
                transcript = listOf(
                    AgentModelClient.ConversationMessage(role = "assistant", content = "最终结果")
                ),
            ),
            supplements = emptyList(),
        )

        assertFalse(recovered.alreadyApplied)
        assertEquals(tool, recovered.state.messages.first())
        assertTrue(recovered.state.messages.none { it.id == "interrupted-run-tool" })
        assertEquals("最终结果", (recovered.state.messages.last() as AgentMessageUi).content)
    }

    @Test
    fun appliedMarkerPreventsOldOutboxReplayAfterLaterTurns() {
        val state = AgentChatUiState(
            messages = listOf(
                AgentMessageUi(id = "assistant-run-1-1", content = "第一轮"),
                AgentMessageUi(id = "assistant-run-2-1", content = "第二轮"),
            ),
            history = listOf(
                AgentModelClient.ConversationMessage(role = "assistant", content = "第一轮"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "第二轮"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
            appliedRuntimeRunIds = listOf("run-1", "run-2"),
        )

        val replay = AgentPendingResultRecovery.apply(
            state = state,
            runId = "run-1",
            result = AgentRuntimeWire.RunResult(
                runId = "run-1",
                ok = true,
                content = "第一轮",
                transcript = listOf(
                    AgentModelClient.ConversationMessage(role = "assistant", content = "第一轮")
                ),
            ),
            supplements = emptyList(),
        )

        assertTrue(replay.alreadyApplied)
        assertEquals(state, replay.state)
    }

    @Test
    fun continuationPromptIsAddedToUiAndDurableHistoryOnce() {
        val prompt = AgentUiHandoffPayload.Supplement(
            index = 1,
            text = "继续检查",
            createdAt = 10L,
        )
        val recovered = AgentPendingResultRecovery.apply(
            state = AgentChatUiState(
                messages = emptyList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = false,
            ),
            runId = "run-next",
            result = AgentRuntimeWire.RunResult(
                runId = "run-next",
                ok = true,
                content = "检查完成",
                transcript = listOf(
                    AgentModelClient.ConversationMessage(role = "assistant", content = "检查完成")
                ),
            ),
            promptSupplement = prompt,
            supplements = emptyList(),
        )

        assertEquals(
            listOf("user-run-next-supplement-1", "assistant-run-next-1"),
            recovered.state.messages.map { it.id },
        )
        assertEquals(listOf("user", "assistant"), recovered.state.history.map { it.role })
        assertEquals("继续检查", recovered.state.history.first().content)
    }

    @Test
    fun liveSupplementAppendsAfterStreamingAssistantInsteadOfPreviousUser() {
        val user = io.github.mangi.eta.ui.model.UserMessageUi(id = "user-1", content = "帮我查天气")
        val streaming = AgentMessageUi(
            id = "assistant-run-live-1",
            content = "正在查询",
            isStreaming = true,
            renderMarkdown = false,
        )
        val merged = AgentPendingResultRecovery.mergeSupplements(
            runId = "run-live",
            supplements = listOf(
                AgentUiHandoffPayload.Supplement(index = 0, text = "只要上海", createdAt = 1L),
            ),
            messages = listOf(user, streaming),
        )
        assertEquals(
            listOf("user-1", "assistant-run-live-1", "user-run-live-supplement-0"),
            merged.map { it.id },
        )
    }

    @Test
    fun liveSupplementStillAppendsWhenCurrentRoundHasTools() {
        val user = io.github.mangi.eta.ui.model.UserMessageUi(id = "user-1", content = "帮我查天气")
        val tool = ToolActivityMessageUi(
            id = "run-live-tool-1-call",
            toolName = "web_search",
            status = ToolActivityStatusUi.Success,
            argumentsSummary = "上海天气",
        )
        val streaming = AgentMessageUi(
            id = "assistant-run-live-2",
            content = "上海晴",
            isStreaming = true,
            renderMarkdown = false,
        )
        val merged = AgentPendingResultRecovery.mergeSupplements(
            runId = "run-live",
            supplements = listOf(
                AgentUiHandoffPayload.Supplement(index = 0, text = "再看下明天", createdAt = 1L),
            ),
            messages = listOf(user, tool, streaming),
        )
        assertEquals(
            listOf("user-1", "run-live-tool-1-call", "assistant-run-live-2", "user-run-live-supplement-0"),
            merged.map { it.id },
        )
    }
}
