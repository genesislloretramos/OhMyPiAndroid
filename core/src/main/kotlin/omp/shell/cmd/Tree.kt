package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.PathResolver
import omp.shell.fs.VNodeType
import omp.shell.fs.Vfs

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
        val vfs = ctx.session.vfs
        val root = fsStatOrNull(vfs, path) ?: return ctx.fail("tree: $shown: No such file or directory")
        if (root.type == VNodeType.SYMLINK) {
            // A link operand is a link, not the directory behind it: print it and stop, the way
            // `tree` has always printed a link it meets inside a tree.
            ctx.outLine(if (operands.isEmpty()) PathResolver.contract(ctx.session, path) else shown)
            ctx.outLine("$shown -> " + (fsLinkTarget(vfs, path) ?: ""))
            ctx.outLine()
            ctx.outLine("0 directories, 0 files")
            return ExecContext.EXIT_OK
        }
        if (root.type != VNodeType.DIRECTORY) return ctx.fail("tree: $shown: Not a directory")

        ctx.outLine(if (operands.isEmpty()) PathResolver.contract(ctx.session, path) else shown)
        val counts = IntArray(2)
        var status = ExecContext.EXIT_OK
        if (maxDepth > 0) {
            status = walk(ctx, vfs, path, "", 1, maxDepth, all, dirsOnly, counts)
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
        vfs: Vfs,
        dir: String,
        prefix: String,
        depth: Int,
        maxDepth: Int,
        all: Boolean,
        dirsOnly: Boolean,
        counts: IntArray,
    ): Int {
        val entries = Cmds.listDir(ctx, "tree", vfs, dir) ?: return ExecContext.EXIT_GENERAL_ERROR
        val visible = entries.filter { all || !it.name.startsWith(".") }.sortedBy { it.name }
        for (i in visible.indices) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val entry = visible[i]
            val child = fsChild(dir, entry.name)
            val last = i == visible.size - 1
            val link = entry.stat.type == VNodeType.SYMLINK
            // A link to a directory is a directory as far as the counts go, and is never walked.
            val isDir = if (link) fsIsDirFollowing(vfs, child, entry.stat) else entry.stat.type == VNodeType.DIRECTORY
            if (!dirsOnly || isDir) {
                counts[if (isDir) 0 else 1]++
                val target = if (link) " -> " + (fsLinkTarget(vfs, child) ?: "") else ""
                ctx.outLine(prefix + (if (last) BRANCH_END else BRANCH_MID) + entry.name + target)
            }
            if (isDir && !link && depth < maxDepth) {
                val rc = walk(ctx, vfs, child, prefix + (if (last) BLANK else PIPE), depth + 1, maxDepth, all, dirsOnly, counts)
                if (rc != ExecContext.EXIT_OK) return rc
            }
        }
        return ExecContext.EXIT_OK
    }
}
