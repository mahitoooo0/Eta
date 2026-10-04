package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi

/**
 * Mark every ordinary message that already exists as "appeared", regardless of whether its
 * lazy row is currently mounted. LazyColumn only composes a row once it enters the viewport,
 * so latching "appeared" during that first composition misclassifies a pre-existing message
 * as new and plays the 180ms root fade when a work toggle first brings it into view.
 *
 * Call this before an explicit work toggle mutates the projection, from the data rather than
 * from what happens to be composed. A message created after the call is absent from both
 * sources and therefore keeps its entrance fade.
 */
internal fun markExistingMessages(
    appeared: MutableSet<String>,
    rows: List<AgentTimelineRow>,
    messages: List<AgentChatMessageUi>,
) {
    // Ordinary rows carry their message id as the row key.
    rows.forEach { row -> if (row is AgentTimelineRow.Message) appeared.add(row.key) }
    // Authoritative fallback: the raw message list still names a pre-existing message even
    // if a collapsed projection were ever to hide its row. Work ids are inert here because
    // the fade latch is only ever consulted for ordinary message rows.
    messages.forEach { appeared.add(it.id) }
}
