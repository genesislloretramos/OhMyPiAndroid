package omp.shell

import omp.shell.exec.JobGroup

/**
 * All state that outlives a single command: environment, aliases, the working directory, job
 * control, the history and the terminal screen. One session per REPL.
 */
class Session(
    val services: PlatformServices,
    val screen: omp.term.Screen,
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

    /** `$PWD`, for a prompt. */
    fun promptCwd(limit: Int = 40): String {
        val home = services.homeDir()
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
