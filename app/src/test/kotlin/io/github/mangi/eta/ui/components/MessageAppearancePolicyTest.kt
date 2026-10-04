package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The message-appearance latch must be seeded from data, not from lazy composition. Pure
 * policy: no Compose clock, layout or Android runtime required.
 */
class MessageAppearancePolicyTest {
    private fun tool(id: String) = ToolActivityMessageUi(
        id = id, toolName = "terminal", status = ToolActivityStatusUi.Success,
        argumentsSummary = id, command = id, resultSummary = id,
    )

    @Test
    fun existingAnswerIsMarkedBeforeTheToggleEvenWhenItsRowWasNeverMounted() {
        val appeared = HashSet<String>()
        // Only the collapsed work header is "mounted"; the answer row below is projected but
        // has not entered the lazy viewport yet.
        val rows: List<AgentTimelineRow> = listOf(
            AgentTimelineRow.WorkHeader(
                group = AgentTimelineEntry.WorkProcess("work-a", listOf(tool("a"))),
                expanded = false,
            ),
        )
        val messages: List<AgentChatMessageUi> = listOf(tool("a"), AgentMessageUi("answer", "existing"))

        markExistingMessages(appeared, rows, messages)

        assertTrue("pre-existing answer must be marked before the toggle", "answer" in appeared)
    }

    @Test
    fun ordinaryProjectedRowKeyIsMarkedFromTheRowListAlone() {
        val appeared = HashSet<String>()
        val rows = listOf(AgentTimelineRow.Message(AgentMessageUi("answer", "existing")))

        markExistingMessages(appeared, rows, emptyList())

        assertTrue(appeared.contains("answer"))
    }

    @Test
    fun messageCreatedAfterTheToggleStaysUnmarkedAndKeepsItsFade() {
        val appeared = HashSet<String>()
        markExistingMessages(appeared, emptyList(), listOf(AgentMessageUi("old", "existing")))

        assertTrue("already existing message is retired", "old" in appeared)
        assertFalse("a later message must still fade on first appearance", "new" in appeared)
    }

    @Test
    fun repeatedTogglesNeverUnmarkAnAlreadyAppearedMessage() {
        val appeared = HashSet<String>()
        val messages: List<AgentChatMessageUi> = listOf(AgentMessageUi("answer", "existing"))

        repeat(4) { markExistingMessages(appeared, emptyList(), messages) }

        assertEquals(setOf("answer"), appeared)
    }

    @Test
    fun markedSetIsTheUnionOfProjectedRowsAndTheRawMessageList() {
        val appeared = HashSet<String>()
        val rows: List<AgentTimelineRow> = listOf(
            AgentTimelineRow.Message(AgentMessageUi("row-only", "a")),
            AgentTimelineRow.WorkStep("work-a", tool("a"), isFirst = true, isLast = true),
        )
        val messages: List<AgentChatMessageUi> = listOf(
            AgentMessageUi("row-only", "a"),
            AgentMessageUi("list-only", "b"),
        )

        markExistingMessages(appeared, rows, messages)

        assertEquals(setOf("row-only", "list-only"), appeared)
    }
}
