package omp.shell.exec

import omp.shell.PlatformServices
import omp.shell.Session
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The contract every command runs against. Changing a field here changes every command in the
 * shell, so this class is deliberately the narrowest thing that makes pipes, `2>&1` and Ctrl-C work.
 */
class ExecContext(
    val argv: List<String>,
    val stdin: InputStream,
    val stdout: OutputStream,
    val stderr: OutputStream,
    val env: MutableMap<String, String>,
    val services: PlatformServices,
    val session: Session,
    /** True when stdout is the terminal rather than a pipe; `ls` colours and `echo -e` read this. */
    val isTty: Boolean,
    /** Set by Ctrl-C; long-running commands poll it in their work loops. */
    val cancelled: AtomicBoolean,
) {
    /** `argv` without the command name. */
    val args: List<String> = argv.drop(1)

    fun out(text: String) {
        stdout.write(text.toByteArray(Charsets.UTF_8))
    }

    fun out(bytes: ByteArray) {
        stdout.write(bytes)
    }

    fun outLine(text: String = "") {
        out(text)
        out("\n")
    }

    fun errLine(text: String = "") {
        stderr.write((text + "\n").toByteArray(Charsets.UTF_8))
    }

    fun flush() {
        try {
            stdout.flush()
        } catch (e: Exception) {
            // A closed pipe (`| head -1`) surfaces here and is not an error worth reporting.
        }
        try {
            stderr.flush()
        } catch (e: Exception) {
        }
    }

    /** Writes a diagnostic to stderr and returns 1. Diagnostics never go to stdout. */
    fun fail(message: String): Int {
        errLine(message)
        return EXIT_GENERAL_ERROR
    }

    val name: String get() = argv.firstOrNull() ?: "sh"

    /** A shallow copy with a rewritten argument vector; everything else is shared. */
    fun withArgv(argv: List<String>): ExecContext =
        ExecContext(argv, stdin, stdout, stderr, env, services, session, isTty, cancelled)

    companion object {
        const val EXIT_OK = 0
        const val EXIT_GENERAL_ERROR = 1
        const val EXIT_USAGE = 2
        const val EXIT_NOT_EXECUTABLE = 126
        const val EXIT_NOT_FOUND = 127
        const val EXIT_INTERRUPTED = 130
    }
}

/** Reports whether the job was cancelled so a work loop can bail out promptly. */
fun ExecContext.checkCancelled(): Boolean = cancelled.get()
