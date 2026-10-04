package io.github.mangi.eta.ui.components

/**
 * A click owns one short-lived entrance cohort, not a time window in which a
 * scrolled-in row can replay an old click. Capping zero-height entrants also
 * stops lazy measurement from composing an arbitrarily large expanded group.
 * No Compose state or clocks: admission is consumed once per stable row key.
 */
internal class WorkStepEntranceAdmission(private val limit: Int = 32) {
    private var open = true
    private val admitted = HashSet<String>()

    fun claim(key: String): Boolean = open && admitted.size < limit && admitted.add(key)

    fun seal() { open = false }
}

/** Target and visual lifetime are separate. Only mounted exits wait for completion. */
internal data class WorkGroupAnimation(
    val generation: Long,
    val expanded: Boolean,
    val fromBottom: Boolean,
    val retainedStepKeys: Set<String>,
    val pendingExitKeys: Set<String>,
    val entrance: WorkStepEntranceAdmission = WorkStepEntranceAdmission(),
) {
    /** Stale completions from a previous click cannot retire a reversed transition. */
    fun finishExit(key: String, expectedGeneration: Long): WorkGroupAnimation? {
        if (expanded || generation != expectedGeneration || key !in pendingExitKeys) return this
        val pending = pendingExitKeys - key
        return if (pending.isEmpty()) null else copy(pendingExitKeys = pending)
    }
}

internal fun newWorkGroupAnimation(
    generation: Long,
    expanded: Boolean,
    fromBottom: Boolean,
    stepKeys: List<String>,
    mountedStepKeys: Set<String>,
): WorkGroupAnimation {
    val pending = if (expanded) emptySet() else mountedStepKeys.intersect(stepKeys.toSet())
    // Keep the actual last key even if it was virtualized: it owns the card's
    // constant bottom gap and possibly the turn footer, not an offscreen exit.
    val retained = if (expanded || pending.isEmpty()) emptySet() else
        pending + listOfNotNull(stepKeys.lastOrNull())
    return WorkGroupAnimation(generation, expanded, fromBottom, retained, pending)
}
