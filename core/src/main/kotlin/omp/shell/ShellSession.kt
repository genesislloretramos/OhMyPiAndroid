package omp.shell

import omp.shell.exec.CommandTable
import omp.shell.exec.ExecContext
import omp.shell.exec.Shell
import omp.shell.fs.RealVfs
import omp.shell.fs.Vfs
import omp.term.Screen
import java.io.InputStream
import java.io.OutputStream

/**
 * The REPL. This is the single entry point to the whole shell: it reads a line, expands and runs
 * it, and returns the final exit status.
 *
 * [table] and [vfs] are what a second namespace is: a command set and a filesystem that are not
 * this device's, with [namespaceName] only so the prompt says which one the user is in. A session
 * that passes neither is the phone's session, unchanged.
 */
class ShellSession(
    val services: PlatformServices,
    val screen: Screen,
    val input: InputChannel,
    val table: CommandTable = CommandTable.global,
    val vfs: Vfs = RealVfs(),
    val namespaceName: String? = null,
) {
    val session = Session(services, screen, vfs, table)
    val shell = Shell(session, table)
    val editor = LineEditor(session, screen, input)

    /** The stream commands read when they want stdin; it is the same channel the editor uses. */
    val stdin: InputStream by lazy { input.asInputStream() }

    private var storageWarningShown = false

    private val screenOut: OutputStream by lazy { ScreenOutput(screen) }

    init {
        session.cwd = services.initialDirectory()
        session.oldPwd = session.cwd
        session.env["PS1"] = DEFAULT_PS1
        // The channel the editor reads, recorded on the session so a namespace REPL can be built on
        // the same Screen and the same keys rather than on a second, dead terminal.
        session.input = input
        // Only the phone's own filesystem has a sandbox home to prepare; a namespace that brought
        // its own Vfs brought its own home, and creating directories inside someone else's
        // namespace at start-up would be a side effect the user never asked for.
        if (vfs is RealVfs && vfs.isDeviceRoot) prepareHome()
        shell.expander.positionalProvider = { omp.shell.cmd.Positional.values }
        shell.expander.selfPid = services.processPid().toLong()
        shell.topStderr = screenOut
    }

    /**
     * `$HOME` is internal storage, not the external app directory: it is always writable with no
     * permission, and `adb shell run-as com.omp.terminal cat files/home/...` can read what the
     * shell wrote, which is how the end-to-end checks assert on it.
     */
    private fun prepareHome() {
        val home = services.homeDir()
        omp.shell.cmd.fsMakeDirs(vfs, home)
        val profilePath = home.trimEnd('/') + "/.profile"
        if (omp.shell.cmd.fsExists(vfs, profilePath)) return
        try {
            vfs.writeBytes(
                profilePath,
                profile(session.cwd),
            )
        } catch (e: Exception) {
            // A read-only home is reported by the first command that tries to use it.
        }
    }

    /**
     * The REPL loop. Returns the exit status.
     *
     * Ctrl-C is wired here rather than in the constructor, and handed back on the way out, because
     * a second REPL may run on the same [input]: while it does, its foreground job is the one a
     * Ctrl-C should cancel, and the session that was here first is not the one reading the keys.
     */
    fun run(): Int {
        val outerInterrupt = input.onInterrupt
        input.onInterrupt = {
            val job = session.foreground
            if (job != null) job.cancel()
        }
        try {
            screen.write(CLEAR_HOME)
            startupBanner()
            while (!session.exitRequested) {
                when (val result = editor.readLine(prompt())) {
                    is ReadResult.Eof -> {
                        screen.write("\r\n")
                        return ExecContext.EXIT_OK
                    }
                    is ReadResult.Interrupted -> {
                        session.lastStatus = ExecContext.EXIT_INTERRUPTED
                    }
                    is ReadResult.Line -> {
                        val line = result.text
                        if (line.isNotBlank()) session.history.add(line)
                        session.lastStatus = run(line)
                    }
                }
            }
            return session.lastStatus
        } finally {
            input.onInterrupt = outerInterrupt
        }
    }

    private fun run(line: String): Int {
        val status = try {
            shell.executeLine(line, stdin, screenOut, screenOut, true)
        } catch (e: Exception) {
            screen.write("sh: ${e.message ?: e::class.java.simpleName}\n")
            ExecContext.EXIT_GENERAL_ERROR
        }
        val job = session.foreground
        if (job != null && job.cancelled.get()) screen.write("^C\n")
        return status
    }

    /** `[namespaceLabel]:` in bold green — `omp:` when there is no namespace — then cwd in bold blue and `$ `. */
    fun prompt(): String {
        val ps1 = session.env["PS1"] ?: DEFAULT_PS1
        return if (ps1 == DEFAULT_PS1) defaultPrompt() else expandPrompt(ps1)
    }

    private fun defaultPrompt(): String {
        val storage = if (!services.isExternalStorageManager()) YELLOW + " grant-storage" + RESET + "  " else ""
        return storage + GREEN + BOLD + "$namespaceLabel:" + RESET +
            BLUE + BOLD + session.promptCwd() + RESET +
            WHITE + " " + DOLLAR + " " + RESET
    }

    /**
     * The name in front of the prompt's colon, for a namespace: `<user>@<namespace>`, which is
     * bash's own shape and is read from the session's `USER` on every prompt.
     *
     * That indirection is the point. `su` rewrites the session's environment from the target's
     * passwd entry, so a prompt built from the label once would keep saying `ubuntu` after the
     * session had become root — which is exactly the moment a user needs the prompt to be right.
     * A session with no namespace is the phone's own, and its name does not move.
     */
    private val namespaceLabel: String
        get() = namespaceName?.let { n -> session.env["USER"]?.let { u -> "$u@$n" } ?: n } ?: "omp"

    /** `export PS1=...` works, so `$VAR`, `${VAR}` and `$(cmd)` in it are expanded here. */
    private fun expandPrompt(ps1: String): String {
        val parts = ArrayList<String>()
        try {
            val lexer = omp.shell.parser.Lexer(ps1)
            val token = lexer.tokenize().firstOrNull()
            if (token == null || token.type != omp.shell.parser.TokenType.WORD || token.word == null) return defaultPrompt()
            for (part in shell.expander.expandWords(listOf(token.word!!))) parts += part
        } catch (e: Exception) {
            return defaultPrompt()
        }
        if (parts.isEmpty()) return defaultPrompt()
        return parts.joinToString(" ")
            .replace("omp:", GREEN + BOLD + "omp:" + RESET)
            .replace("~", BLUE + BOLD + "~" + RESET)
    }

    /** Printed once at start-up, because `ls /sdcard` otherwise looks like an empty directory. */
    fun startupBanner() {
        if (!services.isExternalStorageManager() && !storageWarningShown) {
            storageWarningShown = true
            screen.write(
                YELLOW + "All-files access is not granted. Run: grant-storage" + RESET + "\r\n" +
                    "Without it only the app sandbox and /proc/meminfo are reachable.\r\n"
            )
        }
    }

    companion object {
        private const val CLEAR_HOME = "${CSI}H${CSI}2J${CSI}3J"
        private const val DOLLAR = "$"
        private const val RESET = "${CSI}0m"
        private const val BOLD = "${CSI}1m"
        private const val GREEN = "${CSI}32m"
        private const val BLUE = "${CSI}34m"
        private const val YELLOW = "${CSI}33m"
        private const val WHITE = "${CSI}37m"
        const val DEFAULT_PS1 = "omp:~$ "

        /**
         * The session profile `$HOME` gets, built here because it names the session's own working
         * directory: a second home in a second namespace must not inherit the first one's line.
         */
        fun profile(cwd: String): ByteArray = (
            "# omp terminal session profile. Sourced with: . $cwd/.profile\n" +
                "export PATH=\"\${DOLLAR}PATH\"\n" +
                "alias ll='ls -l'\n\n"
            ).toByteArray(Charsets.UTF_8)
    }
}

private const val ESC = 27.toChar()
private const val CSI = "$ESC["
