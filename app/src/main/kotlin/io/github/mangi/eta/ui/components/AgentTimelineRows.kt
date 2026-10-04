package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.isResumeAfterCompress

/** Flat lazy-list rows: expanding a group must not eagerly compose every detail. */
internal sealed interface AgentTimelineRow {
    val key: String
    data class Message(val message: AgentChatMessageUi) : AgentTimelineRow {
        override val key: String get() = message.id
    }
    data class WorkHeader(val group: AgentTimelineEntry.WorkProcess, val expanded: Boolean) : AgentTimelineRow {
        override val key: String get() = group.key
    }
    data class WorkStep(
        val groupKey: String,
        val message: AgentChatMessageUi,
        val isFirst: Boolean,
        val isLast: Boolean,
        val expanded: Boolean = true,
    ) : AgentTimelineRow {
        override val key: String get() = "work-step:${message.id}"
    }
}

internal fun List<AgentTimelineEntry>.toLazyTimelineRows(
    expandedOverrides: Map<String, Boolean>,
    isStreaming: Boolean,
    retainedSteps: Map<String, Set<String>> = emptyMap(),
): List<AgentTimelineRow> = buildList {
    val trailingWorkKey = (this@toLazyTimelineRows.lastOrNull() as? AgentTimelineEntry.WorkProcess)?.key
    this@toLazyTimelineRows.forEach { entry ->
        when (entry) {
            is AgentTimelineEntry.Message -> add(AgentTimelineRow.Message(entry.message))
            is AgentTimelineEntry.WorkProcess -> {
                val running = entry.messages.any { message ->
                    (message is ThinkingMessageUi && message.isStreaming) ||
                        (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running)
                }
                val expanded = expandedOverrides[entry.key] ?: (running || (isStreaming && entry.key == trailingWorkKey))
                add(AgentTimelineRow.WorkHeader(entry, expanded))
                // During exit only retained rows are projected. A newly appended
                // hidden step (or a deleted old tail) must not steal the card bottom.
                val projectedMessages = if (expanded) entry.messages else entry.messages.filter { message ->
                    "work-step:${message.id}" in retainedSteps[entry.key].orEmpty()
                }
                projectedMessages.forEachIndexed { index, message ->
                    add(AgentTimelineRow.WorkStep(entry.key, message,
                        isFirst = message.id == entry.messages.firstOrNull()?.id,
                        isLast = index == projectedMessages.lastIndex,
                        expanded = expanded))
                }
            }
        }
    }
}

internal fun List<AgentTimelineRow>.lazyUserMessageIndices(): List<Int> = mapIndexedNotNull { index, row ->
    val user = (row as? AgentTimelineRow.Message)?.message as? UserMessageUi
    index.takeIf { user != null && !user.isResumeAfterCompress() }
}

internal fun AgentTimelineRow.containsMessageId(id: String): Boolean = when (this) {
    is AgentTimelineRow.Message -> message.id == id
    is AgentTimelineRow.WorkHeader -> key == id || (!expanded && group.messages.any { it.id == id })
    is AgentTimelineRow.WorkStep -> message.id == id
}

/** Terminal notices must not hide an assistant that is still revealing text. */
internal fun hasPendingAssistantReveal(
    messages: List<AgentChatMessageUi>,
    isPending: (AgentMessageUi) -> Boolean,
): Boolean = (messages.lastOrNull { it is AgentMessageUi && it.content.isNotBlank() }
    as? AgentMessageUi)?.let(isPending) == true
