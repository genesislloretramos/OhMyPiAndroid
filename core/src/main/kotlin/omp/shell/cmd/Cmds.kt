package omp.shell.cmd

import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.fs.FsException
import omp.shell.fs.PathException
import omp.shell.fs.PathResolver
import omp.shell.fs.VEntry
import omp.shell.fs.VNodeType
import omp.shell.fs.VStat
import omp.shell.fs.Vfs
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Helpers every command shares. Anything two commands need belongs here rather than being written
 * twice, because the two copies drift. Every filesystem answer comes from the session's
 * [omp.shell.fs.Vfs], so a path a command resolves is the path its own namespace answers for.
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
     * The entries of [path], or null with the reason already on stderr. An unreadable directory is
     * the single most common real case on Android (every other app's /data/data/<pkg>), and it has
     * to reach the user as `Permission denied` rather than as an empty directory — [Vfs] answers
     * that with an [omp.shell.fs.FsErrno.PERM_DENIED] for exactly this reason.
     *
     * Every entry carries the [VStat] of the node behind it, so a walk never asks about the same
     * name twice.
     */
    fun listDir(ctx: ExecContext, op: String, vfs: Vfs, path: String): List<VEntry>? = try {
        vfs.readDir(path)
    } catch (e: FsException) {
        ctx.errLine("$op: $path: ${e.errno.text}")
        null
    }
    /**
     * The node the name itself is, with a symbolic link left as a [VNodeType.SYMLINK]: `rm`,
     * `rmdir`, `mv`, `ln`, `readlink`, `stat`, `ls -l`, `find`'s type tests and `tree` all act on
     * the name the user typed, so none of them may be handed the target instead.
     *
     * Throws [FsException]; a caller that has to report a missing path in its own voice uses
     * [statOrNull] instead.
     */
    fun statLink(vfs: Vfs, path: String): VStat = vfs.stat(path)

    /**
     * The node a path finally names, with one [Vfs.realpath] hop first: `cd`, `test -e/-f/-d/-s`,
     * `du`, `df` and the content readers ask whether the path *leads to* something, which is a
     * different question from whether the name is a link.
     *
     * One hop per call, never one per entry: a tree walk reads its [VStat]s from the listing.
     */
    fun statFollowed(vfs: Vfs, path: String): VStat = vfs.stat(vfs.realpath(path))

    /** [statFollowed] as a null, for a caller that treats "cannot be reached" as "is not there". */
    fun statFollowedOrNull(vfs: Vfs, path: String): VStat? = try {
        statFollowed(vfs, path)
    } catch (e: FsException) {
        null
    }

    /** `true` when the name exists and leads to a directory, links included, as `cd -P` would see it. */
    fun isDirFollowed(vfs: Vfs, path: String): Boolean =
        statFollowedOrNull(vfs, path)?.type == VNodeType.DIRECTORY

    /** The [VStat] of [path], or null when it is absent or this app may not look at it. */
    fun statOrNull(vfs: Vfs, path: String): VStat? = try {
        vfs.stat(path)
    } catch (e: FsException) {
        null
    }

    /** `-` means stdin, the one place a file command does not touch the filesystem. */
    fun openInput(ctx: ExecContext, path: String): InputStream? {
        if (path == "-") return ctx.stdin
        val resolved = resolve(ctx, path) ?: return null
        // The seam refuses a directory with `Is a directory`, which is the wording this has always
        // printed for one, so the check does not have to be made here as well.
        return try {
            ctx.session.vfs.openRead(resolved).buffered()
        } catch (e: FsException) {
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
     * *this app* can do, which is the only thing the user of this terminal can act on, so they come
     * from the [VStat]'s access bits and the group/other columns stay dashes whatever the mode says.
     */
    fun perms(stat: VStat): String {
        val sb = StringBuilder("----------")
        sb.setCharAt(0, if (stat.readable) 'r' else '-')
        sb.setCharAt(1, if (stat.writable) 'w' else '-')
        sb.setCharAt(2, if (stat.executable) 'x' else '-')
        val type = when (stat.type) {
            VNodeType.SYMLINK -> 'l'
            VNodeType.DIRECTORY -> 'd'
            VNodeType.FILE -> '-'
            else -> '?'
        }
        // The type character plus all nine permission characters: dropping the first of them
        // would print the read bit as a dash for every file this app can read.
        return "$type${sb.substring(0, 9)}"
    }

    fun timestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))

    fun timestampSec(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    /**
     * The one-per-line header every column-oriented tool prints, so `| less` is readable. A
     * directory is one 4096-byte block, and a link is measured by its target string: a
     * [VNodeType.SYMLINK]'s size is the length of the name it points at, where a stat that followed
     * the link would report the target's own size.
     */
    fun sizeOf(stat: VStat): Long = if (stat.type == VNodeType.DIRECTORY) 4096L else stat.size

    fun quoteIfNeeded(name: String): String =
        if (name.isEmpty() || name.any { it.isWhitespace() || it == '\'' }) "'${name.replace("'", "'\\''")}'"
        else name
}
