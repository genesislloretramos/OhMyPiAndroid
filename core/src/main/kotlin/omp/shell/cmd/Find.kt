package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.CommandTable
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.printUsage
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.PathResolver
import omp.shell.fs.VNodeType
import omp.shell.fs.VStat
import omp.shell.fs.Vfs
import omp.shell.fs.resolveSymlinks
import omp.shell.parser.Glob
import java.io.IOException
import java.util.Locale

@CommandSpec(
    name = "find",
    synopsis = "[path] [-name G] [-iname G] [-type f|d|l] [-maxdepth N] [-mindepth N] [-size +Nc] [-empty] [-path P] [-print] [-delete] [-exec cmd ;]",
    group = "files",
    notes = "links are not followed unless -L; a directory cycle is pruned rather than walked twice",
)
object Find : Command {

    private const val MAX_LINK_HOPS = 40
    private const val USAGE = "find [path] [-name G] [-iname G] [-type f|d|l] [-maxdepth N] [-mindepth N] " +
        "[-size +Nc] [-empty] [-path P] [-print] [-delete] [-exec cmd ;]"

    private val TYPES = setOf("f", "d", "l", "b", "c", "p", "s")

    /** `n` counted in [unit] bytes, `+n` meaning more than n of them and `-n` fewer. */
    private class Size(val count: Long, val unit: Long, val more: Boolean, val less: Boolean) {
        fun matches(bytes: Long): Boolean {
            val n = (bytes + unit - 1) / unit
            return when {
                more -> n > count
                less -> n < count
                else -> n == count
            }
        }
    }

    private class Query(
        val name: String?,
        val iname: String?,
        val pathPattern: String?,
        val type: String?,
        val size: Size?,
        val empty: Boolean,
        val minDepth: Int,
        val maxDepth: Int,
        val printAction: Boolean,
        val deleteAction: Boolean,
        val exec: List<String>?,
        val follow: Boolean,
    )

    override fun run(ctx: ExecContext): Int {
        var startRaw: String? = null
        var name: String? = null
        var iname: String? = null
        var pathPattern: String? = null
        var type: String? = null
        var size: Size? = null
        var empty = false
        var printAction = false
        var deleteAction = false
        var exec: List<String>? = null
        var follow = false
        var maxDepth = Int.MAX_VALUE
        var minDepth = 0

        val args = ctx.args
        var i = 0
        var literal = false
        while (i < args.size) {
            val a = args[i]
            if (literal || a == "-" || !a.startsWith("-")) {
                if (startRaw != null) {
                    ctx.errLine("find: paths must precede the expression: $a")
                    printUsage(ctx.stderr, USAGE)
                    return ExecContext.EXIT_USAGE
                }
                startRaw = a
                i++
                continue
            }
            if (a == "--") {
                literal = true
                i++
                continue
            }
            when (a) {
                "-name", "-iname", "-path", "-type", "-size", "-maxdepth", "-mindepth" -> {
                    if (i + 1 >= args.size) {
                        ctx.errLine("find: option $a requires an argument")
                        printUsage(ctx.stderr, USAGE)
                        return ExecContext.EXIT_USAGE
                    }
                    val v = args[i + 1]
                    when (a) {
                        "-name" -> name = v
                        "-iname" -> iname = v
                        "-path" -> pathPattern = v
                        "-type" -> {
                            if (v !in TYPES) return ctx.fail("find: unknown file type: '$v'")
                            type = v
                        }
                        "-size" -> {
                            size = parseSize(v) ?: return ctx.fail("find: invalid argument to -size: '$v'")
                        }
                        "-maxdepth" -> {
                            val d = v.trim().toIntOrNull()
                            if (d == null || d < 0) return ctx.fail("find: invalid argument to -maxdepth: '$v'")
                            maxDepth = d
                        }
                        "-mindepth" -> {
                            val d = v.trim().toIntOrNull()
                            if (d == null || d < 0) return ctx.fail("find: invalid argument to -mindepth: '$v'")
                            minDepth = d
                        }
                    }
                    i++
                }
                "-print" -> printAction = true
                "-delete" -> deleteAction = true
                "-empty" -> empty = true
                "-L", "-follow" -> follow = true
                "-P" -> follow = false
                "-exec" -> {
                    val collected = ArrayList<String>()
                    var closed = false
                    while (i + 1 < args.size) {
                        i++
                        val token = args[i]
                        if (token == ";") {
                            closed = true
                            break
                        }
                        if (token == "+") {
                            return ctx.fail("find: -exec ... + is not supported, use ';'")
                        }
                        collected += token
                    }
                    if (!closed || collected.isEmpty()) {
                        ctx.errLine("find: -exec must be terminated by ';'")
                        printUsage(ctx.stderr, USAGE)
                        return ExecContext.EXIT_USAGE
                    }
                    exec = collected
                }
                else -> {
                    ctx.errLine("find: invalid option -- '${a.substring(1)}'")
                    printUsage(ctx.stderr, USAGE)
                    return ExecContext.EXIT_USAGE
                }
            }
            i++
        }

        val shown = startRaw ?: "."
        val startPath = Cmds.resolve(ctx, shown) ?: return ExecContext.EXIT_GENERAL_ERROR
        val vfs = ctx.session.vfs
        // `find` does not dereference: `find link -type l` is how a user finds the links.
        val startStat = fsStatOrNull(vfs, startPath)
            ?: return ctx.fail("find: '$shown': No such file or directory")

        val query = Query(
            name = name,
            iname = iname,
            pathPattern = pathPattern,
            type = type,
            size = size,
            empty = empty,
            minDepth = minDepth,
            maxDepth = maxDepth,
            printAction = printAction || (exec == null && !deleteAction),
            deleteAction = deleteAction,
            exec = exec,
            follow = follow,
        )
        val display = PathResolver.expandTilde(ctx.session, shown)
        return walk(ctx, vfs, query, startPath, startStat, display, 0, ArrayList(), 0)
    }

    private fun parseSize(text: String): Size? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val more = trimmed.startsWith("+")
        val less = trimmed.startsWith("-")
        var body = if (more || less) trimmed.substring(1) else trimmed
        // POSIX find counts 512-byte blocks unless the argument says otherwise.
        var unit = 512L
        val suffix = body.lastOrNull()
        if (suffix != null && !suffix.isDigit()) {
            unit = when (suffix) {
                'c' -> 1L
                'w' -> 2L
                'b' -> 512L
                'k' -> 1024L
                'M' -> 1024L * 1024L
                'G' -> 1024L * 1024L * 1024L
                else -> return null
            }
            body = body.dropLast(1)
        }
        val n = body.toLongOrNull() ?: return null
        if (n < 0) return null
        return Size(n, unit, more, less)
    }

    /**
     * @return [ExecContext.EXIT_OK] to keep walking, or the status that stopped it: 130 for Ctrl-C
     * and 127 when an -exec command does not exist, which is what GNU find reports.
     */
    private fun walk(
        ctx: ExecContext,
        vfs: Vfs,
        q: Query,
        path: String,
        stat: VStat,
        display: String,
        depth: Int,
        ancestors: MutableList<String>,
        hops: Int,
    ): Int {
        if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
        val link = stat.type == VNodeType.SYMLINK
        if (link && q.follow && hops >= MAX_LINK_HOPS) {
            ctx.errLine("find: $display: Too many levels of symbolic links")
            return ExecContext.EXIT_OK
        }
        val nextHops = if (link && q.follow) hops + 1 else hops
        // A stat is taken without following a link, so a followed one has to be asked about again.
        val isDir = if (link) q.follow && fsIsDirFollowing(vfs, path, stat) else stat.type == VNodeType.DIRECTORY
        if (isDir && q.follow) {
            val real = realPath(vfs, path)
            if (ancestors.contains(real)) return ExecContext.EXIT_OK
            ancestors.add(real)
        }

        if (isDir && depth < q.maxDepth) {
            val entries = Cmds.listDir(ctx, "find", vfs, path)
            if (entries != null) {
                for (entry in entries.sortedBy { it.name }) {
                    val child = display.trimEnd('/') + "/" + entry.name
                    val rc = walk(ctx, vfs, q, fsChild(path, entry.name), entry.stat, child, depth + 1, ancestors, nextHops)
                    if (rc != ExecContext.EXIT_OK) return rc
                }
            }
        }
        if (isDir && q.follow) ancestors.removeAt(ancestors.size - 1)

        if (!matches(vfs, q, path, stat, display, depth)) return ExecContext.EXIT_OK
        if (q.printAction) ctx.outLine(display)
        if (q.exec != null) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val rc = runExec(ctx, q.exec, display)
            if (rc == ExecContext.EXIT_NOT_FOUND) return rc
        }
        if (q.deleteAction) delete(ctx, vfs, path, display, isDir)
        return ExecContext.EXIT_OK
    }

    private fun delete(ctx: ExecContext, vfs: Vfs, path: String, display: String, isDir: Boolean) {
        if (!isDir) {
            fsDeleteFile(ctx, "find", display, vfs, path)
            return
        }
        val occupied = try {
            vfs.readDir(path).isNotEmpty()
        } catch (e: FsException) {
            // An unreadable directory and one that vanished mid-walk are both `Permission denied`
            // here, which is the wording a `find` that could not see inside it has always used.
            ctx.errLine("find: $display: ${if (e.errno == FsErrno.PERM_DENIED) e.errno.text else FsErrno.PERM_DENIED.text}")
            return
        }
        if (occupied) {
            ctx.errLine("find: $display: Directory not empty")
            return
        }
        fsRmdir(ctx, "find", display, vfs, path)
    }

    private fun realPath(vfs: Vfs, path: String): String = try {
        resolveSymlinks(vfs, path)
    } catch (e: FsException) {
        path
    }

    private fun matches(vfs: Vfs, q: Query, path: String, stat: VStat, display: String, depth: Int): Boolean {
        val name = fsName(display)
        if (depth < q.minDepth) return false
        if (q.name != null && !Glob.matchSegment(q.name, name)) return false
        if (q.iname != null && !Glob.matchSegment(q.iname.lowercase(Locale.US), name.lowercase(Locale.US))) {
            return false
        }
        if (q.pathPattern != null && !Glob.matchWhole(q.pathPattern, display)) return false
        if (q.type != null && !typeMatches(stat, q.type)) return false
        if (q.size != null && !q.size.matches(Cmds.sizeOf(stat))) return false
        if (q.empty && !isEmpty(vfs, path, stat)) return false
        return true
    }

    private fun typeMatches(stat: VStat, type: String): Boolean {
        val link = stat.type == VNodeType.SYMLINK
        return when (type) {
            "f" -> stat.type == VNodeType.FILE && !link
            "d" -> stat.type == VNodeType.DIRECTORY && !link
            "l" -> link
            // A block device, a character device, a fifo and a socket are one thing to a stat
            // built from the JDK, which is the same answer `find` has always given for all four.
            else -> !link && stat.type != VNodeType.FILE && stat.type != VNodeType.DIRECTORY
        }
    }

    /** A directory this app may not list is not empty, and never was: `find -empty` skips it. */
    private fun isEmpty(vfs: Vfs, path: String, stat: VStat): Boolean {
        if (stat.type != VNodeType.DIRECTORY) return Cmds.sizeOf(stat) == 0L
        return try {
            vfs.readDir(path).isEmpty()
        } catch (e: FsException) {
            false
        }
    }

    private fun runExec(ctx: ExecContext, command: List<String>, path: String): Int {
        val name = command.first()
        val target = ctx.session.table.lookup(name)
        if (target == null) {
            ctx.errLine("find: $name: command not found")
            return ExecContext.EXIT_NOT_FOUND
        }
        val argv = ArrayList<String>(command.size + 1)
        argv.addAll(command)
        argv.add(path)
        val child = ExecContext(
            argv, ctx.stdin, ctx.stdout, ctx.stderr, ctx.env, ctx.services, ctx.session,
            ctx.isTty, ctx.cancelled,
        )
        return try {
            target.run(child)
        } catch (e: IOException) {
            ctx.errLine("find: $name: ${Errno.messageFor(e)}")
            ExecContext.EXIT_GENERAL_ERROR
        } catch (e: SecurityException) {
            ctx.errLine("find: $name: Permission denied")
            ExecContext.EXIT_GENERAL_ERROR
        }
    }
}
