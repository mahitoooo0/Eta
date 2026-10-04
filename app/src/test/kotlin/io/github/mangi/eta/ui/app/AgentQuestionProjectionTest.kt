package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.question.*
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentQuestionProjectionTest {
    private fun request(conversation: String = "chat", run: String = "run", q: String = "q", call: String = "call") =
        AgentQuestionRequest(q, conversation, run, call, "Decide", "Choose one", listOf(
            AgentQuestionOption("a", "Original A", "description"), AgentQuestionOption("b", "B")), recommendedOptionId = "a")
    private fun card(messages: List<AgentChatMessageUi>) = messages.filterIsInstance<AgentQuestionMessageUi>().single()
    private fun selected() = AgentQuestionAnswer("option", "b", note = "note")

    @Test fun recommendedOptionIsNotAutomaticallySelected() {
        val m = card(AgentQuestionProjection.requested("chat", "run", request(), emptyList()))
        assertNull(m.selectedOptionId)
        assertFalse(AgentQuestionCodec.validateAnswer(m.request, AgentQuestionProjection.draftAnswer(m)).accepted)
    }
    @Test fun liveReplayIsIdempotentAndKeepsDraft() {
        val initial = AgentQuestionMessageUi("id", request(), selectedOptionId = "b", note = "draft", otherText = "retained")
        val once = AgentQuestionProjection.requested("chat", "run", request(), listOf(initial), replaying = true)
        val twice = AgentQuestionProjection.requested("chat", "run", request(), once, replaying = true)
        assertEquals(once, twice)
        assertEquals("b", card(twice).selectedOptionId)
        assertEquals("draft", card(twice).note)
        assertEquals("retained", card(twice).otherText)
    }
    @Test fun requestRejectsOtherConversationAndRun() {
        val original = listOf<AgentChatMessageUi>(AgentMessageUi("text", "evidence"))
        assertEquals(original, AgentQuestionProjection.requested("different", "run", request(), original))
        assertEquals(original, AgentQuestionProjection.requested("chat", "different", request(), original))
    }
    @Test fun sealedRunDoesNotCreateNewQuestion() {
        assertTrue(AgentQuestionProjection.requested("chat", "run", request(), emptyList(), acceptNew = false).isEmpty())
    }
    @Test fun foreignAndAmbiguousResolutionsCannotChangeCard() {
        val first = AgentQuestionMessageUi("id", request())
        val event = AgentEvent.QuestionResolved("q", "run", AgentQuestionStatus.Answered, selected())
        assertEquals(listOf(first), AgentQuestionProjection.resolved("foreign", "run", event, listOf(first)))
        assertEquals(listOf(first), AgentQuestionProjection.resolved("chat", "run", event.copy(runId = "other"), listOf(first)))
        val ambiguous = listOf(first, first.copy(id = "second", request = request(call = "different")))
        assertEquals(ambiguous, AgentQuestionProjection.resolved("chat", "run", event, ambiguous))
    }
    @Test fun resolutionValidatesAnswerAndIsIdempotent() {
        val initial = listOf<AgentChatMessageUi>(AgentQuestionMessageUi("id", request()))
        val invalid = AgentEvent.QuestionResolved("q", "run", AgentQuestionStatus.Answered, AgentQuestionAnswer("option", "fake"))
        assertEquals(initial, AgentQuestionProjection.resolved("chat", "run", invalid, initial))
        val event = invalid.copy(answer = selected())
        val resolved = AgentQuestionProjection.resolved("chat", "run", event, initial)
        assertEquals(AgentQuestionStatus.Answered, card(resolved).status)
        assertEquals(selected(), card(resolved).answer)
        assertEquals(resolved, AgentQuestionProjection.resolved("chat", "run", event, resolved))
    }
    @Test fun reservationAckDoesNotInventAnswer() {
        val m = AgentQuestionMessageUi("id", request(), selectedOptionId = "b", submitting = true)
        val result = AgentQuestionProjection.acknowledged(m, selected(), AgentQuestionReceipt(true, "QUESTION_RESERVED"))
        assertEquals(AgentQuestionStatus.Waiting, result.status)
        assertTrue(result.submitting)
        assertNull(result.answer)
    }
    @Test fun ackTimeoutKeepsDraftAndAllowsRetry() {
        val m = AgentQuestionMessageUi("id", request(), selectedOptionId = "b", note = "draft", submitting = true)
        val result = AgentQuestionProjection.acknowledged(m, selected(), AgentQuestionReceipt(false, "QUESTION_ACK_TIMEOUT"))
        assertEquals(AgentQuestionStatus.Waiting, result.status)
        assertFalse(result.submitting)
        assertEquals("b", result.selectedOptionId)
        assertEquals("draft", result.note)
    }
    @Test fun lateAckCannotResurrectInterruptedCard() {
        val m = AgentQuestionMessageUi("id", request(), status = AgentQuestionStatus.Interrupted)
        assertEquals(m, AgentQuestionProjection.acknowledged(m, selected(), AgentQuestionReceipt(true, "QUESTION_RESERVED")))
    }
    @Test fun resolvedBeforeAckRemainsActualAnswer() {
        val actual = selected().copy(note = "runtime")
        val m = AgentQuestionMessageUi("id", request(), status = AgentQuestionStatus.Answered, answer = actual)
        assertEquals(actual, AgentQuestionProjection.acknowledged(m, selected(), AgentQuestionReceipt(true, "QUESTION_RESERVED")).answer)
    }
    @Test fun definitiveRuntimeLossInterruptsOnlyOwningRunAndPreservesDraft() {
        val m = AgentQuestionMessageUi("id", request(), selectedOptionId = "b", submitting = true, note = "draft")
        val other = m.copy(id = "other", request = request(run = "other"))
        val result = AgentQuestionProjection.interruptWaiting("run", listOf(m, other))
        val interrupted = result.first() as AgentQuestionMessageUi
        assertEquals(AgentQuestionStatus.Interrupted, interrupted.status)
        assertFalse(interrupted.submitting)
        assertEquals("draft", interrupted.note)
        assertEquals(other, result.last())
    }
    @Test fun persistenceRoundTripsRequestDraftStatusButNotTransientSubmission() {
        val original = AgentQuestionMessageUi("id", request(), selectedOptionId = "b", answerKind = "other",
            otherText = "custom", note = "persisted", submitting = true, error = "transient")
        val decoded = AgentQuestionPersistence.decode("id", "chat", AgentQuestionPersistence.encode(original))!!
        assertEquals(original.copy(submitting = false, error = null), decoded)
    }
    @Test fun answeredPersistenceUsesValidatedOriginalOption() {
        val m = AgentQuestionMessageUi("id", request(), AgentQuestionStatus.Answered, selected())
        val decoded = AgentQuestionPersistence.decode("id", "chat", AgentQuestionPersistence.encode(m))!!
        assertEquals(m, decoded)
        assertEquals("B", AgentQuestionCodec.resultJson(decoded.request, decoded.answer!!).getJSONObject("selected_option").getString("label"))
    }
    @Test fun storageRejectsForeignOwnershipAndFutureVersions() {
        val raw = AgentQuestionPersistence.encode(AgentQuestionMessageUi("id", request()))
        assertNull(AgentQuestionPersistence.decode("id", "foreign", raw))
        assertNull(AgentQuestionPersistence.decode("id", "chat", JSONObject(raw).put("version", 2).toString()))
    }
    @Test fun corruptAnsweredHistoryBecomesInterruptedNotFakeSuccess() {
        val raw = JSONObject(AgentQuestionPersistence.encode(AgentQuestionMessageUi("id", request())))
            .put("status", "Answered")
        assertEquals(AgentQuestionStatus.Interrupted, AgentQuestionPersistence.decode("id", "chat", raw.toString())!!.status)
    }
    @Test fun draftAnswerFiltersInactiveFieldsAndForbiddenNote() {
        val m = AgentQuestionMessageUi("id", request().copy(allowNote = false), selectedOptionId = "a", otherText = "retained", note = "forbidden")
        assertEquals(AgentQuestionAnswer("option", "a"), AgentQuestionProjection.draftAnswer(m))
        assertEquals(AgentQuestionAnswer("delegate"), AgentQuestionProjection.draftAnswer(m.copy(answerKind = "delegate")))
    }
    @Test fun historyReplayDoesNotReviveOrMoveAnInterruptedCard() {
        val m = AgentQuestionMessageUi("id", request(), status = AgentQuestionStatus.Interrupted, note = "draft")
        val messages = listOf<AgentChatMessageUi>(m, AgentMessageUi("after", "later evidence"))
        assertEquals(messages, AgentQuestionProjection.requested("chat", "run", request(), messages, replaying = true))
        assertEquals(messages, AgentQuestionProjection.requested("chat", "run", request(), messages, acceptNew = false, replaying = true))
    }
    @Test fun authoritativeSnapshotResolvesButForeignSnapshotIsIgnored() {
        val m = AgentQuestionMessageUi("id", request(), submitting = true)
        val snapshot = AgentQuestionSnapshot("chat", "run", "q", "call", AgentQuestionStatus.Answered, selected())
        assertEquals(m, AgentQuestionProjection.reconcile(m, snapshot.copy(toolCallId = "foreign")))
        val resolved = AgentQuestionProjection.reconcile(m, snapshot)
        assertEquals(AgentQuestionStatus.Answered, resolved.status)
        assertEquals(selected(), resolved.answer)
        assertFalse(resolved.submitting)
    }
    @Test fun pendingSnapshotOrUnknownTransportUnlocksOnlySubmissionNotUserWait() {
        val m = AgentQuestionMessageUi("id", request(), selectedOptionId = "b", note = "draft", submitting = true)
        val pending = AgentQuestionSnapshot("chat", "run", "q", "call", AgentQuestionStatus.Waiting)
        assertEquals(m.copy(submitting = false), AgentQuestionProjection.reconcile(m, pending))
        val unknown = AgentQuestionProjection.reconcile(m, null)
        assertEquals(AgentQuestionStatus.Waiting, unknown.status)
        assertEquals("draft", unknown.note)
        assertFalse(unknown.submitting)
    }
    @Test fun authoritativeDefinitiveLossDisablesCardWithoutInventingAnswer() {
        val m = AgentQuestionMessageUi("id", request(), submitting = true)
        val ended = AgentQuestionSnapshot("chat", "run", "q", "call", AgentQuestionStatus.Interrupted)
        val result = AgentQuestionProjection.reconcile(m, ended)
        assertEquals(AgentQuestionStatus.Interrupted, result.status)
        assertNull(result.answer)
        assertFalse(result.submitting)
    }

    @Test fun endedAckStillQueriesAndRecoversConsumedAnswer() {
        for (code in listOf("QUESTION_RUN_NOT_ACTIVE", "QUESTION_NOT_PENDING", "QUESTION_LATE")) {
            val initial = AgentQuestionMessageUi("id", request(), selectedOptionId = "b", note = "draft", submitting = true)
            val ack = AgentQuestionProjection.acknowledged(initial, selected(), AgentQuestionReceipt(false, code))
            assertEquals(AgentQuestionStatus.Waiting, ack.status)
            assertFalse(ack.submitting)
            assertNull(ack.answer)
            assertEquals("draft", ack.note)
            assertTrue(AgentQuestionProjection.needsAuthoritativeQuery(listOf(ack), initial.request))
            // RunFinished can win before the bounded read. Exercise the same list helpers used
            // by both AppState and voice, rather than a copied Waiting-only caller predicate.
            val closed = AgentQuestionProjection.interruptWaiting("run", listOf(ack))
            assertTrue(AgentQuestionProjection.needsAuthoritativeQuery(closed, initial.request))
            val recovered = AgentQuestionProjection.reconcileMessages(closed, initial.request,
                AgentQuestionSnapshot("chat", "run", "q", "call", AgentQuestionStatus.Answered, selected()))
            assertEquals(AgentQuestionStatus.Answered, card(recovered).status)
            assertEquals(selected(), card(recovered).answer)
            assertFalse(AgentQuestionProjection.needsAuthoritativeQuery(recovered, initial.request))
        }
    }

    @Test fun validAuthoritativeAnswerCorrectsEitherLocallyClosedState() {
        for (status in listOf(AgentQuestionStatus.Interrupted, AgentQuestionStatus.Cancelled)) {
            val initial = AgentQuestionMessageUi("id", request(), status = status,
                selectedOptionId = "b", note = "retained draft", submitting = true, error = "local")
            val actual = selected().copy(note = "consumed")
            val snapshot = AgentQuestionSnapshot("chat", "run", "q", "call", AgentQuestionStatus.Answered, actual)
            val ack = AgentQuestionProjection.acknowledged(initial, selected(), AgentQuestionReceipt(true, "QUESTION_RESERVED"))
            assertEquals(status, ack.status)
            assertTrue(AgentQuestionProjection.needsAuthoritativeQuery(listOf(ack), initial.request))
            val recovered = card(AgentQuestionProjection.reconcileMessages(listOf(ack), initial.request, snapshot))
            assertEquals(AgentQuestionStatus.Answered, recovered.status)
            assertEquals(actual, recovered.answer)
            assertEquals("retained draft", recovered.note)
            assertFalse(recovered.submitting)
            assertNull(recovered.error)
        }
    }

    @Test fun correctiveTerminalStatesHaveExplicitAuthorityWithoutWaitingRevival() {
        val pending = AgentQuestionSnapshot("chat", "run", "q", "call", AgentQuestionStatus.Waiting)
        for (status in listOf(AgentQuestionStatus.Interrupted, AgentQuestionStatus.Cancelled)) {
            val closed = AgentQuestionMessageUi("id", request(), status = status, note = "draft", submitting = true)
            assertEquals(closed.copy(submitting = false), AgentQuestionProjection.reconcile(closed, pending))
            val otherTerminal = if (status == AgentQuestionStatus.Interrupted) AgentQuestionStatus.Cancelled
                else AgentQuestionStatus.Interrupted
            assertEquals(closed.copy(status = otherTerminal, submitting = false),
                AgentQuestionProjection.reconcile(closed, pending.copy(status = otherTerminal)))
        }
        val answered = AgentQuestionMessageUi("id", request(), status = AgentQuestionStatus.Answered,
            answer = selected(), submitting = true)
        for (status in listOf(AgentQuestionStatus.Waiting, AgentQuestionStatus.Interrupted, AgentQuestionStatus.Cancelled)) {
            assertEquals(answered.copy(submitting = false), AgentQuestionProjection.reconcile(answered, pending.copy(status = status)))
        }
        assertEquals(answered.copy(submitting = false), AgentQuestionProjection.reconcile(answered, null))
    }

    @Test fun unknownReadOnlyUnlocksSubmissionForEveryStatusAndKeepsAllDraftFields() {
        for (status in AgentQuestionStatus.values()) {
            val initial = AgentQuestionMessageUi("id", request(), status = status,
                answer = selected().takeIf { status == AgentQuestionStatus.Answered },
                selectedOptionId = "b", answerKind = "other", otherText = "custom", note = "draft",
                submitting = true, error = "existing receipt")
            assertEquals(initial.copy(submitting = false), AgentQuestionProjection.reconcile(initial, null))
        }
    }

    @Test fun closedCardRejectsEveryForeignOwnerAndInvalidAnswer() {
        val initial = AgentQuestionMessageUi("id", request(), status = AgentQuestionStatus.Interrupted,
            selectedOptionId = "b", note = "draft", submitting = true)
        val snapshot = AgentQuestionSnapshot("chat", "run", "q", "call", AgentQuestionStatus.Answered, selected())
        for (foreign in listOf(snapshot.copy(conversationId = "other"), snapshot.copy(runId = "other"),
            snapshot.copy(questionId = "other"), snapshot.copy(toolCallId = "other"))) {
            assertEquals(initial, AgentQuestionProjection.reconcile(initial, foreign))
        }
        for (invalid in listOf(snapshot.copy(answer = null), snapshot.copy(answer = AgentQuestionAnswer("option", "missing")),
            snapshot.copy(status = AgentQuestionStatus.Cancelled))) {
            assertEquals(initial.copy(submitting = false), AgentQuestionProjection.reconcile(initial, invalid))
        }
        assertFalse(AgentQuestionProjection.needsAuthoritativeQuery(listOf(initial), request(call = "other")))
        assertEquals(listOf(initial), AgentQuestionProjection.reconcileMessages(listOf(initial), request(run = "other"), snapshot))
    }

    @Test fun recoveryBatchIncludesClosedExactOwnersButIsBoundedAndDoesNotCloseSkippedCards() {
        val messages = (0 until 12).map { i -> AgentQuestionMessageUi("id-$i", request(q = "q-$i"),
            status = if (i % 2 == 0) AgentQuestionStatus.Waiting else AgentQuestionStatus.Interrupted) }
        val foreign = messages.first().copy(id = "foreign", request = request(conversation = "foreign"))
        val answered = messages.first().copy(id = "answered", request = request(q = "answered"),
            status = AgentQuestionStatus.Answered, answer = selected())
        val all = messages + foreign + answered + messages.first()
        val owners = AgentQuestionProjection.recoveryOwners("chat", all)
        assertEquals(12, owners.size)
        val first = AgentQuestionProjection.recoveryQueryBatch(owners, 0)
        assertEquals(AgentQuestionProjection.MAX_RECOVERY_QUERIES, first.owners.size)
        val second = AgentQuestionProjection.recoveryQueryBatch(owners, first.nextOffset)
        assertEquals(owners.toSet(), (first.owners + second.owners).toSet())
        var reconciled: List<AgentChatMessageUi> = all
        first.owners.forEach { owner -> reconciled = AgentQuestionProjection.reconcileMessages(reconciled, owner, null) }
        assertEquals(all, reconciled) // Unknown or omitted entries are never declared dead.
        assertEquals(0, AgentQuestionProjection.recoveryQueryBatch(emptyList(), 100).nextOffset)
    }

    @Test fun branchFreezeRewritesOwnerAndPreservesDraftAndAnsweredHistory() {
        val waiting = AgentQuestionMessageUi("id", request(), selectedOptionId = "b", answerKind = "other",
            otherText = "custom", note = "draft", submitting = true, error = "transient")
        val frozen = AgentQuestionProjection.freezeForBranch(waiting, "branch")
        assertEquals(waiting.copy(request = request(conversation = "branch"),
            status = AgentQuestionStatus.Interrupted, submitting = false, error = null), frozen)
        assertFalse(AgentQuestionProjection.hasWaiting(listOf(frozen)))
        val oldAnswer = AgentQuestionSnapshot("chat", "run", "q", "call", AgentQuestionStatus.Answered, selected())
        assertEquals(frozen, AgentQuestionProjection.reconcile(frozen, oldAnswer))
        assertEquals(frozen, AgentQuestionProjection.reconcile(frozen,
            oldAnswer.copy(conversationId = "branch", status = AgentQuestionStatus.Waiting, answer = null)))
        for (status in listOf(AgentQuestionStatus.Answered, AgentQuestionStatus.Interrupted, AgentQuestionStatus.Cancelled)) {
            val historical = waiting.copy(status = status,
                answer = selected().takeIf { status == AgentQuestionStatus.Answered })
            val branch = AgentQuestionProjection.freezeForBranch(historical, "branch")
            assertEquals(status, branch.status)
            assertEquals(historical.answer, branch.answer)
            assertEquals(historical.note, branch.note)
            assertFalse(branch.submitting)
        }
    }

}
