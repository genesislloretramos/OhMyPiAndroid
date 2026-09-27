package omp.shell

import omp.shell.exec.CommandTable
import omp.shell.exec.JobGroup
import omp.shell.fs.RealVfs
import omp.shell.fs.Vfs

/**
 * All state that outlives a single command: environment, aliases, the working directory, job
 * control, the history and the terminal screen. One session per REPL.
 *
 * [vfs] is what paths mean here and [table] is what names mean; both default to the phone's own, so
 * a session that says nothing is exactly the session this shell has always been. A second namespace
 * is the same [Session] with a different [Vfs] — its `/etc` is not this device's `/etc`.
 */
class Session(
    val services: PlatformServices,
    val screen: omp.term.Screen,
    val vfs: Vfs = RealVfs(),
    val table: CommandTable = CommandTable.global,
) {
    val env = LinkedHashMap<String, String>()
    val aliases = LinkedHashMap<String, String>()
    private val jobList = ArrayList<JobGroup>()
    private val jobLock = Any()

    var cwd: String = services.initialDirectory()
    var oldPwd: String = cwd
    var lastStatus: Int = 0

    /** Set by `exit`; the REPL loop returns it. */
    var exitRequested: Boolean = false

    // `set` options
    var errexit = false
    var xtrace = false
    var nounset = false

    /** Monotonic, starting at 1000: an app cannot see real pids for processes it does not own. */
    var nextPid = 1000

    val history = History(500)


    /** The pipeline the REPL is currently blocked on; Ctrl-C cancels this one only. */
    @Volatile
    var foreground: JobGroup? = null

    /** `$!`: the pid of the most recently started background job. */
    var lastBackgroundPid: Int = 0

    /**
     * The channel this session's line editor reads, kept here so a second namespace on the same
     * terminal can be opened without the caller threading it through: `vm enter` builds the VM's
     * session from the phone's [input] and the same [omp.term.Screen], and that is what makes the
     * two sessions one terminal rather than two.
     *
     * Null until a [ShellSession] sets it, because a [Session] built directly has no REPL.
     */
    var input: InputChannel? = null

    /**
     * Who is showing this session, when there is a host at all. `vm enter` copies the phone's
     * [host] into the VM's session so the VM's REPL can say it is in front and hand the terminal
     * back on the way out. Null on a session that is the only one, which is the app's own.
     */
    var host: SessionHost? = null

    init {
        env["HOME"] = services.homeDir()
        env["PWD"] = cwd
        env["OLDPWD"] = cwd
        env["PATH"] = DEFAULT_PATH
        env["SHELL"] = "/system/bin/sh"
        env["TERM"] = "xterm-256color"
        env["LANG"] = services.locale()
        env["TMPDIR"] = services.homeDir()
    }

    fun allocatePid(): Int = synchronized(jobLock) { nextPid++ }

    fun addJob(job: JobGroup) = synchronized(jobLock) { jobList.add(job); jobList.toList() }

    fun removeJob(pid: Int) = synchronized(jobLock) { jobList.removeAll { it.pid == pid } }

    fun jobs(): List<JobGroup> = synchronized(jobLock) { jobList.toList() }

    fun job(pid: Int): JobGroup? = synchronized(jobLock) { jobList.firstOrNull { it.pid == pid } }

    /**
     * `$HOME`, which the environment owns: `export HOME=…` moves it, and a namespace that is not
     * this device's brings a home of its own. The platform's home is only the seed.
     */
    fun home(): String = env["HOME"] ?: services.homeDir()

    /** `$PWD`, for a prompt. */
    fun promptCwd(limit: Int = 40): String {
        val home = home()
        val shown = if (cwd == home) "~" else if (cwd.startsWith("$home/")) "~" + cwd.substring(home.length) else cwd
        return if (shown.length <= limit) shown else "…" + shown.takeLast(limit - 1)
    }

    companion object {
        const val DEFAULT_PATH = "/system/bin:/system/xbin:/vendor/bin:/product/bin"
    }
}

/** Newest-last, capped, de-duplicated against the immediately previous entry. */
class History(val capacity: Int) {
    private val entries = ArrayList<String>()

    @Synchronized
    fun add(line: String) {
        if (line.isBlank()) return
        if (entries.lastOrNull() == line) return
        entries.add(line)
        while (entries.size > capacity) entries.removeAt(0)
    }

    @Synchronized
    fun all(): List<String> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun last(): String? = entries.lastOrNull()

    @Synchronized
    fun size(): Int = entries.size
}
