package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.io.File

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

    private class Item(val name: String, val file: File)

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

        var status = ExecContext.EXIT_OK
        for (raw in roots) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val path = Cmds.resolve(ctx, raw) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val file = File(path)
            if (!file.exists()) {
                ctx.errLine("ls: $raw: No such file or directory")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            if (file.isDirectory && !dirItself) {
                if (headers && !fromCwd) ctx.outLine("$raw:")
                val rc = listDirectory(ctx, file, settings, headers)
                if (rc != ExecContext.EXIT_OK) status = rc
            } else {
                emit(ctx, listOf(Item(raw, file)), settings)
            }
        }
        return status
    }

    private fun listDirectory(ctx: ExecContext, dir: File, s: Settings, headers: Boolean): Int {
        val entries = Cmds.listDir(ctx, "ls", dir) ?: return ExecContext.EXIT_GENERAL_ERROR
        val items = ArrayList<Item>(entries.size + 2)
        if (s.showAll) {
            items += Item(".", dir)
            items += Item("..", dir)
        }
        for (e in entries) items += Item(e.name, e)
        sort(items, s)
        emit(ctx, items, s)
        if (headers && s.recursive) ctx.outLine()
        if (!s.recursive) return ExecContext.EXIT_OK

        var status = ExecContext.EXIT_OK
        for (item in items) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            // A symlinked directory is listed but not descended into: `ls -R` does not follow links.
            if (item.name == "." || item.name == ".." || fsIsLink(item.file)) continue
            if (!item.file.isDirectory) continue
            ctx.outLine(item.name + ":")
            val rc = listDirectory(ctx, item.file, s, true)
            if (rc != ExecContext.EXIT_OK) status = rc
        }
        return status
    }

    private fun sort(items: MutableList<Item>, s: Settings) {
        // -S and -t both replace the name order; a stable sort keeps the earlier one as the tie-break.
        val base: Comparator<Item> = when {
            s.bySize -> Comparator<Item> { a, b -> Cmds.sizeOf(b.file).compareTo(Cmds.sizeOf(a.file)) }
            s.byTime -> Comparator<Item> { a, b -> b.file.lastModified().compareTo(a.file.lastModified()) }
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
        val file = item.file
        val sb = StringBuilder()
        sb.append(Cmds.perms(file)).append(' ')
        sb.append(Cmds.humanSize(Cmds.sizeOf(file), s.human)).append(' ')
        sb.append(Cmds.timestamp(file.lastModified())).append(' ')
        sb.append(paint(item, s.color))
        val target = if (fsIsLink(file)) fsLinkTarget(file) else null
        if (target != null) {
            sb.append(" -> ")
            sb.append(if (s.color) sgr("36") + target + sgr("0") else target)
        }
        return sb.toString()
    }

    /**
     * Directory blue, symlink cyan, executable green, anything that is not a regular file yellow.
     * `java.io` cannot tell a device node from a fifo, so the yellow covers every special file.
     */
    private fun paint(item: Item, color: Boolean): String {
        val file = item.file
        if (!color) return item.name
        val body = when {
            fsIsLink(file) -> sgr("36")
            file.isDirectory -> sgr("1;34")
            file.canExecute() -> sgr("1;32")
            !file.isFile -> sgr("1;33")
            else -> return item.name
        }
        return body + item.name + sgr("0")
    }
}
