package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.question.*
import org.junit.Assert.*
import org.junit.Test

class AgentQuestionRuntimeSessionTest {
    private fun request() = AgentQuestionRequest("q", "chat", "run", "call", "title", "question",
        listOf(AgentQuestionOption("a", "A"), AgentQuestionOption("b", "B")))
    @Test fun stopStillPublishesAndReplaysQuestionResolution() {
        val events = mutableListOf<AgentEvent>()
        val session = AgentRuntimeSession("run", eventSink = events::add)
        assertTrue(session.emit(AgentEvent.QuestionRequested(request())))
        assertTrue(session.requestStop())
        val resolution = AgentEvent.QuestionResolved("q", "run", AgentQuestionStatus.Cancelled)
        assertTrue(session.emit(resolution))
        assertFalse(session.emit(AgentEvent.RoundStarted(2, 2)))
        val replay = mutableListOf<AgentEvent>()
        assertTrue(session.attach(replay::add, {}))
        assertEquals(listOf(AgentEvent.QuestionRequested(request()), resolution), replay)
        assertEquals(AgentQuestionStatus.Cancelled, session.questionSnapshot("chat", "q", "call")!!.status)
    }
    @Test fun questionSnapshotRequiresExactOwnershipAndPublishedResolution() {
        val session = AgentRuntimeSession("run")
        assertNull(session.questionSnapshot("chat", "q", "call"))
        session.emit(AgentEvent.QuestionRequested(request()))
        assertEquals(AgentQuestionStatus.Waiting, session.questionSnapshot("chat", "q", "call")!!.status)
        assertNull(session.questionSnapshot("other", "q", "call"))
        assertNull(session.questionSnapshot("chat", "other", "call"))
        assertNull(session.questionSnapshot("chat", "q", "other"))
        val answer = AgentQuestionAnswer("option", "a")
        session.emit(AgentEvent.QuestionResolved("q", "run", AgentQuestionStatus.Answered, answer))
        assertEquals(answer, session.questionSnapshot("chat", "q", "call")!!.answer)
    }
    @Test fun terminalSessionRejectsLateQuestionEvents() {
        val session = AgentRuntimeSession("run")
        session.emit(AgentEvent.QuestionRequested(request()))
        assertTrue(session.complete(AgentRuntimeWire.RunResult("run", false, "", "failed")))
        assertFalse(session.emit(AgentEvent.QuestionResolved("q", "run", AgentQuestionStatus.Answered, AgentQuestionAnswer("option", "a"))))
        assertEquals(AgentQuestionStatus.Interrupted, session.questionSnapshot("chat", "q", "call")!!.status)
    }
    @Test fun terminalSnapshotPreservesPublishedAnswerAndOwnership() {
        val session = AgentRuntimeSession("run")
        val answer = AgentQuestionAnswer("option", "a", note = "confirmed")
        assertTrue(session.emit(AgentEvent.QuestionRequested(request())))
        assertTrue(session.emit(AgentEvent.QuestionResolved("q", "run", AgentQuestionStatus.Answered, answer)))
        assertTrue(session.complete(AgentRuntimeWire.RunResult("run", true, "done", null)))
        val snapshot = session.questionSnapshot("chat", "q", "call")!!
        assertEquals(AgentQuestionStatus.Answered, snapshot.status)
        assertEquals(answer, snapshot.answer)
        assertNull(session.questionSnapshot("other", "q", "call"))
        assertNull(session.questionSnapshot("chat", "q", "other"))
        assertFalse(session.attach({}, {}))
    }
    @Test fun terminalSnapshotPreservesPublishedCancellation() {
        val session = AgentRuntimeSession("run")
        assertTrue(session.emit(AgentEvent.QuestionRequested(request())))
        assertTrue(session.requestStop())
        assertTrue(session.emit(AgentEvent.QuestionResolved("q", "run", AgentQuestionStatus.Cancelled)))
        assertTrue(session.complete(AgentRuntimeWire.RunResult("run", false, "", "stopped")))
        val snapshot = session.questionSnapshot("chat", "q", "call")!!
        assertEquals(AgentQuestionStatus.Cancelled, snapshot.status)
        assertNull(snapshot.answer)
    }
    @Test fun immediateCancelDoesNotDiscardUnresolvedQuestionIdentity() {
        val session = AgentRuntimeSession("run")
        assertTrue(session.emit(AgentEvent.QuestionRequested(request())))
        assertTrue(session.cancel("replaced"))
        assertEquals(AgentQuestionStatus.Interrupted, session.questionSnapshot("chat", "q", "call")!!.status)
        assertFalse(session.emit(AgentEvent.QuestionRequested(request().copy(questionId = "late"))))
        assertNull(session.questionSnapshot("chat", "late", "call"))
    }

}
