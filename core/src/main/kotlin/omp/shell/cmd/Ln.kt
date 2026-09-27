package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.PathResolver

@CommandSpec(
    name = "ln",
    synopsis = "[-s] target link",
    group = "files",
    notes = "symlink only: a hard link would be a second name for a file an app cannot reason about portably",
)
object Ln : FileCommand() {

    override val flagSpec = "s"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        if (operands.size != 2) return ctx.fail("ln: usage: ln [-s] target link")
        if ('s' !in flags) return ctx.fail("ln: hard links are not supported on Android")

        val targetRaw = operands[0]
        val linkRaw = operands[1]
        // The target stays as typed apart from `~`, because a relative link target is relative to
        // the link's own directory and must not be rewritten into an absolute path.
        val target = PathResolver.expandTilde(ctx.session, targetRaw)
        if (target.isEmpty()) return ctx.fail("ln: $targetRaw: No such file or directory")
        val linkPath = Cmds.resolve(ctx, linkRaw) ?: return ExecContext.EXIT_GENERAL_ERROR
        val vfs = ctx.session.vfs
        // `stat` does not follow a link, so a dangling one is a name that is already taken.
        if (fsExists(vfs, linkPath)) {
            return ctx.fail("ln: $linkRaw: File exists")
        }
        return try {
            vfs.symlink(target, linkPath)
            ExecContext.EXIT_OK
        } catch (e: SecurityException) {
            ctx.fail("ln: $linkRaw: Permission denied")
        } catch (e: java.io.IOException) {
            Errno.report(ctx, "ln", linkRaw, e)
        }
    }
}
