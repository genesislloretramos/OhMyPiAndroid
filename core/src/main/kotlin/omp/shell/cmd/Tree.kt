package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.PathResolver
import java.io.File

@CommandSpec(
    name = "tree",
    synopsis = "[-a] [-L N] [-d] [dir]",
    group = "files",
    notes = "-d lists directories only, -L limits the depth and -a includes dotfiles",
)
object Tree : FileCommand() {

    override val flagSpec = "ad"
    override val valueSpec = "L:"

    private const val BRANCH_MID = "├── "
    private const val BRANCH_END = "└── "
    private const val PIPE = "│   "
    private const val BLANK = "    "

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        val all = 'a' in flags
        val dirsOnly = 'd' in flags
        val rawDepth = options["L"]
        var maxDepth = Int.MAX_VALUE
        if (rawDepth != null) {
            val v = rawDepth.trim().toIntOrNull()
            if (v == null || v < 0) return ctx.fail("tree: invalid depth: '$rawDepth'")
            maxDepth = v
        }
        if (operands.size > 1) return ctx.fail("tree: only one directory at a time")

        val shown = operands.firstOrNull() ?: "."
        val path = Cmds.resolve(ctx, shown) ?: return ExecContext.EXIT_GENERAL_ERROR
        val root = File(path)
        if (!root.exists()) return ctx.fail("tree: $shown: No such file or directory")
        if (!root.isDirectory) return ctx.fail("tree: $shown: Not a directory")

        ctx.outLine(if (operands.isEmpty()) PathResolver.contract(ctx.session, path) else shown)
        val counts = IntArray(2)
        var status = ExecContext.EXIT_OK
        if (maxDepth > 0) {
            status = walk(ctx, root, "", 1, maxDepth, all, dirsOnly, counts)
        }
        if (status == ExecContext.EXIT_INTERRUPTED) return status
        ctx.outLine()
        ctx.outLine(
            "${counts[0]} ${plural(counts[0], "directory", "directories")}, " +
                "${counts[1]} ${plural(counts[1], "file", "files")}",
        )
        return status
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many

    private fun walk(
        ctx: ExecContext,
        dir: File,
        prefix: String,
        depth: Int,
        maxDepth: Int,
        all: Boolean,
        dirsOnly: Boolean,
        counts: IntArray,
    ): Int {
        val entries = Cmds.listDir(ctx, "tree", dir) ?: return ExecContext.EXIT_GENERAL_ERROR
        val visible = entries.filter { all || !it.name.startsWith(".") }.sortedBy { it.name }
        for (i in visible.indices) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val entry = visible[i]
            val last = i == visible.size - 1
            val link = fsIsLink(entry)
            val isDir = entry.isDirectory
            if (!dirsOnly || isDir) {
                counts[if (isDir) 0 else 1]++
                val target = if (link) " -> " + (fsLinkTarget(entry) ?: "") else ""
                ctx.outLine(prefix + (if (last) BRANCH_END else BRANCH_MID) + entry.name + target)
            }
            if (isDir && !link && depth < maxDepth) {
                val rc = walk(ctx, entry, prefix + (if (last) BLANK else PIPE), depth + 1, maxDepth, all, dirsOnly, counts)
                if (rc != ExecContext.EXIT_OK) return rc
            }
        }
        return ExecContext.EXIT_OK
    }
}
