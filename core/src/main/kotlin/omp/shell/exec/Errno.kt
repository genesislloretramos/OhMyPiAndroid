package omp.shell.exec

import omp.shell.fs.FsException

/**
 * Maps a filesystem exception to the Unix diagnostic the user expects. The kernel's own wording is
 * already in the exception message on Android (`/proc/stat (Permission denied)`), so this reads it
 * out rather than guessing from an error table that would drift.
 *
 * A failure that came through the [omp.shell.fs.Vfs] seam already knows its errno, so it is asked
 * first and the scraping below is only for the framework exceptions nothing has wrapped yet.
 */
object Errno {

    fun messageFor(e: Throwable): String {
        if (e is FsException) return e.errno.text
        val raw = e.message ?: return "No such file or directory"
        for (known in KNOWN) {
            if (raw.contains("(${known})") || raw.endsWith(": $known")) return known
        }
        return if (e is java.io.FileNotFoundException) "No such file or directory" else raw
    }

    /** `<cmd>: <path>: <reason>` on stderr, exit 1. */
    fun report(ctx: ExecContext, op: String, path: String, e: Throwable): Int =
        ctx.fail("$op: $path: ${messageFor(e)}")

    private val KNOWN = listOf(
        "Permission denied",
        "No such file or directory",
        "Not a directory",
        "Is a directory",
        "No space left on device",
        "File exists",
        "Too many levels of symbolic links",
        "Device or resource busy",
        "Read-only file system",
    )
}
