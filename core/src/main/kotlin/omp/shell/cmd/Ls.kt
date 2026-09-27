package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.VNodeType
import omp.shell.fs.VStat
import omp.shell.fs.Vfs

@CommandSpec(
    name = "ls",
    synopsis = "[-lahrRtS1d] [--color=auto] [file ...]",
    group = "files",
    notes = "no owner or group column: an app cannot read uid/gid, so the mode letters say what this shell can do",
)
object Ls : FileCommand() {

    override val flagSpec = "lahrRtS1d"
    override val longOptions = mapOf("color" to true)

    private val esc = 27.toChar()

    private fun sgr(body: String) = "$esc[${body}m"

    /** A name to print, the path it names, and the metadata a listing already holds for it. */
    private class Item(val name: String, val path: String, val stat: VStat, val vfs: Vfs)

    private class Settings(
        val long: Boolean,
        val human: Boolean,
        val showAll: Boolean,
        val reverse: Boolean,
        val recursive: Boolean,
        val byTime: Boolean,
        val bySize: Boolean,
        val color: Boolean,
        val multiColumn: Boolean,
        val termWidth: Int,
    )

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        val color = when (options["color"] ?: "auto") {
            "always" -> true
            "never", "none" -> false
            else -> ctx.isTty
        }
        val settings = Settings(
            long = 'l' in flags,
            human = 'h' in flags,
            showAll = 'a' in flags,
            reverse = 'r' in flags,
            recursive = 'R' in flags,
            byTime = 't' in flags,
            bySize = 'S' in flags,
            color = color,
            // Columns are for a human at a terminal; a pipe gets one name per line.
            multiColumn = ctx.isTty && 'l' !in flags && '1' !in flags,
            termWidth = maxOf(20, ctx.session.screen.cols),
        )
        val dirItself = 'd' in flags
        val fromCwd = operands.isEmpty()
        val roots = if (fromCwd) listOf(".") else operands
        val headers = roots.size > 1 || settings.recursive
        val vfs = ctx.session.vfs

        var status = ExecContext.EXIT_OK
        for (raw in roots) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val path = Cmds.resolve(ctx, raw) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            // The name as typed: `ls -l link` shows a link, its own size and where it points, which
            // is the only way a listing can show that a link exists at all.
            val stat = fsStatOrNull(vfs, path)
            if (stat == null) {
                ctx.errLine("ls: $raw: No such file or directory")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            if (stat.type == VNodeType.DIRECTORY && !dirItself) {
                if (headers && !fromCwd) ctx.outLine("$raw:")
                val rc = listDirectory(ctx, vfs, path, stat, settings, headers)
                if (rc != ExecContext.EXIT_OK) status = rc
            } else {
                emit(ctx, listOf(Item(raw, path, stat, vfs)), settings)
            }
        }
        return status
    }

    private fun listDirectory(
        ctx: ExecContext,
        vfs: Vfs,
        dir: String,
        dirStat: VStat,
        s: Settings,
        headers: Boolean,
    ): Int {
        val entries = Cmds.listDir(ctx, "ls", vfs, dir) ?: return ExecContext.EXIT_GENERAL_ERROR
        val items = ArrayList<Item>(entries.size + 2)
        if (s.showAll) {
            // `.` and `..` are the directory itself: a listing of them is a listing of its mode.
            items += Item(".", dir, dirStat, vfs)
            items += Item("..", dir, dirStat, vfs)
        }
        for (e in entries) items += Item(e.name, fsChild(dir, e.name), e.stat, vfs)
        sort(items, s)
        emit(ctx, items, s)
        if (headers && s.recursive) ctx.outLine()
        if (!s.recursive) return ExecContext.EXIT_OK

        var status = ExecContext.EXIT_OK
        for (item in items) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            // A symlinked directory is listed but not descended into: `ls -R` does not follow links.
            if (item.name == "." || item.name == "..") continue
            if (item.stat.type == VNodeType.SYMLINK) continue
            if (item.stat.type != VNodeType.DIRECTORY) continue
            ctx.outLine(item.name + ":")
            val rc = listDirectory(ctx, vfs, item.path, item.stat, s, true)
            if (rc != ExecContext.EXIT_OK) status = rc
        }
        return status
    }

    private fun sort(items: MutableList<Item>, s: Settings) {
        // -S and -t both replace the name order; a stable sort keeps the earlier one as the tie-break.
        val base: Comparator<Item> = when {
            s.bySize -> Comparator<Item> { a, b -> Cmds.sizeOf(b.stat).compareTo(Cmds.sizeOf(a.stat)) }
            s.byTime -> Comparator<Item> { a, b -> b.stat.mtimeMillis.compareTo(a.stat.mtimeMillis) }
            else -> Comparator<Item> { a, b -> fsCompareC(a.name, b.name) }
        }
        items.sortWith(if (s.reverse) base.reversed() else base)
    }

    private fun emit(ctx: ExecContext, items: List<Item>, s: Settings) {
        if (items.isEmpty()) return
        if (s.long) {
            for (item in items) ctx.outLine(longLine(item, s))
            return
        }
        val width = items.maxOf { fsWidth(it.name) }
        if (!s.multiColumn) {
            for (item in items) ctx.outLine(paint(item, s.color))
            return
        }
        val colWidth = width + 2
        val columns = maxOf(1, s.termWidth / colWidth)
        val rows = (items.size + columns - 1) / columns
        for (r in 0 until rows) {
            val sb = StringBuilder()
            for (c in 0 until columns) {
                val index = c * rows + r
                if (index >= items.size) break
                sb.append(paint(items[index], s.color))
                repeat(colWidth - fsWidth(items[index].name)) { sb.append(' ') }
            }
            ctx.outLine(sb.toString().trimEnd())
        }
    }

    private fun longLine(item: Item, s: Settings): String {
        val sb = StringBuilder()
        sb.append(Cmds.perms(item.stat)).append(' ')
        sb.append(Cmds.humanSize(Cmds.sizeOf(item.stat), s.human)).append(' ')
        sb.append(Cmds.timestamp(item.stat.mtimeMillis)).append(' ')
        sb.append(paint(item, s.color))
        // The metadata a listing already holds says whether this is a link, so no second stat.
        val target = if (item.stat.type == VNodeType.SYMLINK) fsLinkTarget(item.vfs, item.path) else null
        if (target != null) {
            sb.append(" -> ")
            sb.append(if (s.color) sgr("36") + target + sgr("0") else target)
        }
        return sb.toString()
    }

    /**
     * Directory blue, symlink cyan, executable green, anything that is not a regular file yellow.
     * A device node, a fifo and a socket are one thing to a [VStat] built from the JDK, so the
     * yellow covers every special file.
     */
    private fun paint(item: Item, color: Boolean): String {
        if (!color) return item.name
        val body = when {
            item.stat.type == VNodeType.SYMLINK -> sgr("36")
            item.stat.type == VNodeType.DIRECTORY -> sgr("1;34")
            item.stat.executable -> sgr("1;32")
            item.stat.type != VNodeType.FILE -> sgr("1;33")
            else -> return item.name
        }
        return body + item.name + sgr("0")
    }
}
