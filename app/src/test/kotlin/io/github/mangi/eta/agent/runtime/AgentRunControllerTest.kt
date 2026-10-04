package io.github.mangi.eta.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunControllerTest {
    @Test fun questionNotificationSurvivesSteeringAndPrecedesBlockingCleanup() {
        val controller = AgentRunController()
        val wake = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        controller.register { entered.countDown(); release.await() }
        controller.register(wakeBeforeCleanup = true) { wake.countDown() }
        controller.steer("guidance is not an answer or cancellation")
        assertEquals(1L, wake.count)
        val stopping = thread(isDaemon = true) { controller.cancel() }
        try {
            assertTrue(wake.await(1, TimeUnit.SECONDS))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
        } finally { release.countDown(); stopping.join(2_000) }
    }

    @Test fun cancellationInterruptsTransportBeforePotentiallyBlockingToolCleanup() {
        val controller = AgentRunController()
        val transportStopped = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val cleanupSawTransport = AtomicReference<Boolean>()
        // This is the runtime's registration order: the tool owner precedes the SSE binding.
        controller.register {
            cleanupSawTransport.set(transportStopped.count == 0L)
            cleanupEntered.countDown()
            releaseCleanup.await()
        }
        controller.register(interruptible = true) { transportStopped.countDown() }
        val stopping = thread(isDaemon = true) { controller.cancel() }
        try {
            assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS))
            assertTrue(controller.isCancelled)
            assertEquals(true, cleanupSawTransport.get())
            assertTrue(transportStopped.await(1, TimeUnit.SECONDS))
        } finally {
            releaseCleanup.countDown()
            stopping.join(2_000)
            controller.cancel()
        }
    }

    @Test fun budgetPauseWaitsOnlyAtWorkerCheckpoints() {
        val controller = AgentRunController()
        val interrupted = AtomicInteger()
        controller.register(interruptible = true) { interrupted.incrementAndGet() }
        controller.pauseAtCheckpoint()
        assertFalse(controller.isPaused)
        assertFalse(controller.hasPausedInterrupt)
        controller.withTransportCallback {
            controller.throwIfCancelled()
            controller.withTransportCallback { controller.throwIfCancelled() }
        }
        assertEquals(0, interrupted.get())
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try { controller.throwIfCancelled() } catch (t: Throwable) { failure.set(t) }
            finally { finished.countDown() }
        }
        try {
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            controller.resume()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertNull(failure.get())
        } finally { controller.cancel(); worker.join(2_000) }
    }

    @Test fun callbackScopeIsRestoredOnFailureAndCancellationStillWorks() {
        val controller = AgentRunController()
        controller.pauseAtCheckpoint()
        runCatching { controller.withTransportCallback<Unit> { error("test callback failure") } }
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try { controller.throwIfCancelled() } catch (t: Throwable) { failure.set(t) }
            finally { finished.countDown() }
        }
        try {
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            controller.cancel()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertTrue(failure.get() is AgentRunCancelledException)
            assertTrue(runCatching {
                controller.withTransportCallback { controller.throwIfCancelled() }
            }.exceptionOrNull() is AgentRunCancelledException)
        } finally { controller.cancel(); worker.join(2_000) }
    }

    @Test fun stoppedQueuedSupplementsAreRetainedOnceWithoutResuming() {
        val controller = AgentRunController()
        controller.steer("accepted but not consumed")
        controller.cancel()
        controller.cancel()
        assertFalse(controller.hasPendingSteering)
        assertEquals(listOf("accepted but not consumed"), controller.takeStoppedSteering().map { it.text })
        assertTrue(controller.takeStoppedSteering().isEmpty())
        assertFalse(controller.steer("late"))
    }

    @Test
    fun cancellationWakesLongRetryWait() {
        val controller = AgentRunController()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread {
            started.countDown()
            try {
                controller.awaitRetryDelay(60_000L)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            controller.cancel()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(failure.get() is AgentRunCancelledException)
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
    }

    @Test
    fun steeringIsQueuedOneAtATimeWithoutCancellingResources() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        controller.register { cancellations.incrementAndGet() }

        assertTrue(controller.steer("  first  "))
        assertFalse(controller.steer("   "))
        assertTrue(controller.steer("second"))

        assertEquals(0, cancellations.get())
        assertEquals("first", controller.pollSteeringMessage())
        assertEquals("second", controller.pollSteeringMessage())
        assertNull(controller.pollSteeringMessage())
    }

    @Test
    fun steeringCancelsOnlyInterruptibleResources() {
        val controller = AgentRunController()
        val durable = AtomicInteger(0)
        val stream = AtomicInteger(0)
        controller.register { durable.incrementAndGet() }
        controller.register(interruptible = true) { stream.incrementAndGet() }

        assertTrue(controller.steer("改短一点"))

        assertEquals(0, durable.get())
        assertEquals(1, stream.get())
        assertEquals("改短一点", controller.pollSteeringMessage())
    }

    @Test
    fun pauseCancelsInterruptibleResources() {
        val controller = AgentRunController()
        val stream = AtomicInteger(0)
        controller.register(interruptible = true) { stream.incrementAndGet() }
        controller.pause()

        assertEquals(1, stream.get())
        assertTrue(controller.hasPausedInterrupt)
        assertTrue(controller.consumePausedInterrupt())
        assertFalse(controller.hasPausedInterrupt)
    }

    @Test
    fun pausedSteeringDoesNotCancelInterruptibleResourcesAgain() {
        val controller = AgentRunController()
        val stream = AtomicInteger(0)
        controller.register(interruptible = true) { stream.incrementAndGet() }
        controller.pause()

        assertTrue(controller.steer("等我看完再说"))
        assertEquals(1, stream.get())
        assertEquals("等我看完再说", controller.pollSteeringMessage())
    }

    @Test
    fun finalPollAtomicallySealsSteering() {
        val controller = AgentRunController()

        assertNull(controller.pollSteeringOrSeal())
        assertFalse(controller.steer("too late"))
    }

    @Test
    fun cancelClearsSteeringAndCancelsEachResourceOnce() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        controller.register { cancellations.incrementAndGet() }
        controller.steer("pending")

        controller.cancel()
        controller.cancel()

        assertEquals(1, cancellations.get())
        assertFalse(controller.hasPendingSteering)
        assertFalse(controller.steer("late"))
    }

    @Test
    fun closedBindingIsNotCancelledLater() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        val binding = controller.register { cancellations.incrementAndGet() }

        binding.close()
        controller.cancel()

        assertEquals(0, cancellations.get())
    }

    @Test
    fun resourceRegisteredAfterCancellationIsCancelledExactlyOnce() {
        val controller = AgentRunController()
        val cancellations = AtomicInteger(0)
        controller.cancel()

        val binding = controller.register { cancellations.incrementAndGet() }
        controller.cancel()
        binding.close()

        assertEquals(1, cancellations.get())
    }

    @Test
    fun pauseBlocksUntilResume() {
        val controller = AgentRunController()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        controller.pause()
        val worker = thread(name = "controller-pause-test") {
            entered.countDown()
            runCatching(controller::throwIfCancelled).exceptionOrNull()?.let(failure::set)
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            controller.resume()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertNull(failure.get())
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
        assertFalse(worker.isAlive)
    }

    @Test
    fun steeringResumesAPausedRun() {
        val controller = AgentRunController()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        controller.pause()
        val worker = thread(name = "controller-paused-steering-test", isDaemon = true) {
            entered.countDown()
            runCatching(controller::throwIfCancelled)
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(controller.steer("补充条件"))
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertEquals("补充条件", controller.pollSteeringMessage())
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
    }

    @Test
    fun requestCompactResumesAPausedRunAtSafeBoundary() {
        val controller = AgentRunController()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        controller.pause()
        val worker = thread(name = "controller-paused-compact-test", isDaemon = true) {
            entered.countDown()
            runCatching(controller::throwIfCancelled)
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(controller.requestCompact(keepRecentMessages = 2))
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(controller.hasPendingCompact)
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
    }

    @Test
    fun defaultCompactRequestResumesAPausedRun() {
        val controller = AgentRunController()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        controller.pause()
        val worker = thread(name = "controller-budget-compact-test", isDaemon = true) {
            entered.countDown()
            runCatching(controller::throwIfCancelled)
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(controller.requestCompact())
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(controller.hasPendingCompact)
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
    }

    @Test
    fun cancelWhilePausedWakesWorkerWithCancellation() {
        val controller = AgentRunController()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        controller.pause()
        val worker = thread(name = "controller-cancel-test") {
            entered.countDown()
            runCatching(controller::throwIfCancelled).exceptionOrNull()?.let(failure::set)
            finished.countDown()
        }

        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            controller.cancel()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(failure.get() is AgentRunCancelledException)
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
        assertFalse(worker.isAlive)
    }

    @Test
    fun requestCompactQueuesWithoutCancellingAnyResources() {
        val controller = AgentRunController()
        val durable = AtomicInteger(0)
        val stream = AtomicInteger(0)
        controller.register { durable.incrementAndGet() }
        controller.register(interruptible = true) { stream.incrementAndGet() }

        assertTrue(controller.requestCompact(keepRecentMessages = 2))

        assertEquals(0, durable.get())
        assertEquals(0, stream.get())
        val request = controller.takePendingCompact()
        assertEquals(2, request?.keepRecentMessages)
        assertFalse(controller.hasPendingCompact)
    }

    @Test fun cancelledOrSealedRunCannotAcceptMaintenanceAndCancelClearsQueue() {
        val controller = AgentRunController()
        assertTrue(controller.requestCompact())
        controller.cancel()
        assertFalse(controller.hasPendingCompact)
        assertFalse(controller.requestCompact())
        val sealed = AgentRunController()
        assertNull(sealed.pollSteeringOrSeal())
        assertFalse(sealed.requestCompact())
    }

    @Test
    fun requestCompactDoesNotSealSteering() {
        val controller = AgentRunController()
        assertTrue(controller.requestCompact())
        assertNull(controller.pollSteeringOrSeal())
        assertTrue(controller.steer("还能追加"))
        assertEquals("还能追加", controller.pollSteeringMessage())
    }
}
