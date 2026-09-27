package omp.vm

import omp.shell.PlatformServices
import omp.shell.Session

/**
 * One process in the VM's own PID namespace.
 *
 * The numbers here are the VM's, not the phone's: an app cannot see or signal another process's pid
 * on Android, and pretending otherwise would make `kill 4242` a lie. [state] is the one-character
 * code `ps` and `/proc/<pid>/stat` print: `R` while it is doing something, `S` once it is parked
 * waiting, `Z` after [VmProcessTable.release] until the table is dropped.
 */
data class VmProcess(
    val pid: Int,
    val argv: List<String>,
    val comm: String,
    val state: Char,
    val ppid: Int,
    val uid: Int,
    val gid: Int,
    /** Monotonic milliseconds at [VmProcessTable.register], which is what `ps` prints as `start`. */
    val startMillis: Long,
    val cpuMillis: Long = 0L,
)

/**
 * The VM's process table: pid 1 is the VM's own init, pid 2 the login shell once one is started,
 * and a command the shell is running appears at the shell's own pid, with the `?` in its TTY column saying there is no real process behind it.
 *
 * This is a *namespace*, not a scheduler. There are no threads behind these entries — a `VmProcess`
 * is a bookkeeping record so that `ps`, `/proc` and `top` have something honest to print while a
 * command runs, and the state is set by whoever started the work. Nothing here can be signalled,
 * because there is no pid to signal: the phone's pids belong to the phone.
 *
 * A pid is handed out exactly once. [release] never advances the counter, so a released pid is
 * never reused and a double release cannot leak one, which is what `ps` would show and what a test
 * can hold us to.
 */
class VmProcessTable(
    private val services: PlatformServices,
    private val now: () -> Long,
    /** The user a process started by the shell belongs to; every one of them is that user. */
    private val currentUser: () -> VmUser? = { null },
) {
    private val live = LinkedHashMap<Int, VmProcess>()
    private val zombies = ArrayList<VmProcess>()
    private var nextPid = FIRST_COMMAND_PID

    /**
     * The shell whose jobs are this table's truth, set once by [omp.vm.VmSystem.openSession].
     *
     * A [omp.shell.exec.JobGroup] is the only thing in the whole program that knows a command is
     * running right now: it is created before the work starts, removed when the work is done, and
     * its `pid` is the one the shell put in `$!`. So the table reads the session's live jobs rather
     * than keeping a second, guessy copy of them — which is also why there is nothing to wire into
     * `omp.shell.exec.Shell`: the jobs are already there, and asking is more honest than a
     * notification that could be missed. A null session (the phone, or a kernel nobody entered)
     * simply contributes no jobs.
     */
    @Volatile
    private var session: Session? = null

    fun attach(session: Session) {
        this.session = session
    }

    /** The session's running jobs, as processes in this namespace, with the shell's own pids. */
    fun jobs(): List<VmProcess> {
        val user = currentUser()
        return session?.jobs().orEmpty().filter { it.isRunning() }.map { job ->
            VmProcess(
                pid = job.pid,
                argv = job.argv.toList(),
                comm = shortName(job.argv),
                state = if (job.cancelled.get()) 'Z' else 'R',
                ppid = LOGIN_PID,
                uid = user?.uid ?: 0,
                gid = user?.gid ?: 0,
                startMillis = now(),
                cpuMillis = 0L,
            )
        }
    }

    /** Live processes, the session's running jobs and zombies, in pid order: what `ps` lists. */
    fun snapshot(): List<VmProcess> = (live.values + jobs() + zombies).sortedBy { it.pid }

    /** The most recently started process that is still alive, which is what `/proc/self` means. */
    fun current(): VmProcess? = jobs().lastOrNull() ?: live.values.lastOrNull() ?: live[INIT_PID]

    /**
     * A new process. The counter only ever moves forward, so a pid identifies one process for the
     * lifetime of the VM — a command that exits and the next one that starts never share a number.
     */
    fun register(
        argv: List<String>,
        comm: String = shortName(argv),
        ppid: Int = INIT_PID,
        uid: Int = currentUser()?.uid ?: 0,
        gid: Int = currentUser()?.gid ?: 0,
    ): VmProcess {
        val process = VmProcess(
            pid = nextPid++,
            argv = argv.toList(),
            comm = comm,
            state = 'R',
            ppid = ppid,
            uid = uid,
            gid = gid,
            startMillis = now(),
        )
        live[process.pid] = process
        return process
    }

    /** Records the fixed entries: pid 1 is the VM's init, pid 2 the login shell. */
    fun registerFixed(pid: Int, argv: List<String>, comm: String, state: Char, uid: Int, gid: Int): VmProcess {
        val process = VmProcess(pid, argv.toList(), comm, state, 0, uid, gid, now())
        live[pid] = process
        if (pid >= nextPid) nextPid = pid + 1
        return process
    }

    /**
     * Ends a process: it becomes a zombie, which is what `ps` shows until the table is dropped.
     * Releasing an unknown pid, or releasing the same pid twice, does nothing at all.
     */
    fun release(pid: Int) {
        val process = live.remove(pid) ?: return
        zombies += process.copy(state = 'Z')
    }

    /** Marks a process as parked (`S`) or running again (`R`); a released pid stays a zombie. */
    fun setState(pid: Int, state: Char) {
        val process = live[pid] ?: return
        live[pid] = process.copy(state = state)
    }

    /** Records CPU time against a process; `top` reads it, and it is the app's own CPU clock. */
    fun addCpuMillis(pid: Int, millis: Long) {
        val process = live[pid] ?: return
        live[pid] = process.copy(cpuMillis = process.cpuMillis + millis)
    }

    fun of(pid: Int): VmProcess? = live[pid] ?: jobs().firstOrNull { it.pid == pid } ?: zombies.firstOrNull { it.pid == pid }

    /** Kills everything and forgets it, which is what [VmKernel.shutdown] means. */
    fun clear() {
        live.clear()
        zombies.clear()
    }

    fun size(): Int = snapshot().size

    /** The kernel's own pid count for `/proc/loadavg`'s `running/total` field. */
    fun loadAvgCounts(): String = "${live.values.count { it.state == 'R' }}/${size()}"

    /** The VM's CPU clock: the app's own process times, or nothing when the platform has none. */
    fun hostCpuTimes(): Pair<Long, Long>? = services.processCpuTimes()

    companion object {
        const val INIT_PID = 1
        const val LOGIN_PID = 2

        /** Commands start here, so a pid is never confused with init or the login shell. */
        const val FIRST_COMMAND_PID = 100

        /** The `comm` a process is listed under: its first word, without a directory. */
        fun shortName(argv: List<String>): String {
            val first = argv.firstOrNull() ?: return "?"
            val slash = first.lastIndexOf('/')
            return if (slash >= 0) first.substring(slash + 1) else first
        }
    }
}
