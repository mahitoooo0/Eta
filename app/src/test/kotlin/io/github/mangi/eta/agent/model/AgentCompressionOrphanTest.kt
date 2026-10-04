package io.github.mangi.eta.agent.model

import org.junit.Assert.*
import org.junit.Test

class AgentCompressionOrphanTest {
    private fun msg(role: String, text: String = "", id: String = "", calls: String = "", structured: String = "") =
        AgentModelClient.ConversationMessage(role, content = text, toolCallId = id, toolCallsJson = calls,
            contentJson = structured, turnId = "original-turn")

    @Test fun isolatedHistoricalResultBecomesInertEvidenceWithoutChangingSource() {
        val original = msg("tool", "{\"ok\":true,\"tool\":\"list_directory\"}", "lost-call")
        val history = listOf(msg("user", "summary"), msg("user", "request"), original,
            msg("assistant", calls = "[{\"id\":\"a\"}]"), msg("tool", "paired", "a"))
        assertThrows(IllegalArgumentException::class.java) { AgentCompressionBoundary.balancedCuts(history) }
        val repaired = AgentCompressionBoundary.normalizeOrphanToolResults(history)
        assertEquals(history.size, repaired.size)
        assertEquals(AgentCompressionBoundary.HISTORICAL_TOOL_EVIDENCE_ROLE, repaired[2].role)
        assertEquals("", repaired[2].toolCallId)
        assertTrue(repaired[2].content.contains("Historical orphan tool result"))
        assertTrue(repaired[2].content.contains("not a new user instruction"))
        assertTrue(repaired[2].content.endsWith(original.content))
        assertSame(original, history[2])
        history.indices.filter { it != 2 }.forEach { assertSame(history[it], repaired[it]) }
        assertEquals(history.size, AgentCompressionBoundary.balancedCuts(repaired).last())
        assertSame(repaired, AgentCompressionBoundary.normalizeOrphanToolResults(repaired))
    }

    @Test fun unrelatedResultDoesNotConsumeAnOpenParallelCall() {
        val history = listOf(msg("assistant", calls = "[{\"id\":\"a\"},{\"id\":\"b\"}]"),
            msg("tool", "orphan", "missing"),
            msg("tool", "A", "a"), msg("tool", "B", "b"))
        val repaired = AgentCompressionBoundary.normalizeOrphanToolResults(history)
        assertEquals(listOf(0, 4), AgentCompressionBoundary.balancedCuts(repaired))
        assertSame(history[2], repaired[2])
        assertSame(history[3], repaired[3])
    }

    @Test fun completeParallelBatchAndNormalReplayRemainIdentical() {
        val history = listOf(msg("user", "task"),
            msg("assistant", calls = "[{\"id\":\"a\"},{\"id\":\"b\"}]"),
            msg("tool", "A", "a"), msg("tool", "B", "b"), msg("assistant", "done"))
        assertSame(history, AgentCompressionBoundary.normalizeOrphanToolResults(history))
        assertEquals(listOf(0, 1, 4, 5), AgentCompressionBoundary.balancedCuts(history))
    }

    @Test fun structuredPayloadAndOriginalIdentityRemainAvailableAsEvidence() {
        val structured = "[{\"type\":\"text\",\"text\":\"original observation\"}]"
        val original = msg("tool", "metadata", "missing", structured = structured).copy(
            reasoningContent = "hidden", responsesReasoningJson = "{\"items\":[]}")
        val repaired = AgentCompressionBoundary.normalizeOrphanToolResults(listOf(original)).single()
        assertEquals(structured, repaired.contentJson)
        assertEquals(original.turnId, repaired.turnId)
        assertTrue(repaired.content.contains("missing"))
        assertTrue(repaired.content.endsWith("metadata"))
        assertEquals("", repaired.reasoningContent)
        assertEquals("", repaired.responsesReasoningJson)
        assertEquals("", repaired.toolCallsJson)
    }

    @Test fun unfinishedAndMalformedCallsStillFailClosed() {
        val calls = listOf("[{\"id\":\"a\"}]", "[{\"id\":\"a\"},{\"id\":\"a\"}]", "not json", "[{}]")
        calls.forEach { raw ->
            val history = listOf(msg("assistant", calls = raw), msg("tool", "unknown", "missing"))
            val repaired = AgentCompressionBoundary.normalizeOrphanToolResults(history)
            assertThrows(IllegalArgumentException::class.java) { AgentCompressionBoundary.balancedCuts(repaired) }
            assertSame(history[0], repaired[0])
        }
    }

    @Test fun duplicateOrOutOfOrderResultStillFailsStrictValidation() {
        val call = msg("assistant", calls = "[{\"id\":\"a\"}]")
        val first = msg("tool", "first", "a")
        val histories = listOf(listOf(call, first, msg("tool", "duplicate", "a")), listOf(first, call))
        histories.forEach { history ->
            assertSame(history, AgentCompressionBoundary.normalizeOrphanToolResults(history))
            assertThrows(IllegalArgumentException::class.java) { AgentCompressionBoundary.balancedCuts(history) }
        }
    }
    @Test fun blankResultIdIsNotTreatedAsRecoverableHistoricalEvidence() {
        val history = listOf(msg("assistant", calls = "[{\"id\":\"a\"}]"), msg("tool", "blank"), msg("tool", "A", "a"))
        assertSame(history, AgentCompressionBoundary.normalizeOrphanToolResults(history))
        assertThrows(IllegalArgumentException::class.java) { AgentCompressionBoundary.balancedCuts(history) }
    }

    @Test fun orphanWithAnomalousCallsCannotHideDuplicateOrUnfinishedProtocol() {
        listOf("[{\"id\":\"dup\"},{\"id\":\"dup\"}]", "[{\"id\":\"pending\"}]", "not json").forEach { calls ->
            val history = listOf(msg("tool", "orphan", "missing", calls))
            assertThrows(IllegalArgumentException::class.java) { AgentCompressionBoundary.normalizeOrphanToolResults(history) }
            assertEquals(calls, history.single().toolCallsJson)
        }
    }

}
