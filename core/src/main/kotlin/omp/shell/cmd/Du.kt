package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.io.File

@CommandSpec(
    name = "du",
    synopsis = "[-hsa] [-d N] file ...",
    group = "files",
    notes = "sizes are 1K blocks unless -h; an unreadable subtree is reported on stderr and the walk carries on",
)
object Du : FileCommand() {

    override val flagSpec = "hsa"
    override val valueSpec = "d:"
    override val longOptions = mapOf("max-depth" to true)

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        if (operands.isEmpty()) return ctx.fail("du: missing operand")
        val rawDepth = options["d"] ?: options["max-depth"]
        var maxDepth = Int.MAX_VALUE
        if (rawDepth != null) {
            val v = rawDepth.trim().toIntOrNull()
            if (v == null || v < 0) return ctx.fail("du: invalid number: '$rawDepth'")
            maxDepth = v
        }
        if ('s' in flags) maxDepth = 0
        val walker = Walker(ctx, 'h' in flags, 'a' in flags, maxDepth)

        for (op in operands) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val path = Cmds.resolve(ctx, op) ?: run {
                walker.failed = true
                continue
            }
            val root = File(path)
            if (!root.exists()) {
                ctx.errLine("du: $op: No such file or directory")
                walker.failed = true
                continue
            }
            if (!root.isDirectory) {
                walker.row(op, Cmds.sizeOf(root))
                continue
            }
            walker.visit(root, op, 0, true)
            if (walker.cancelled) return ExecContext.EXIT_INTERRUPTED
        }
        return if (walker.failed) ExecContext.EXIT_GENERAL_ERROR else ExecContext.EXIT_OK
    }

    /**
     * Children are measured before their parent, which is the order `du` prints them in and the
     * order that makes a bottom-up total meaningful.
     */
    private class Walker(
        private val ctx: ExecContext,
        private val human: Boolean,
        private val all: Boolean,
        private val maxDepth: Int,
    ) {
        var failed = false
        var cancelled = false

        fun row(display: String, bytes: Long) {
            val text = if (human) Cmds.humanSize(bytes, true) else ((bytes + 1023) / 1024).toString()
            ctx.outLine("$text\t$display")
        }

        fun visit(file: File, display: String, depth: Int, root: Boolean): Long {
            if (ctx.cancelled.get()) {
                cancelled = true
                return 0
            }
            if (!file.isDirectory) {
                val size = Cmds.sizeOf(file)
                if (depth <= maxDepth && (all || root)) row(display, size)
                return size
            }
            var total = Cmds.sizeOf(file)
            val entries = Cmds.listDir(ctx, "du", file)
            if (entries == null) {
                failed = true
                return total
            }
            for (entry in entries.sortedBy { it.name }) {
                if (ctx.cancelled.get()) {
                    cancelled = true
                    return total
                }
                total += visit(entry, display.trimEnd('/') + "/" + entry.name, depth + 1, false)
            }
            if (depth <= maxDepth && (all || root)) row(display, total)
            return total
        }
    }
}
