package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.question.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Production Session + query/publish gates + ledger core, with only the AtomicFile adapter replaced. */
class AgentQuestionDurableAuthorityTest {
    private class Storage(override val key: String = UUID.randomUUID().toString()) : AgentQuestionLedger.Storage {
        @Volatile var json: String? = null
        @Volatile var failRead = false
        @Volatile var failWrite = false
        var writes = 0
        var gate: (() -> Unit)? = null
        override fun read(): String? { if (failRead) throw IOException("read failed"); return json }
        override fun write(json: String) {
            gate?.invoke()
            if (failWrite) throw IOException("write failed")
            this.json = json
            writes++
        }
    }
    private fun request(q: String = "q", run: String = "run") = AgentQuestionRequest(q, "chat", run, "call", "title", "question",
        listOf(AgentQuestionOption("a", "A"), AgentQuestionOption("b", "B")))
    private val answer = AgentQuestionAnswer("option", "a", note = "original answer")
    private fun resolved(status: AgentQuestionStatus = AgentQuestionStatus.Answered, q: String = "q", run: String = "run") =
        AgentEvent.QuestionResolved(q, run, status, answer.takeIf { status == AgentQuestionStatus.Answered })
    private fun query(registry: AgentRuntimeSessionRegistry, ledger: AgentQuestionLedger) =
        AgentQuestionAuthority.query(registry, ledger, "chat", "run", "q", "call")
    private fun fatal(block: () -> Unit) {
        try { block(); fail("Expected fatal question interruption") }
        catch (_: AgentQuestionInterruptedException) { }
    }

    @Test fun failedRunRemovalAndDeliveryAckDoNotEraseOriginalAnswer() {
        val storage = Storage()
        val ledger = AgentQuestionLedger(storage)
        val registry = AgentRuntimeSessionRegistry()
        val session = AgentRuntimeSession("run", questionLedger = ledger)
        registry.put(session)
        AgentQuestionEventPublisher.publish(session, AgentEvent.QuestionRequested(request()))
        AgentQuestionEventPublisher.publish(session, resolved())
        session.complete(AgentRuntimeWire.RunResult("run", false, "", "provider failed"))
        assertEquals(answer, query(registry, ledger)!!.answer)
        assertTrue(registry.remove(session))
        // ACK/checkpoint/archive have no API into this independent ledger.
        val rebuilt = AgentQuestionLedger(storage)
        assertEquals(AgentQuestionStatus.Answered, query(AgentRuntimeSessionRegistry(), rebuilt)!!.status)
        assertEquals(answer, query(AgentRuntimeSessionRegistry(), rebuilt)!!.answer)
        assertNull(AgentQuestionAuthority.query(registry, rebuilt, "chat", "run", "unknown", "call"))
    }

    @Test fun reconstructedServiceAndProcessReadOnlyKnownWaitingCanInterrupt() {
        val storage = Storage()
        val ledger = AgentQuestionLedger(storage)
        val session = AgentRuntimeSession("run", questionLedger = ledger)
        AgentQuestionEventPublisher.publish(session, AgentEvent.QuestionRequested(request()))
        assertEquals(AgentQuestionStatus.Waiting, query(AgentRuntimeSessionRegistry(), AgentQuestionLedger(storage))!!.status)
        ledger.retireOwner(session.questionOwnerGeneration)
        assertEquals(AgentQuestionStatus.Interrupted, query(AgentRuntimeSessionRegistry(), AgentQuestionLedger(storage))!!.status)
        // Fresh synchronization domain simulates process death: only serialized ownership/status survive.
        val fresh = Storage().also { it.json = storage.json }
        assertEquals(AgentQuestionStatus.Interrupted, query(AgentRuntimeSessionRegistry(), AgentQuestionLedger(fresh))!!.status)
        assertNull(query(AgentRuntimeSessionRegistry(), AgentQuestionLedger(Storage())))
    }

    @Test fun exactFourIdentityAndReplacementCannotTakeOldQuestionOwnership() {
        val storage = Storage()
        val ledger = AgentQuestionLedger(storage)
        val registry = AgentRuntimeSessionRegistry()
        val old = AgentRuntimeSession("run", questionLedger = ledger)
        registry.put(old)
        AgentQuestionEventPublisher.publish(old, AgentEvent.QuestionRequested(request()))
        AgentQuestionEventPublisher.publish(old, resolved())
        val replacement = AgentRuntimeSession("run", questionLedger = AgentQuestionLedger(storage))
        assertSame(old, registry.put(replacement))
        assertFalse(registry.remove(old))
        fatal { AgentQuestionEventPublisher.publish(replacement, AgentEvent.QuestionRequested(request())) }
        assertEquals(answer, query(registry, ledger)!!.answer)
        old.complete(AgentRuntimeWire.RunResult("run", false, "", "replaced"))
        assertEquals(answer, query(registry, ledger)!!.answer)
        assertNull(AgentQuestionAuthority.query(registry, ledger, "other", "run", "q", "call"))
        assertNull(AgentQuestionAuthority.query(registry, ledger, "chat", "other", "q", "call"))
        assertNull(AgentQuestionAuthority.query(registry, ledger, "chat", "run", "other", "call"))
        assertNull(AgentQuestionAuthority.query(registry, ledger, "chat", "run", "q", "other"))
    }

    @Test fun requestedDuplicateDoesNotResetAnswerOrChangeOwnerAndOrphanDoesNotCreateEvidence() {
        val storage = Storage()
        val ledger = AgentQuestionLedger(storage)
        val session = AgentRuntimeSession("run", questionLedger = ledger)
        assertFalse(session.emit(resolved()))
        assertEquals(0, storage.writes)
        assertTrue(session.emit(AgentEvent.QuestionRequested(request())))
        assertTrue(session.emit(resolved()))
        assertTrue(session.emit(AgentEvent.QuestionRequested(request())))
        assertEquals(answer, session.questionSnapshot("chat", "q", "call")!!.answer)
        assertFalse(session.emit(AgentEvent.QuestionRequested(request().copy(toolCallId = "foreign"))))
        assertFalse(session.emit(AgentEvent.QuestionRequested(request().copy(conversationId = "foreign"))))
        assertFalse(session.emit(resolved(run = "foreign")))
        assertFalse(session.emit(resolved(q = "orphan")))
        assertEquals(answer, ledger.query("chat", "run", "q", "call")!!.answer)
    }

    @Test fun acceptedCorrectiveInterruptedOverridesAnsweredAndSealDoesNotRewriteCancellation() {
        val ledger = AgentQuestionLedger(Storage())
        val session = AgentRuntimeSession("run", questionLedger = ledger)
        AgentQuestionEventPublisher.publish(session, AgentEvent.QuestionRequested(request()))
        AgentQuestionEventPublisher.publish(session, resolved())
        AgentQuestionEventPublisher.publish(session, resolved(AgentQuestionStatus.Interrupted))
        AgentQuestionEventPublisher.publish(session, AgentEvent.QuestionRequested(request("q2")))
        assertTrue(session.requestStop())
        AgentQuestionEventPublisher.publish(session, resolved(AgentQuestionStatus.Cancelled, "q2"))
        session.complete(AgentRuntimeWire.RunResult("run", true, "done", null))
        assertEquals(AgentQuestionStatus.Interrupted, ledger.query("chat", "run", "q", "call")!!.status)
        assertNull(ledger.query("chat", "run", "q", "call")!!.answer)
        assertEquals(AgentQuestionStatus.Cancelled, ledger.query("chat", "run", "q2", "call")!!.status)
    }

    @Test fun lateResolutionIsFatalWithoutCheckpointOrLedgerPollution() {
        val storage = Storage()
        val ledger = AgentQuestionLedger(storage)
        val session = AgentRuntimeSession("run", questionLedger = ledger)
        AgentQuestionEventPublisher.publish(session, AgentEvent.QuestionRequested(request()))
        session.complete(AgentRuntimeWire.RunResult("run", false, "", "failed"))
        val durable = storage.json
        var checkpoints = 0
        fatal { AgentQuestionEventPublisher.publish(session, resolved()) { checkpoints++ } }
        assertEquals(0, checkpoints)
        assertEquals(durable, storage.json)
        assertEquals(AgentQuestionStatus.Interrupted, ledger.query("chat", "run", "q", "call")!!.status)
    }

    @Test fun missingCorruptAndReadFailureReturnUnknownWithoutOverwritingFile() {
        assertNull(query(AgentRuntimeSessionRegistry(), AgentQuestionLedger(Storage())))
        listOf("not json", "{}", "{\"version\":1,\"entries\":[{}]}", "{\"version\":1,\"entries\":[]} trailing").forEach { corrupt ->
            val storage = Storage().also { it.json = corrupt }
            val ledger = AgentQuestionLedger(storage)
            assertNull(query(AgentRuntimeSessionRegistry(), ledger))
            fatal { AgentRuntimeSession("run", questionLedger = ledger).emit(AgentEvent.QuestionRequested(request())) }
            assertEquals(corrupt, storage.json)
            assertEquals(0, storage.writes)
        }
        val storage = Storage().also { it.failRead = true }
        assertNull(query(AgentRuntimeSessionRegistry(), AgentQuestionLedger(storage)))
    }

    @Test fun failedWriteAndFailedAcceptedCheckpointNeverPublishOrInventTerminalEvidence() {
        val storage = Storage().also { it.failWrite = true }
        val ledger = AgentQuestionLedger(storage)
        var deliveries = 0
        val session = AgentRuntimeSession("run", questionLedger = ledger, eventSink = { deliveries++ })
        fatal { AgentQuestionEventPublisher.publish(session, AgentEvent.QuestionRequested(request())) }
        assertEquals(0, deliveries)
        assertNull(session.questionSnapshot("chat", "q", "call"))
        assertNull(ledger.query("chat", "run", "q", "call"))
        session.complete(AgentRuntimeWire.RunResult("run", true, "", null))
        assertFalse(session.terminalResult!!.ok)
        assertNull(ledger.query("chat", "run", "q", "call"))

        val secondLedger = AgentQuestionLedger(Storage())
        val second = AgentRuntimeSession("run", questionLedger = secondLedger)
        fatal { AgentQuestionEventPublisher.publish(second, AgentEvent.QuestionRequested(request())) { throw IOException("checkpoint") } }
        assertNull(second.questionSnapshot("chat", "q", "call"))
        assertNull(secondLedger.query("chat", "run", "q", "call"))
    }

    @Test fun failedTerminalWritePreventsSuccessCommitAndReturnsUnknown() {
        val storage = Storage()
        val ledger = AgentQuestionLedger(storage)
        val session = AgentRuntimeSession("run", questionLedger = ledger)
        session.emit(AgentEvent.QuestionRequested(request()))
        storage.failWrite = true
        var committed = false
        session.complete(AgentRuntimeWire.RunResult("run", true, "", null)) { committed = true }
        assertFalse(committed)
        assertFalse(session.terminalResult!!.ok)
        assertNull(session.questionSnapshot("chat", "q", "call"))
        assertNull(ledger.query("chat", "run", "q", "call"))
    }

    @Test fun durableWritePrecedesDispatchAndCoordinatorAnsweredCommit() {
        val storage = Storage()
        val ledger = AgentQuestionLedger(storage)
        lateinit var session: AgentRuntimeSession
        val controller = AgentRunController()
        session = AgentRuntimeSession("run", controller, questionLedger = ledger, eventSink = { event ->
            if (event is AgentEvent.QuestionRequested) {
                assertEquals(AgentQuestionStatus.Waiting, ledger.query("chat", "run", "q", "call")!!.status)
                session.questionCoordinator!!.submitAnswer("chat", "run", "q", "call", answer)
            } else if (event is AgentEvent.QuestionResolved) {
                assertEquals(answer, ledger.query("chat", "run", "q", "call")!!.answer)
            }
        })
        session.questionCoordinator = AgentQuestionCoordinator(controller) { AgentQuestionEventPublisher.publish(session, it) }
        assertEquals(answer, session.questionCoordinator!!.awaitAnswer(request()))
    }

    @Test fun blockedLedgerWriteDoesNotBlockStopSignalOrStateLockAndTerminalWaitsForAdmittedAnswer() {
        val storage = Storage()
        val ledger = AgentQuestionLedger(storage)
        val session = AgentRuntimeSession("run", questionLedger = ledger)
        session.emit(AgentEvent.QuestionRequested(request()))
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        storage.gate = { writing.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        val failure = AtomicReference<Throwable?>()
        val writer = thread { try { session.emit(resolved()) } catch (t: Throwable) { failure.set(t) } }
        assertTrue(writing.await(2, TimeUnit.SECONDS))
        session.signalStop()
        assertTrue(session.requestStop()) // Must not acquire a lock held by storage.write.
        assertFalse(session.isTerminal)
        val terminalStarted = CountDownLatch(1)
        val terminalDone = CountDownLatch(1)
        val terminal = thread {
            terminalStarted.countDown()
            session.complete(AgentRuntimeWire.RunResult("run", false, "", "stop"))
            terminalDone.countDown()
        }
        assertTrue(terminalStarted.await(2, TimeUnit.SECONDS))
        assertFalse(terminalDone.await(50, TimeUnit.MILLISECONDS))
        release.countDown()
        writer.join(2000); terminal.join(2000)
        assertFalse(writer.isAlive); assertFalse(terminal.isAlive)
        assertNull(failure.get())
        assertEquals(answer, ledger.query("chat", "run", "q", "call")!!.answer)
    }

    @Test fun independentStoreInstancesSerializeReadModifyWriteWithoutLosingOtherRun() {
        val storage = Storage()
        val first = AgentRuntimeSession("run", questionLedger = AgentQuestionLedger(storage))
        val second = AgentRuntimeSession("run2", questionLedger = AgentQuestionLedger(storage))
        val start = CountDownLatch(1)
        val errors = AtomicReference<Throwable?>()
        val workers = listOf(first to request(), second to request("q2", "run2")).map { (session, request) ->
            thread { try { start.await(); session.emit(AgentEvent.QuestionRequested(request)) } catch (t: Throwable) { errors.set(t) } }
        }
        start.countDown()
        workers.forEach { it.join(2000); assertFalse(it.isAlive) }
        assertNull(errors.get())
        val reader = AgentQuestionLedger(storage)
        assertNotNull(reader.query("chat", "run", "q", "call"))
        assertNotNull(reader.query("chat", "run2", "q2", "call"))
    }

    @Test fun ledgerEnabledCallbackCancelAndCompleteDoNotWaitForAdmittedChildCoordinator() {
        listOf(false, true).forEach { complete ->
            val ledger = AgentQuestionLedger(Storage())
            val coordinator = java.util.concurrent.locks.ReentrantLock()
            val childEntered = CountDownLatch(1)
            val callbackReturned = CountDownLatch(1)
            val order = java.util.Collections.synchronizedList(mutableListOf<String>())
            val failure = AtomicReference<Throwable?>()
            lateinit var session: AgentRuntimeSession
            session = AgentRuntimeSession("run", questionLedger = ledger, eventSink = {
                assertTrue(if (complete) session.complete(AgentRuntimeWire.RunResult("run", true, "done")) {
                    order += "commit"
                } else session.cancel("replaced"))
                assertFalse(session.isTerminal)
                assertFalse(session.requestCompact(childTaskId = "late"))
                order += "claim"
            }, resultSink = { order += "result" })
            // A real question admission callback, not just an ordinary replay event.
            session.childCompactor = { _, _, _ ->
                childEntered.countDown()
                check(coordinator.tryLock(3, TimeUnit.SECONDS))
                try { order += "child"; true } finally { coordinator.unlock() }
            }
            val child = thread(start = false, isDaemon = true) {
                try { assertTrue(session.requestCompact(childTaskId = "child")) }
                catch (t: Throwable) { failure.set(t) }
            }
            val callback = thread(isDaemon = true) {
                coordinator.lock()
                try {
                    child.start()
                    assertTrue(childEntered.await(2, TimeUnit.SECONDS))
                    assertTrue(session.emit(AgentEvent.QuestionRequested(request())))
                    order += "callback-return"
                    callbackReturned.countDown()
                } catch (t: Throwable) { failure.set(t) }
                finally { coordinator.unlock() }
            }
            assertTrue("Question callback waited for child coordinator", callbackReturned.await(2, TimeUnit.SECONDS))
            callback.join(4000); child.join(4000)
            assertFalse(callback.isAlive); assertFalse(child.isAlive)
            assertNull(failure.get())
            assertTrue(session.isTerminal)
            assertEquals(if (complete) listOf("claim", "callback-return", "child", "commit", "result")
                else listOf("claim", "callback-return", "child", "result"), order)
            assertEquals(AgentQuestionStatus.Interrupted, ledger.query("chat", "run", "q", "call")!!.status)
        }
    }

    @Test fun coordinatorCorrectiveInterruptedIsDurableInRealProductionGate() {
        val ledger = AgentQuestionLedger(Storage())
        val controller = AgentRunController()
        lateinit var session: AgentRuntimeSession
        session = AgentRuntimeSession("run", controller, questionLedger = ledger, eventSink = { event ->
            if (event is AgentEvent.QuestionRequested)
                session.questionCoordinator!!.submitAnswer("chat", "run", "q", "call", answer)
            if (event is AgentEvent.QuestionResolved && event.status == AgentQuestionStatus.Answered)
                Thread.currentThread().interrupt()
        })
        session.questionCoordinator = AgentQuestionCoordinator(controller) { AgentQuestionEventPublisher.publish(session, it) }
        try {
            assertNull(session.questionCoordinator!!.awaitAnswer(request()))
            assertTrue(Thread.currentThread().isInterrupted)
            val snapshot = ledger.query("chat", "run", "q", "call")!!
            assertEquals(AgentQuestionStatus.Interrupted, snapshot.status)
            assertNull(snapshot.answer)
        } finally { Thread.interrupted() }
    }

    @Test fun coordinatorResolutionWriteFailureDoesNotCommitReservedAnswer() {
        val storage = Storage()
        val ledger = AgentQuestionLedger(storage)
        val controller = AgentRunController()
        lateinit var session: AgentRuntimeSession
        session = AgentRuntimeSession("run", controller, questionLedger = ledger, eventSink = { event ->
            if (event is AgentEvent.QuestionRequested) {
                session.questionCoordinator!!.submitAnswer("chat", "run", "q", "call", answer)
                storage.failWrite = true
            }
        })
        session.questionCoordinator = AgentQuestionCoordinator(controller) { AgentQuestionEventPublisher.publish(session, it) }
        fatal { session.questionCoordinator!!.awaitAnswer(request()) }
        assertNull(ledger.query("chat", "run", "q", "call"))
        assertNull(session.questionSnapshot("chat", "q", "call"))
    }

    @Test fun committingRejectsLateResolutionBeforeAnyCheckpointOrDurableWrite() {
        val storage = Storage()
        val ledger = AgentQuestionLedger(storage)
        val deferred = mutableListOf<() -> Unit>()
        val session = AgentRuntimeSession("run", questionLedger = ledger, terminalWork = { deferred += it })
        session.emit(AgentEvent.QuestionRequested(request()))
        session.complete(AgentRuntimeWire.RunResult("run", true, "done"))
        val durable = storage.json
        var checkpoints = 0
        fatal { AgentQuestionEventPublisher.publish(session, resolved()) { checkpoints++ } }
        assertEquals(0, checkpoints)
        assertEquals(durable, storage.json)
        assertFalse(session.isTerminal)
        deferred.single().invoke()
        assertTrue(session.isTerminal)
        assertEquals(AgentQuestionStatus.Interrupted, ledger.query("chat", "run", "q", "call")!!.status)
    }

    @Test fun durableShapeMatchesCanonicalUnusedBlankFields() {
        assertTrue(validQuestionResolution(
            AgentQuestionStatus.Answered,
            AgentQuestionAnswer("option", "a", otherText = "   "),
        ))
        assertTrue(validQuestionResolution(
            AgentQuestionStatus.Answered,
            AgentQuestionAnswer("other", optionId = "   ", otherText = "custom"),
        ))
        assertTrue(validQuestionResolution(
            AgentQuestionStatus.Answered,
            AgentQuestionAnswer("delegate", optionId = "   ", otherText = "   "),
        ))
    }

    @Test fun ledgerIsBoundedWithoutEvictingLiveWaiting() {
        val storage = Storage()
        val ledger = AgentQuestionLedger(storage)
        val session = AgentRuntimeSession("run", questionLedger = ledger)
        repeat(AgentQuestionLedger.MAX_RECORDS) { session.emit(AgentEvent.QuestionRequested(request("q$it"))) }
        fatal { session.emit(AgentEvent.QuestionRequested(request("overflow"))) }
        assertNotNull(ledger.query("chat", "run", "q0", "call"))
        assertNull(ledger.query("chat", "run", "overflow", "call"))
    }
}
