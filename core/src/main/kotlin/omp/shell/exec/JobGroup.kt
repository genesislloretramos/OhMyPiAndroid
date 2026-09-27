package omp.shell.exec

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One pipeline's worth of work: every stage runs on its own daemon thread, and Ctrl-C sets
 * [cancelled] on the foreground group.
 *
 * A pipeline that a stage starts for itself -- a `$( )` substitution -- is [parent]'s work, not a
 * job of its own, and it shares [parent]'s cancel flag rather than keeping one that nothing would
 * ever set. So an interrupt reaches the job the user means *and* everything running inside it.
 */
class JobGroup(
    val pid: Int,
    val argv: List<String>,
    val background: Boolean,
    private val parent: JobGroup? = null,
) {
    val cancelled: AtomicBoolean = parent?.cancelled ?: AtomicBoolean(false)
    val finished = CountDownLatch(1)

    /**
     * The pipeline's exit status, which is the **last** stage's and no other: only that stage
     * writes it, before it returns, and [finished] only counts down once its thread has been
     * joined, so a reader of [join] sees it. Every other stage's status is its own business.
     */
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
