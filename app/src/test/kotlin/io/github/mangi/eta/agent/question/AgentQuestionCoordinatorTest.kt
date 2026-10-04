package io.github.mangi.eta.agent.question

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentQuestionCoordinatorTest {
    @Test fun cancelWakesWaiterWithoutAnAnswer() {
        val harness = Harness()
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        await(harness.requested)
        assertEquals("QUESTION_FOREIGN", submit(harness, toolCallId = "someone-else").code)
        harness.controller.cancel()
        succeeded(waiter)
        assertNull(waiter.result.get())
        val events = harness.events()
        assertTrue(events.indexOfFirst { it is AgentEvent.QuestionRequested } <
            events.indexOfFirst { it is AgentEvent.QuestionResolved })
        assertEquals(AgentQuestionStatus.Cancelled, harness.resolutions().single().status)
        assertNull(harness.resolutions().single().answer)
        assertEquals("QUESTION_LATE", submit(harness).code)
    }

    @Test fun allFourIdsAndBlankIdsAreCheckedWithoutConsumingTheQuestion() {
        val harness = Harness()
        assertEquals("QUESTION_NOT_PENDING", submit(harness).code)
        assertEquals("QUESTION_FOREIGN", submit(harness, questionId = "").code)
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        await(harness.requested)
        val foreign = listOf(
            submit(harness, conversationId = "other"),
            submit(harness, runId = "other"),
            submit(harness, questionId = "other"),
            submit(harness, toolCallId = "other"),
            submit(harness, conversationId = " "),
            submit(harness, runId = ""),
            submit(harness, questionId = ""),
            submit(harness, toolCallId = ""),
        )
        foreign.forEach {
            assertFalse(it.accepted)
            assertEquals("QUESTION_FOREIGN", it.code)
        }
        assertTrue(harness.resolutions().isEmpty())
        assertReserved(submit(harness))
        succeeded(waiter)
        assertEquals(option(), waiter.result.get())
        assertEquals(AgentQuestionStatus.Answered, harness.resolutions().single().status)
        assertEquals("QUESTION_FOREIGN", submit(harness, runId = "other").code)
    }

    @Test fun invalidAnswerStaysWaitingUntilAValidOne() {
        val harness = Harness()
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        await(harness.requested)
        val receipt = submit(harness, answer = AgentQuestionAnswer(kind = "other", otherText = ""))
        assertFalse(receipt.accepted)
        assertEquals(AgentQuestionCodec.CODE_OTHER_TEXT_REQUIRED, receipt.code)
        assertStillRunning(waiter)
        assertTrue(harness.resolutions().isEmpty())
        assertReserved(submit(harness))
        succeeded(waiter)
        assertEquals(option(), waiter.result.get())
    }

    @Test fun concurrentDifferentAnswersReserveExactlyOneAndRejectTheOther() {
        val releaseRequest = CountDownLatch(1)
        val harness = Harness { if (it is AgentEvent.QuestionRequested) releaseRequest.await() }
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        try {
            await(harness.requested)
            val start = CountDownLatch(1)
            val submitters = listOf("yes", "no").map { id ->
                Task { start.await(); submit(harness, answer = option(id)) }
            }
            start.countDown()
            submitters.forEach(::succeeded)
            val receipts = submitters.map { it.result.get()!! }
            assertEquals(1, receipts.count { it.accepted })
            assertEquals(1, receipts.count { it.code == "QUESTION_RESERVED" })
            assertEquals(1, receipts.count { it.code == "QUESTION_DUPLICATE" })
            val winner = if (receipts[0].accepted) option("yes") else option("no")
            val loser = if (receipts[0].accepted) option("no") else option("yes")
            assertReserved(submit(harness, answer = winner))
            assertEquals("QUESTION_DUPLICATE", submit(harness, answer = loser).code)
            assertTrue(harness.resolutions().isEmpty())
            releaseRequest.countDown()
            succeeded(waiter)
            assertEquals(winner, waiter.result.get())
            assertEquals(1, harness.resolutions().size)
            val retry = submit(harness, answer = winner)
            assertTrue(retry.accepted)
            assertEquals("QUESTION_ALREADY_ANSWERED", retry.code)
            assertEquals("QUESTION_DUPLICATE", submit(harness, answer = loser).code)
        } finally {
            releaseRequest.countDown()
        }
    }

    @Test fun concurrentIdenticalAnswersAreIdempotentReservationsWithOneResolution() {
        val releaseRequest = CountDownLatch(1)
        val harness = Harness { if (it is AgentEvent.QuestionRequested) releaseRequest.await() }
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        try {
            await(harness.requested)
            val start = CountDownLatch(1)
            val submitters = List(2) { Task { start.await(); submit(harness) } }
            start.countDown()
            submitters.forEach(::succeeded)
            submitters.forEach { assertReserved(it.result.get()!!) }
            // 完全相同才可重试；同 option 但 note 不同也不能覆写第一次受理。
            assertEquals("QUESTION_DUPLICATE", submit(harness, answer = option().copy(note = "new")).code)
            releaseRequest.countDown()
            succeeded(waiter)
            assertEquals(option(), waiter.result.get())
            assertEquals(1, harness.resolutions().size)
            assertEquals("QUESTION_ALREADY_ANSWERED", submit(harness).code)
        } finally {
            releaseRequest.countDown()
        }
    }

    @Test fun blockedResolutionDoesNotBlockAckButPreventsReturnAndTheNextAwait() {
        val publishing = CountDownLatch(1)
        val releaseResolution = CountDownLatch(1)
        val nextRequested = CountDownLatch(1)
        val harness = Harness { event ->
            if (event is AgentEvent.QuestionRequested && event.request.questionId == "q2") nextRequested.countDown()
            if (event is AgentEvent.QuestionResolved && event.questionId == "q1") {
                publishing.countDown()
                releaseResolution.await()
            }
        }
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        try {
            await(harness.requested)
            val submitter = Task { submit(harness) }
            succeeded(submitter)
            assertReserved(submitter.result.get()!!)
            await(publishing)
            assertStillRunning(waiter)
            assertTrue(harness.resolutions().isEmpty())
            assertActiveAwaitRejected(harness, request("q2", "tool-2"))
            assertReserved(submit(harness))
            assertEquals("QUESTION_DUPLICATE", submit(harness, answer = option("no")).code)
            releaseResolution.countDown()
            succeeded(waiter)
            assertEquals(option(), waiter.result.get())

            val next = Task { harness.coordinator.awaitAnswer(request("q2", "tool-2")) }
            await(nextRequested)
            assertReserved(submit(harness, questionId = "q2", toolCallId = "tool-2"))
            succeeded(next)
            val events = harness.events()
            val firstResolved = events.indexOfFirst { it is AgentEvent.QuestionResolved && it.questionId == "q1" }
            val secondRequested = events.indexOfFirst { it is AgentEvent.QuestionRequested && it.request.questionId == "q2" }
            assertTrue(firstResolved >= 0 && firstResolved < secondRequested)
        } finally {
            releaseResolution.countDown()
        }
    }

    @Test fun newAwaitCannotOverwriteAnActiveWaitingSlotEvenWithIdenticalIds() {
        val harness = Harness()
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        await(harness.requested)
        assertActiveAwaitRejected(harness, request())
        assertActiveAwaitRejected(harness, request("q2", "tool-2"))
        assertReserved(submit(harness))
        succeeded(waiter)
        assertEquals(option(), waiter.result.get())
        assertEquals(1, harness.events().filterIsInstance<AgentEvent.QuestionRequested>().size)
    }

    @Test fun cancellationBindingIsInstalledBeforeRequestedAndNeverRunsEventCallbacksOnCancel() {
        val releaseRequest = CountDownLatch(1)
        val harness = Harness { if (it is AgentEvent.QuestionRequested) releaseRequest.await() }
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        try {
            await(harness.requested)
            val cancellation = Task { harness.controller.cancel() }
            succeeded(cancellation)
            assertStillRunning(waiter)
            assertEquals("QUESTION_LATE", submit(harness).code)
            // Cancelled 状态不代表 await 已退出，不能用新请求覆盖它。
            assertActiveAwaitRejected(harness, request("q2", "tool-2"))
            releaseRequest.countDown()
            succeeded(waiter)
            assertNull(waiter.result.get())
            assertEquals(AgentQuestionStatus.Cancelled, harness.resolutions().single().status)
        } finally {
            releaseRequest.countDown()
        }
    }

    @Test fun alreadyCancelledControllerResolvesWithoutAnswering() {
        lateinit var harness: Harness
        val callbackReceipt = AtomicReference<AgentQuestionReceipt?>()
        harness = Harness { if (it is AgentEvent.QuestionRequested) callbackReceipt.set(submit(harness)) }
        harness.controller.cancel()
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        succeeded(waiter)
        assertNull(waiter.result.get())
        assertEquals("QUESTION_LATE", callbackReceipt.get()?.code)
        assertEquals(AgentQuestionStatus.Cancelled, harness.resolutions().single().status)
    }

    @Test fun cancelledFlagRejectsSubmitBeforeTheCancelResourceRuns() {
        val harness = Harness()
        val earlyReceipt = AtomicReference<AgentQuestionReceipt?>()
        harness.controller.register { earlyReceipt.set(submit(harness)) }
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        await(harness.requested)
        harness.controller.cancel()
        succeeded(waiter)
        assertNull(waiter.result.get())
        assertEquals("QUESTION_LATE", earlyReceipt.get()?.code)
        assertFalse(earlyReceipt.get()?.accepted == true)
        assertEquals(AgentQuestionStatus.Cancelled, harness.resolutions().single().status)
    }

    @Test fun cancellationRevokesAReservationBeforeResolutionStarts() {
        val releaseRequest = CountDownLatch(1)
        val harness = Harness { if (it is AgentEvent.QuestionRequested) releaseRequest.await() }
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        try {
            await(harness.requested)
            assertReserved(submit(harness))
            harness.controller.cancel()
            assertEquals("QUESTION_LATE", submit(harness).code)
            releaseRequest.countDown()
            succeeded(waiter)
            assertNull(waiter.result.get())
            val resolved = harness.resolutions().single()
            assertEquals(AgentQuestionStatus.Cancelled, resolved.status)
            assertNull(resolved.answer)
        } finally {
            releaseRequest.countDown()
        }
    }

    @Test fun alreadyStartedAnswerPublicationWinsCancellationWithoutBlockingCancel() {
        val publishing = CountDownLatch(1)
        val releaseResolution = CountDownLatch(1)
        val harness = Harness {
            if (it is AgentEvent.QuestionResolved) {
                publishing.countDown()
                releaseResolution.await()
            }
        }
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        try {
            await(harness.requested)
            assertReserved(submit(harness))
            await(publishing)
            val cancellation = Task { harness.controller.cancel() }
            succeeded(cancellation)
            assertStillRunning(waiter)
            assertReserved(submit(harness))
            assertActiveAwaitRejected(harness, request("q2", "tool-2"))
            releaseResolution.countDown()
            succeeded(waiter)
            assertEquals(option(), waiter.result.get())
            assertEquals(AgentQuestionStatus.Answered, harness.resolutions().single().status)
        } finally {
            releaseResolution.countDown()
        }
    }

    @Test fun interruptedWaiterPreservesInterruptAndNeverReturnsAnAnswer() {
        val harness = Harness()
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        await(harness.requested)
        waiter.worker.interrupt()
        succeeded(waiter)
        assertNull(waiter.result.get())
        assertTrue(waiter.interrupted.get())
        assertEquals(AgentQuestionStatus.Interrupted, harness.resolutions().single().status)
        assertEquals("QUESTION_LATE", submit(harness).code)
    }

    @Test fun interruptBeforePublicationRevokesAnAlreadyReservedAnswer() {
        val releaseRequest = CountDownLatch(1)
        val interruptedAtGate = CountDownLatch(1)
        val harness = Harness {
            if (it is AgentEvent.QuestionRequested) awaitUninterruptibly(releaseRequest, interruptedAtGate)
        }
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        try {
            await(harness.requested)
            assertReserved(submit(harness))
            waiter.worker.interrupt()
            await(interruptedAtGate)
            assertStillRunning(waiter)
            assertActiveAwaitRejected(harness, request("q2", "tool-2"))
            releaseRequest.countDown()
            succeeded(waiter)
            assertNull(waiter.result.get())
            assertTrue(waiter.interrupted.get())
            assertEquals(AgentQuestionStatus.Interrupted, harness.resolutions().single().status)
            assertEquals("QUESTION_LATE", submit(harness).code)
        } finally {
            releaseRequest.countDown()
        }
    }

    @Test fun interruptDuringPublicationCannotBypassTheBarrierOrReturnAnAnswer() {
        val publishing = CountDownLatch(1)
        val releaseResolution = CountDownLatch(1)
        val interruptedAtGate = CountDownLatch(1)
        val harness = Harness {
            if (it is AgentEvent.QuestionResolved && it.status == AgentQuestionStatus.Answered) {
                publishing.countDown()
                awaitUninterruptibly(releaseResolution, interruptedAtGate)
            }
        }
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        try {
            await(harness.requested)
            assertReserved(submit(harness))
            await(publishing)
            waiter.worker.interrupt()
            await(interruptedAtGate)
            assertStillRunning(waiter)
            assertActiveAwaitRejected(harness, request("q2", "tool-2"))
            releaseResolution.countDown()
            succeeded(waiter)
            assertNull(waiter.result.get())
            assertTrue(waiter.interrupted.get())
            // 已完成的 Answered 回调不能撤回，必须再以 Interrupted 收尾。
            assertEquals(listOf(AgentQuestionStatus.Answered, AgentQuestionStatus.Interrupted),
                harness.resolutions().map { it.status })
            assertNull(harness.resolutions().last().answer)
            assertEquals("QUESTION_LATE", submit(harness).code)
        } finally {
            releaseResolution.countDown()
        }
    }

    @Test fun interruptedCorrectivePublicationFailurePreservesInterruptAndNeverReturnsAnswer() {
        val failure = IllegalStateException("interrupted callback failed")
        val harness = Harness {
            if (it is AgentEvent.QuestionResolved) {
                if (it.status == AgentQuestionStatus.Answered) Thread.currentThread().interrupt()
                if (it.status == AgentQuestionStatus.Interrupted) throw failure
            }
        }
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        await(harness.requested)
        assertReserved(submit(harness))
        finished(waiter)
        assertSame(failure, waiter.failure.get())
        assertNull(waiter.result.get())
        assertTrue(waiter.interrupted.get())
        assertEquals("QUESTION_LATE", submit(harness).code)
    }

    @Test fun requestedCallbackCanSubmitSynchronously() {
        lateinit var harness: Harness
        val receipt = AtomicReference<AgentQuestionReceipt?>()
        harness = Harness { if (it is AgentEvent.QuestionRequested) receipt.set(submit(harness)) }
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        succeeded(waiter)
        assertReserved(receipt.get()!!)
        assertEquals(option(), waiter.result.get())
        assertEquals(listOf(AgentQuestionStatus.Answered), harness.resolutions().map { it.status })
    }

    @Test fun eventCallbacksCanUseCoordinatorFromAnotherThreadWithoutLockInversion() {
        lateinit var harness: Harness
        harness = Harness { event ->
            if (event is AgentEvent.QuestionRequested) {
                val submission = Task { submit(harness) }
                succeeded(submission)
                assertReserved(submission.result.get()!!)
            } else if (event is AgentEvent.QuestionResolved) {
                val duplicate = Task { submit(harness, answer = option("no")) }
                succeeded(duplicate)
                assertEquals("QUESTION_DUPLICATE", duplicate.result.get()?.code)
            }
        }
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        succeeded(waiter)
        assertEquals(option(), waiter.result.get())
    }

    @Test fun requestedCallbackFailureRevokesSynchronousReservationAndReleasesOwnership() {
        val failure = IllegalStateException("requested callback failed")
        lateinit var harness: Harness
        harness = Harness {
            if (it is AgentEvent.QuestionRequested && it.request.questionId == "q1") {
                assertReserved(submit(harness))
                throw failure
            }
        }
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        finished(waiter)
        assertSame(failure, waiter.failure.get())
        assertNull(waiter.result.get())
        assertTrue(harness.resolutions().isEmpty())
        assertEquals("QUESTION_LATE", submit(harness).code)
        assertNextAwaitWorks(harness)
    }

    @Test fun resolvedCallbackFailureNeverCommitsAnsweredAndReleasesOwnership() {
        val failure = IllegalStateException("resolved callback failed")
        val harness = Harness {
            if (it is AgentEvent.QuestionResolved && it.questionId == "q1") throw failure
        }
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        await(harness.requested)
        assertReserved(submit(harness))
        finished(waiter)
        assertSame(failure, waiter.failure.get())
        assertNull(waiter.result.get())
        assertTrue(harness.resolutions().isEmpty())
        assertEquals("QUESTION_LATE", submit(harness).code)
        assertNextAwaitWorks(harness)
    }

    @Test fun interruptedExceptionFromRequestedCallbackReturnsNullAndPreservesInterrupt() {
        val harness = Harness { if (it is AgentEvent.QuestionRequested) throw InterruptedException() }
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        succeeded(waiter)
        assertNull(waiter.result.get())
        assertTrue(waiter.interrupted.get())
        assertTrue(harness.resolutions().isEmpty())
        assertEquals("QUESTION_LATE", submit(harness).code)
    }

    @Test fun interruptedExceptionFromResolvedCallbackDoesNotCommitAnswered() {
        val harness = Harness { if (it is AgentEvent.QuestionResolved) throw InterruptedException() }
        val waiter = Task { harness.coordinator.awaitAnswer(request()) }
        await(harness.requested)
        assertReserved(submit(harness))
        succeeded(waiter)
        assertNull(waiter.result.get())
        assertTrue(waiter.interrupted.get())
        assertTrue(harness.resolutions().isEmpty())
        assertEquals("QUESTION_LATE", submit(harness).code)
    }

    private fun assertNextAwaitWorks(harness: Harness) {
        val nextRequest = request("q2", "tool-2")
        val next = Task { harness.coordinator.awaitAnswer(nextRequest) }
        // 第二次请求的就绪信号独立于第一次请求，禁止用旧 latch 或重试提交探测就绪。
        await(harness.secondRequested)
        assertReserved(submit(harness, questionId = "q2", toolCallId = "tool-2"))
        succeeded(next)
        assertEquals(option(), next.result.get())
    }

    private fun assertActiveAwaitRejected(harness: Harness, request: AgentQuestionRequest) {
        val attempt = Task { harness.coordinator.awaitAnswer(request) }
        finished(attempt)
        assertTrue("An active await must not be overwritten", attempt.failure.get() is IllegalStateException)
    }

    private fun assertReserved(receipt: AgentQuestionReceipt) {
        assertTrue(receipt.accepted)
        assertEquals("QUESTION_RESERVED", receipt.code)
    }

    private fun submit(
        harness: Harness,
        conversationId: String = "conv",
        runId: String = "run",
        questionId: String = "q1",
        toolCallId: String = "tool-1",
        answer: AgentQuestionAnswer = option(),
    ) = harness.coordinator.submitAnswer(conversationId, runId, questionId, toolCallId, answer)

    private fun request(questionId: String = "q1", toolCallId: String = "tool-1") = AgentQuestionRequest(
        questionId = questionId,
        conversationId = "conv",
        runId = "run",
        toolCallId = toolCallId,
        title = "选择",
        question = "继续吗？",
        options = listOf(AgentQuestionOption("yes", "是"), AgentQuestionOption("no", "否")),
    )

    private fun option(id: String = "yes") = AgentQuestionAnswer(kind = "option", optionId = id)

    private fun await(latch: CountDownLatch) {
        assertTrue("Timed out waiting for a test synchronization point", latch.await(2, TimeUnit.SECONDS))
    }

    private fun assertStillRunning(task: Task<*>) {
        assertEquals(1L, task.done.count)
        assertTrue(task.worker.isAlive)
    }

    private fun finished(task: Task<*>) {
        await(task.done)
        task.worker.join(2_000)
        assertFalse("Worker did not exit", task.worker.isAlive)
    }

    private fun succeeded(task: Task<*>) {
        finished(task)
        task.failure.get()?.let { throw AssertionError("Worker failed", it) }
    }

    private fun awaitUninterruptibly(release: CountDownLatch, interruptedAtGate: CountDownLatch) {
        var interrupted = false
        while (true) {
            try {
                release.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
                interruptedAtGate.countDown()
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private class Task<T>(block: () -> T) {
        val result = AtomicReference<T?>()
        val failure = AtomicReference<Throwable?>()
        val interrupted = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val worker = thread(isDaemon = true) {
            try {
                result.set(block())
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted)
                done.countDown()
            }
        }
    }

    private class Harness(hook: (AgentEvent) -> Unit = {}) {
        val controller = AgentRunController()
        val requested = CountDownLatch(1)
        val secondRequested = CountDownLatch(1)
        private val recorded = Collections.synchronizedList(mutableListOf<AgentEvent>())
        val coordinator = AgentQuestionCoordinator(controller) { event ->
            if (event is AgentEvent.QuestionRequested) {
                recorded += event
                if (event.request.questionId == "q1") requested.countDown() else secondRequested.countDown()
            }
            hook(event)
            // 记录成功完成的 Resolved 回调，而非仅记录开始发布。
            if (event is AgentEvent.QuestionResolved) recorded += event
        }

        fun events(): List<AgentEvent> = synchronized(recorded) { recorded.toList() }
        fun resolutions(): List<AgentEvent.QuestionResolved> = events().filterIsInstance<AgentEvent.QuestionResolved>()
    }
}
