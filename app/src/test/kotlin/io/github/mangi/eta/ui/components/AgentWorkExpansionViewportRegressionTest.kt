package io.github.mangi.eta.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Production recovery + flat LazyColumn rows + actual step-height transition.
 * The fixture has an upper group, an existing answer and four lower step rows.
 * Immutable graphics layers record the FIRST actual draw for each manual frame.
 * No follow coroutine or test-owned scrolling is allowed after an expansion.
 * The step entrance/exit uses production's [WorkStepEntranceAdmission] cohort and
 * [tailDetailsExit], not a test-only clock window or an emptied exit transition. This fixture
 * only expands, so the collapse/exit path itself is still not exercised here; the
 * never-mounted answer case is covered by `MessageAppearancePolicyTest`.
 * Written but intentionally not executed until Android compilation is authorized.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentWorkExpansionViewportRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val expanded = mutableStateOf(false)
    private val owner = mutableStateOf(true)
    private val pointer = mutableStateOf(false)
    // Production entry point for the explicit toggle: one bounded entrance cohort plus a
    // real shrink exit. The fixture must not substitute a test-only clock window.
    private val workAnimation = mutableStateOf<WorkGroupAnimation?>(null)
    private var revision by mutableIntStateOf(0)
    private var expectedCount = BASE_COUNT
    private var stepCount = 18
    private var enableRecovery = true
    private var streaming = false
    private var consumed = 0f
    private lateinit var state: LazyListState
    private lateinit var scope: CoroutineScope
    private lateinit var recovery: BottomFollowViewportRecovery
    private val probes = mutableMapOf<Int, Probe>()
    private val images = mutableMapOf<Int, Bitmap>()
    private data class Probe(val answerTop: Int?, val markerBottom: Int?, val consumed: Float)

    @Test fun eighteenInsertedStepsKeepLowerContentOnEveryFirstDraw() = checkExpansion(18, false)
    @Test fun thirtyTwoInsertedStepsKeepLowerContentOnEveryFirstDraw() = checkExpansion(32, false)
    @Test fun streamingExpansionAlsoKeepsExistingLowerContentOnFirstDraw() = checkExpansion(32, true)

    @Test fun oldUnknownTailPathIsANegativeControlNotAnEventualIdleAssertion() {
        setup(32, recoveryEnabled = false)
        captureExpansion()
        assertTrue("negative control must observe the original displacement",
            probes.values.any { it.answerTop == null || abs(it.answerTop - ANSWER_TOP) > 2 })
        assertTrue("negative control must lose the visible lower marker on at least one frame",
            images.values.any { markerPixels(it) == 0 })
        assertEquals(0f, consumed, 0f)
    }

    @Test fun historyAndPointerOwnersAuthorizeNoRecoveryScroll() {
        setup(18)
        compose.runOnIdle { owner.value = false }
        assertFalse(begin())
        captureExpansion(captureOwner = false)
        assertEquals(0f, consumed, 0f)
    }

    @Test fun pointerInterruptAndSameGroupCollapseDiscardTheExpansionOwner() {
        setup(18)
        assertTrue(begin())
        compose.runOnIdle { pointer.value = true }
        captureExpansion(captureOwner = false)
        assertEquals(0f, consumed, 0f)
        compose.runOnIdle {
            recovery.cancelWorkExpansion(UPPER)
            expanded.value = false
            expectedCount = BASE_COUNT
            revision++
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(0f, consumed, 0f)
    }

    @Test fun expiredSecondCaptureCannotReuseTheFirstGroupsAnchor() {
        setup(18)
        assertTrue(begin())
        compose.runOnIdle {
            assertFalse(recovery.beginWorkExpansion(UPPER,
                (0 until stepCount).mapTo(HashSet<Any>()) { "work-step:upper-$it" },
                expiresAtNanos = 0L, canOwnViewport = true))
        }
        captureExpansion(captureOwner = false)
        assertEquals(0f, consumed, 0f)
    }

    @Test fun rejectedSecondCaptureCannotReuseTheFirstGroupsAnchor() {
        setup(18)
        assertTrue(begin())
        compose.runOnIdle {
            assertFalse(recovery.beginWorkExpansion("missing-second-group", setOf("missing-step"),
                Long.MAX_VALUE, canOwnViewport = true))
        }
        captureExpansion(captureOwner = false)
        assertEquals("no raw scroll from the discarded first-group owner", 0f, consumed, 0f)
    }

    private fun checkExpansion(count: Int, follow: Boolean) {
        setup(count, follow = follow)
        captureExpansion()
        assertTrue("actual first-frame records must exist", probes.size >= 10)
        probes.forEach { (frame, sample) ->
            assertNotNull("answer virtualized at first draw frame=$frame", sample.answerTop)
            assertTrue("answer moved on first draw frame=$frame probe=$sample",
                abs(checkNotNull(sample.answerTop) - ANSWER_TOP) <= 2)
            assertTrue("existing answer root replayed opacity on frame=$frame",
                coloredPixels(checkNotNull(images[frame]), ANSWER_COLOR) >= WIDTH * 78)
            assertTrue("lower marker hidden at first draw frame=$frame probe=$sample",
                markerPixels(checkNotNull(images[frame])) >= WIDTH * MARKER_HEIGHT - 2 * WIDTH)
            assertTrue("lower marker fell behind input frame=$frame probe=$sample",
                sample.markerBottom != null && abs(sample.markerBottom - (REST - 1)) <= 2)
        }
        assertTrue("expanded rows require actual recovery consumption", consumed > 0f)
    }

    private fun setup(count: Int, recoveryEnabled: Boolean = true, follow: Boolean = false) {
        stepCount = count
        enableRecovery = recoveryEnabled
        streaming = follow
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                state = rememberLazyListState()
                scope = rememberCoroutineScope()
                recovery = remember(state) { BottomFollowViewportRecovery(state, SENTINEL) }
                val heldTailLift = remember { intArrayOf(0) }
                // Separate immutable layer per inspected frame, matching existing first-draw tests.
                val snapshots = List(20) { rememberGraphicsLayer() }
                val appearedMessageKeys = remember { HashSet<String>() }
                // Production seals the entrance cohort after the first lazy measurement
                // frame, so later virtualized rows cannot replay the click's animation.
                LaunchedEffect(workAnimation.value?.generation) {
                    withFrameNanos { }
                    workAnimation.value?.entrance?.seal()
                }
                Box(Modifier.size(WIDTH.dp, HEIGHT.dp).testTag(ROOT).drawWithContent {
                    val rev = revision
                    if (rev > 0 && probes[rev] == null && state.layoutInfo.totalItemsCount == expectedCount) {
                        val layer = snapshots[rev]
                        layer.record {
                            drawRect(BG)
                            this@drawWithContent.drawContent()
                            drawRect(INPUT, Offset(0f, REST.toFloat()), Size(size.width, PAD.toFloat()))
                        }
                        drawLayer(layer)
                        val items = state.layoutInfo.visibleItemsInfo
                        probes[rev] = Probe(
                            items.firstOrNull { it.key == ANSWER }?.offset,
                            items.firstOrNull { it.key == LAST_LOWER }?.let { it.offset + it.size },
                            consumed,
                        )
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            images[rev] = layer.toImageBitmap().asAndroidBitmap()
                                .copy(Bitmap.Config.ARGB_8888, false)
                        }
                    } else {
                        drawRect(BG)
                        drawContent()
                        drawRect(INPUT, Offset(0f, REST.toFloat()), Size(size.width, PAD.toFloat()))
                    }
                }) {
                    Box(Modifier.fillMaxSize().clipToBounds().drawWithContent {
                        if (streaming) clipRect(bottom = REST.toFloat()) {
                            this@drawWithContent.drawContent()
                        } else drawContent()
                    }) {
                        LazyColumn(
                            state = state,
                            contentPadding = PaddingValues(bottom = PAD.dp),
                            overscrollEffect = null,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                val info = state.layoutInfo
                                val overflow = info.measuredBottomFollowTailOverflow(SENTINEL)
                                    ?.coerceAtMost(info.afterContentPadding)
                                heldTailLift[0] = nextHeldTailLift(
                                    streaming && owner.value && !pointer.value, overflow, heldTailLift[0],
                                )
                                translationY = -heldTailLift[0].toFloat()
                            }.onGloballyPositioned {
                                if (enableRecovery) consumed += recovery.recover(
                                    canRecoverExpansion = { owner.value && !pointer.value },
                                    canRecover = { streaming && owner.value && !pointer.value },
                                )
                            },
                        ) {
                            items(20, key = { "filler-$it" }) {
                                Box(Modifier.fillMaxWidth().height(40.dp).background(Color.Gray))
                            }
                            item(key = UPPER) {
                                Box(Modifier.fillMaxWidth().height(36.dp).background(Color.DarkGray))
                            }
                            if (expanded.value) items(stepCount, key = { "work-step:upper-$it" }) { index ->
                                val key = "work-step:upper-$index"
                                val animation = workAnimation.value
                                // Production admission/exit entry points: one bounded cohort per
                                // toggle and a real shrink exit, not a test-only 500ms clock window
                                // or an emptied exit transition.
                                val appear = remember(key) {
                                    val animate = animation?.expanded == true &&
                                        animation.entrance.claim(key)
                                    MutableTransitionState(animation?.expanded == true && !animate)
                                }
                                SideEffect { appear.targetState = animation?.expanded == true }
                                val fromBottom = animation?.fromBottom ?: false
                                AnimatedVisibility(
                                    visibleState = appear,
                                    enter = tailDetailsEnter(fromBottom = fromBottom),
                                    exit = tailDetailsExit(toBottom = fromBottom),
                                ) {
                                    Box(Modifier.fillMaxWidth().height(44.dp).background(Color.LightGray))
                                }
                            }
                            item(key = ANSWER) {
                                // Mirrors production's row-level latch: the answer is already in
                                // the appeared set, so the toggle recomposition cannot replay the
                                // 180ms root fade. The never-mounted case is covered by
                                // MessageAppearancePolicyTest.
                                val firstAppearance = remember(ANSWER, expanded.value) {
                                    appearedMessageKeys.add(ANSWER)
                                }
                                Box(Modifier.fillMaxWidth().height(80.dp)
                                    .animateItem(
                                        fadeInSpec = if (firstAppearance) tween(180) else null,
                                        placementSpec = null, fadeOutSpec = null,
                                    ).background(ANSWER_COLOR))
                            }
                            item(key = LOWER) {
                                Box(Modifier.fillMaxWidth().height(36.dp).background(Color.DarkGray))
                            }
                            items(4, key = { "work-step:lower-$it" }) { index ->
                                Column(Modifier.fillMaxWidth().height(20.dp).background(Color.Gray)) {
                                    if (index == 3) {
                                        Spacer(Modifier.weight(1f))
                                        Box(Modifier.fillMaxWidth().height(MARKER_HEIGHT.dp).background(MARKER))
                                    }
                                }
                            }
                            item(key = SENTINEL) { Spacer(Modifier.height(1.dp)) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        var positioned = false
        compose.runOnIdle { scope.launch { state.scrollBy(100_000f); positioned = true } }
        compose.waitUntil(5_000) { positioned }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(ANSWER_TOP, state.layoutInfo.visibleItemsInfo.first { it.key == ANSWER }.offset)
            assertEquals(0, state.layoutInfo.measuredBottomFollowTailOverflow(SENTINEL))
        }
        compose.mainClock.autoAdvance = false
    }

    private fun begin(): Boolean {
        var captured = false
        compose.runOnIdle {
            captured = recovery.beginWorkExpansion(UPPER,
                (0 until stepCount).mapTo(HashSet<Any>()) { "work-step:upper-$it" },
                expiresAtNanos = Long.MAX_VALUE, canOwnViewport = owner.value && !pointer.value)
        }
        return captured
    }

    private fun captureExpansion(captureOwner: Boolean = true) {
        if (captureOwner && enableRecovery) assertTrue("pre-click stable row capture", begin())
        compose.runOnIdle {
            expectedCount = BASE_COUNT + stepCount
            // Production entry point for this explicit toggle: one bounded entrance cohort.
            workAnimation.value = newWorkGroupAnimation(
                generation = 1L,
                expanded = true,
                fromBottom = true,
                stepKeys = (0 until stepCount).map { "work-step:upper-$it" },
                mountedStepKeys = emptySet(),
            )
            expanded.value = true
            revision++
        }
        repeat(14) { frame ->
            if (frame != 0) compose.runOnIdle { revision++ }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            // Pump a real host draw with clock still frozen; discard its eventual image.
            compose.onNodeWithTag(ROOT).captureToImage()
            val rev = revision
            compose.waitUntil(5_000) { probes.containsKey(rev) && images.containsKey(rev) }
        }
    }

    private fun markerPixels(image: Bitmap) = coloredPixels(image, MARKER)

    private fun coloredPixels(image: Bitmap, expected: Color): Int {
        val color = expected.toArgb()
        return (0 until REST).sumOf { y ->
            (0 until image.width).count { x -> image.getPixel(x, y) == color }
        }
    }

    private companion object {
        const val ROOT = "work-expansion-first-draw"
        const val UPPER = "work-upper"
        const val ANSWER = "existing-answer"
        const val LOWER = "work-lower"
        const val LAST_LOWER = "work-step:lower-3"
        const val SENTINEL = "bottom"
        const val WIDTH = 240
        const val HEIGHT = 600
        const val PAD = 142
        const val REST = HEIGHT - PAD
        // 80px answer + 36px lower header + 4*20px lower steps + 1px sentinel.
        const val ANSWER_TOP = REST - 197
        const val BASE_COUNT = 20 + 1 + 1 + 1 + 4 + 1
        const val MARKER_HEIGHT = 8
        val ANSWER_COLOR = Color(0xffc0e0f0)
        val BG = Color.White
        val INPUT = Color(0xfff0f0f0)
        val MARKER = Color(0xffe00050)
    }
}
