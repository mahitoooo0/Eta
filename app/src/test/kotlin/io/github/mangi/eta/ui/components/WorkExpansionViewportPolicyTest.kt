package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure evidence/ownership policy; no Compose clock, guessed row heights or scrolling. */
class WorkExpansionViewportPolicyTest {
    @Test fun measuredStableKeyPreservesItsPreClickOffset() {
        assertEquals(126f, resolveWorkExpansionViewportStep(310, 436, null), 0f)
        assertEquals(0f, resolveWorkExpansionViewportStep(310, 310, null), 0f)
    }

    @Test fun missingStableKeyUsesOnlyMeasuredPrecedingLowerBound() {
        // An inserted step bottom at 450 proves the old anchor is at least there.
        assertEquals(140f, resolveWorkExpansionViewportStep(310, null, 450), 0f)
        // After remeasurement the real key, not the lower bound, decides the next step.
        assertEquals(27f, resolveWorkExpansionViewportStep(310, 337, 330), 0f)
    }

    @Test fun unknownTailWithoutPreClickKeyEvidenceNeverAuthorizesDistance() {
        assertEquals(0f, resolveWorkExpansionViewportStep(310, null, null), 0f)
        assertEquals(0f, resolveBottomFollowViewportStep(0f, null, 142), 0f)
    }

    @Test fun precedingRowsStillAboveTheOldAnchorAuthorizeNoScroll() {
        assertEquals(0f, resolveWorkExpansionViewportStep(310, null, 270), 0f)
        assertEquals(0f, resolveWorkExpansionViewportStep(310, null, 310), 0f)
    }

    @Test fun measuredAnchorOverridesWeakerPrecedingEvidenceAndNeverScrollsBackward() {
        assertEquals(0f, resolveWorkExpansionViewportStep(310, 290, 400), 0f)
        assertEquals(10f, resolveWorkExpansionViewportStep(310, 320, 400), 0f)
    }

    @Test fun negativeViewportOffsetsAndLargeCoordinatesDoNotOverflow() {
        assertEquals(40f, resolveWorkExpansionViewportStep(-24, 16, null), 0f)
        assertEquals(4_294_967_295L.toFloat(),
            resolveWorkExpansionViewportStep(Int.MIN_VALUE, Int.MAX_VALUE, null), 0f)
    }

    @Test fun existingBufferPolicyStaysSmoothWithoutExplicitExpansionEvidence() {
        assertEquals(3f, resolveBottomFollowViewportStep(3f, 140, 142), 0f)
        assertEquals(278f, resolveBottomFollowViewportStep(3f, 420, 142), 0f)
    }

    @Test fun idleAnchoredOwnershipNeedsNoStreamingGate() {
        assertTrue(owns())
        assertFalse(owns(anchored = false))
        assertFalse(owns(initial = true))
    }

    @Test fun pointerDragFlingAndNavigationEachTakePriority() {
        assertFalse(owns(pointer = true))
        assertFalse(owns(dragging = true))
        assertFalse(owns(scrolling = true))
        assertFalse(owns(navigation = true))
    }

    private fun owns(
        anchored: Boolean = true,
        initial: Boolean = false,
        pointer: Boolean = false,
        dragging: Boolean = false,
        scrolling: Boolean = false,
        navigation: Boolean = false,
    ): Boolean = resolveWorkExpansionViewportOwnership(
        keepBottomAnchored = anchored,
        initialBottomPositionPending = initial,
        pointerDown = pointer,
        isUserDragging = dragging,
        isUserScrolling = scrolling,
        navigationActive = navigation,
    )
}
