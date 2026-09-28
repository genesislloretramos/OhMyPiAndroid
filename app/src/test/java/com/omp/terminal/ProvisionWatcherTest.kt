package com.omp.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The provisioning watcher cannot take the app down with it.
 *
 * ### Why this is a test and not a code review
 *
 * On Android an uncaught exception in **any** thread ends the process, and the loop this pins used
 * to have its only `try` around `Thread.sleep` — the two calls that can actually fail, reading the
 * current line and posting the notification, sat outside it. There is no symptom to notice on a
 * desk: the app is simply gone a few minutes into a 334 MB download, with the terminal the user was
 * watching going with it and nothing in the report about a notification.
 *
 * **Every test here fails the loop on purpose and asserts the loop came back.** The three things
 * the watcher touches are handed in rather than reached for, which is what makes that possible on a
 * JVM: a real [android.app.Service] cannot be built here, and neither can a real
 * `NotificationManager`, so a loop that closes over them is a loop whose failure modes nobody can
 * test. The reason it returns is the reason the caller can log it; an `Error` is deliberately not
 * caught, because an `OutOfMemoryError` or a `StackOverflowError` is not something this app has an
 * opinion about and swallowing it would be worse than the crash.
 */
class ProvisionWatcherTest {

    @Test
    fun aWatcherThatCannotPostTheNotificationEndsItsThreadAndSaysWhy() {
        // The step that failed on a real device: `show` threw, once, on the first line.
        val posted = AtomicInteger()
        val reason = watcher(
            next = { "provisioning: downloading omp-linux-arm64" },
            publish = { posted.incrementAndGet(); throw IllegalStateException("no such channel") },
        ).run()

        assertNotNull(
            "a watcher that swallowed the failure would have returned null, and the log would " +
                "have said nothing about a download that stopped showing progress",
            reason,
        )
        assertTrue(
            "the reason has to name what failed, and it says '$reason'",
            reason!!.contains("IllegalStateException") && reason.contains("no such channel"),
        )
        assertEquals("and it must not go on posting after it gave up", 1, posted.get())
    }

    @Test
    fun aWatcherWhoseReaderThrowsEndsItsThreadRatherThanTheProcess() {
        // The other unwrapped call: reading the holder's line. A corrupt or half-written record
        // must cost the progress line, not the app.
        val reason = ProvisionWatcher(
            watching = { true },
            nextLine = { throw IllegalArgumentException("unreadable record") },
            publish = { },
            sleep = { },
        ).run()

        assertNotNull("the reader's failure left the loop unhandled", reason)
        assertTrue("'$reason' does not name what failed", reason!!.contains("unreadable record"))
    }

    @Test
    fun anInterruptIsAShutdownAndNotAFailure() {
        val reason = ProvisionWatcher(
            watching = { true },
            nextLine = { "provisioning: verifying" },
            publish = { },
            sleep = { throw InterruptedException("onDestroy") },
        ).run()

        assertNull("a thread being shut down is not a fault, and must not be reported as one", reason)
        // The flag is restored so whoever interrupted this thread can see that it asked, and it is
        // cleared here so it does not leak into the next test on this same thread.
        assertTrue("the interrupt flag has to survive the catch", Thread.interrupted())
    }

    @Test
    fun aLineIsPostedOnceAndOnlyWhenItChanges() {
        // `omp provision` reports every 64 KiB; the notification is why this polls at all, and a
        // notification re-posted on every report is several updates a second for minutes.
        val posted = ArrayList<String>()
        var tick = 0
        ProvisionWatcher(
            watching = { tick < 4 },
            nextLine = { "provisioning: downloading — ${tick / 2 * 50}%" },
            publish = { posted.add(it) },
            sleep = { tick++ },
        ).run()

        assertEquals(
            "four passes over two distinct lines are two posts, not four",
            listOf("provisioning: downloading — 0%", "provisioning: downloading — 50%"),
            posted,
        )
    }

    @Test
    fun aWatcherThatWasNeverAskedForDoesNothingAtAll() {
        val posted = AtomicInteger()
        val slept = AtomicInteger()
        val reason = ProvisionWatcher(
            watching = { false },
            nextLine = { "provisioning: downloading" },
            publish = { posted.incrementAndGet() },
            sleep = { slept.incrementAndGet() },
        ).run()

        assertNull(reason)
        assertEquals("a loop nobody started must not sleep", 0, slept.get())
        assertEquals("nor post", 0, posted.get())
    }

    /** One line, unchanged, and a thread that is never asked to stop. */
    private fun watcher(next: () -> String?, publish: (String) -> Unit) = ProvisionWatcher(
        watching = { true },
        nextLine = next,
        publish = publish,
        sleep = { },
    )
}
