package com.omp.terminal

/**
 * The provisioning watcher's loop, with the three things it touches handed in from outside.
 *
 * **Why this is a class and not a `Thread { … }` body.** On Android an uncaught exception in
 * *any* thread ends the process, and the body this replaces had two calls outside its only
 * `try` — the reader for the current line and the notification post itself — so one bad read or
 * one refused `notify` took the whole app down at an arbitrary moment in a 334 MB download, with
 * a stack trace that says nothing about the terminal the user was watching. A loop that cannot be
 * handed anything is a loop whose failure modes cannot be tested, and this one is the difference
 * between "the download stopped showing progress" and "the phone restarted itself".
 *
 * **A failure of any step ends the loop, and the reason is returned rather than thrown.** The
 * caller logs it, which is what keeps `android.util.Log` out of a class a JVM test drives: a
 * static call into the platform's stub jar throws in a unit test, and a test that failed on its
 * own logging would have proved nothing about the loop. Continuing after a failure would mean
 * running the same line through the same broken call once a second for as long as the run lasts,
 * which is a notification that updates forever and says nothing new.
 *
 * **The interrupt is a different thing and keeps its own handling.** A watcher that is being shut
 * down is interrupted, and that is not a failure: the interrupt flag is restored, so whatever
 * parked the thread can see that it asked, and the loop returns without a complaint.
 *
 * **A line is published once, and only when it changes**, which is the whole of the reason this
 * polls at all: `omp provision` reports every 64 KiB, and a notification re-posted on every one
 * of those is several updates a second on a channel the user cannot silence.
 */
internal class ProvisionWatcher(
    /** Whether the service still wants this loop running; read once per pass. */
    private val watching: () -> Boolean,
    /** The line to show, or null when there is nothing to show. */
    private val nextLine: () -> String?,
    /** Posts [String] as the notification's progress line. */
    private val publish: (String) -> Unit,
    /** One polling interval. Throws [InterruptedException] when the thread is being shut down. */
    private val sleep: () -> Unit,
) {

    /**
     * Runs until the service stops watching, the thread is interrupted, or a step throws.
     *
     * **It returns a sentence and never throws**, and both halves of that are the point of this
     * class: a `run` that returns is a thread that ends, and a `run` that cannot throw is a
     * thread that cannot take the process down with it.
     *
     * @return null when the loop ended because it was asked to, or a sentence naming the step
     *   that failed. There is no third answer, and a caller that wants the stack trace has it in
     *   its own log already.
     */
    fun run(): String? {
        var shown: String? = null
        while (watching()) {
            try {
                sleep()
                if (!watching()) return null
                val line = nextLine() ?: continue
                if (line == shown) continue
                publish(line)
                shown = line
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (e: Exception) {
                return "the provisioning watcher stopped: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        return null
    }
}
