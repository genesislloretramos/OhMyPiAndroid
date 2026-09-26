package omp.shell.cmd

import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.fs.PathException
import omp.shell.fs.PathResolver
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Helpers every command shares. Anything two commands need belongs here rather than being written
 * twice, because the two copies drift.
 */
object Cmds {

    /** Resolves a user-typed path, reporting the failure in the command's own voice. */
    fun resolve(ctx: ExecContext, path: String): String? = try {
        PathResolver.resolve(ctx.session, path)
    } catch (e: PathException) {
        ctx.errLine("${ctx.name}: $path: ${e.message}")
        null
    }

    fun resolveAll(ctx: ExecContext, operands: List<String>): List<String>? {
        val out = ArrayList<String>(operands.size)
        for (o in operands) {
            val r = resolve(ctx, o) ?: return null
            out += r
        }
        return out
    }

    /**
     * `File.listFiles()` returns null for an unreadable directory, which is the single most common
     * real case on Android (every other app's /data/data/<pkg>). Every tree walk must treat that as
     * `Permission denied` and never as an empty directory.
     */
    fun listDir(ctx: ExecContext, op: String, dir: File): Array<File>? {
        if (!dir.exists()) {
            ctx.errLine("$op: ${dir.path}: No such file or directory")
            return null
        }
        if (!dir.isDirectory) {
            ctx.errLine("$op: ${dir.path}: Not a directory")
            return null
        }
        val entries = try {
            dir.listFiles()
        } catch (e: SecurityException) {
            null
        }
        if (entries == null) {
            ctx.errLine("$op: ${dir.path}: Permission denied")
            return null
        }
        return entries
    }

    /** `-` means stdin, the one place a file command does not touch the filesystem. */
    fun openInput(ctx: ExecContext, path: String): InputStream? {
        if (path == "-") return ctx.stdin
        val resolved = resolve(ctx, path) ?: return null
        val f = File(resolved)
        if (f.isDirectory) {
            ctx.errLine("${ctx.name}: $path: Is a directory")
            return null
        }
        return try {
            FileInputStream(f).buffered()
        } catch (e: IOException) {
            Errno.report(ctx, ctx.name, path, e)
            null
        }
    }

    fun reader(stream: InputStream): BufferedReader =
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 64 * 1024)

    /**
     * Feeds every operand to [action] as `(name, line)`. With no operand, stdin is used. Returns the
     * worst exit status seen, so a diagnostic in one file does not hide the rest.
     */
    fun forEachLine(
        ctx: ExecContext,
        operands: List<String>,
        action: (name: String, line: String) -> Unit,
    ): Int {
        var status = ExecContext.EXIT_OK
        if (operands.isEmpty()) {
            val r = reader(ctx.stdin)
            try {
                var line = r.readLine()
                while (line != null) {
                    action("-", line)
                    line = r.readLine()
                }
            } finally {
                if (ctx.stdin !== r) r.close()
            }
            return status
        }
        for (op in operands) {
            val inS = openInput(ctx, op) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            try {
                val r = reader(inS)
                var line = r.readLine()
                while (line != null) {
                    action(op, line)
                    line = r.readLine()
                }
            } catch (e: IOException) {
                Errno.report(ctx, ctx.name, op, e)
                status = ExecContext.EXIT_GENERAL_ERROR
            } finally {
                if (inS !== ctx.stdin) inS.close()
            }
        }
        return status
    }

    fun humanSize(bytes: Long, human: Boolean): String {
        if (!human) return bytes.toString()
        val units = arrayOf("B", "K", "M", "G", "T", "P")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "${value.toInt()}${units[unit]}"
        else String.format(Locale.US, "%.1f%s", value, units[unit])
    }

    /**
     * `perms size mtime name`, with no owner and group columns: an app cannot read a file's uid/gid,
     * and inventing a value would be worse than omitting the column. The letters describe what
     * *this app* can do, which is the only thing the user of this terminal can act on.
     */
    fun perms(file: File): String {
        val sb = StringBuilder("----------")
        sb.setCharAt(0, if (file.canRead()) 'r' else '-')
        sb.setCharAt(1, if (file.canWrite()) 'w' else '-')
        sb.setCharAt(2, if (file.canExecute()) 'x' else '-')
        val link = try {
            java.nio.file.Files.isSymbolicLink(file.toPath())
        } catch (e: Exception) {
            false
        }
        val type = if (link) 'l' else if (file.isDirectory) 'd' else if (file.isFile) '-' else '?'
        return "$type${sb.substring(1)}"
    }

    fun timestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))

    fun timestampSec(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    /** The one-per-line header every column-oriented tool prints, so `| less` is readable. */
    fun sizeOf(file: File): Long = try {
        if (file.isDirectory) 4096 else file.length()
    } catch (e: SecurityException) {
        0L
    }

    fun quoteIfNeeded(name: String): String =
        if (name.isEmpty() || name.any { it.isWhitespace() || it == '\'' }) "'${name.replace("'", "'\\''")}'"
        else name
}
