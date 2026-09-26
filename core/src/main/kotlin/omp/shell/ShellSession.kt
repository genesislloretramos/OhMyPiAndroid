package omp.shell

import omp.shell.exec.ExecContext
import omp.shell.exec.Shell
import omp.term.Screen
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The REPL. This is the single entry point to the whole shell: it reads a line, expands and runs
 * it, and returns the final exit status.
 */
class ShellSession(
    val services: PlatformServices,
    val screen: Screen,
    val input: InputChannel,
) {
    val session = Session(services, screen)
    val shell = Shell(session)
    val editor = LineEditor(session, screen, input)

    /** The stream commands read when they want stdin; it is the same channel the editor uses. */
    val stdin: InputStream by lazy { input.asInputStream() }

    private var storageWarningShown = false

    private val screenOut: OutputStream by lazy { ScreenOutput(screen) }

    init {
        session.cwd = services.initialDirectory()
        session.oldPwd = session.cwd
        session.env["PS1"] = DEFAULT_PS1
        prepareHome()
        shell.expander.positionalProvider = { omp.shell.cmd.Positional.values }
        shell.expander.selfPid = services.processPid().toLong()
        shell.topStderr = screenOut
        input.onInterrupt = {
            val job = session.foreground
            if (job != null) job.cancel()
        }
    }

    /**
     * `$HOME` is internal storage, not the external app directory: it is always writable with no
     * permission, and `adb shell run-as com.omp.terminal cat files/home/...` can read what the
     * shell wrote, which is how the end-to-end checks assert on it.
     */
    private fun prepareHome() {
        val home = File(services.homeDir())
        home.mkdirs()
        val profile = File(home, ".profile")
        if (!profile.exists()) {
            try {
                profile.writeText(
                    """
                    # omp terminal session profile. Sourced with: . ${session.cwd}/.profile
                    export PATH="\${'$'}PATH"
                    alias ll='ls -l'

                    """.trimIndent() + "\n"
                )
            } catch (e: Exception) {
                // A read-only home is reported by the first command that tries to use it.
            }
        }
    }

    /** The REPL loop. Returns the exit status. */
    fun run(): Int {
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

    /** `omp:` in bold green, the `~`-substituted cwd in bold blue, `$ ` in white. */
    fun prompt(): String {
        val ps1 = session.env["PS1"] ?: DEFAULT_PS1
        return if (ps1 == DEFAULT_PS1) defaultPrompt() else expandPrompt(ps1)
    }

    private fun defaultPrompt(): String {
        val storage = if (!services.isExternalStorageManager()) YELLOW + " grant-storage" + RESET + "  " else ""
        return storage + GREEN + BOLD + "omp:" + RESET +
            BLUE + BOLD + session.promptCwd() + RESET +
            WHITE + " " + DOLLAR + " " + RESET
    }

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
    }
}

private const val ESC = 27.toChar()
private const val CSI = "$ESC["
