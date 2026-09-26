package omp.shell.exec

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One pipeline's worth of work: every stage runs on its own daemon thread, and Ctrl-C sets
 * [cancelled] on the foreground group only.
 */
class JobGroup(
    val pid: Int,
    val argv: List<String>,
    val background: Boolean,
) {
    val cancelled = AtomicBoolean(false)
    val finished = CountDownLatch(1)

    @Volatile
    var status: Int = -1

    @Volatile
    var started: Boolean = false

    private val threads = ArrayList<Thread>()

    fun addThread(t: Thread) = synchronized(threads) { threads.add(t) }

    fun start() {
        val list = synchronized(threads) { ArrayList(threads) }
        started = true
        for (t in list) t.start()
    }

    fun isRunning(): Boolean = finished.count > 0

    fun cancel(): Boolean = cancelled.compareAndSet(false, true)

    /** Waits for the group; returns its exit status. */
    fun join(): Int {
        finished.await()
        return status
    }
}
