package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.VNodeType

@CommandSpec(
    name = "rm",
    synopsis = "[-rf] file ...",
    group = "files",
    notes = "never prompts and has no -i: there is no second chance once it is running",
)
object Rm : FileCommand() {

    override val flagSpec = "rf"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        if (operands.isEmpty()) return ctx.fail("rm: missing operand")
        val recursive = 'r' in flags
        val force = 'f' in flags
        val vfs = ctx.session.vfs
        var status = ExecContext.EXIT_OK
        for (op in operands) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val path = Cmds.resolve(ctx, op) ?: run {
                if (!force) status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val stat = fsStatOrNull(vfs, path)
            if (stat == null) {
                // -f silences a missing operand, not a failure to remove something that is there.
                if (!force) {
                    ctx.errLine("rm: $op: No such file or directory")
                    status = ExecContext.EXIT_GENERAL_ERROR
                }
                continue
            }
            // The name, not the target: `rm link` unlinks a link, and only a real directory needs
            // `-r`. The resolver is lexical precisely so that this command can tell the difference.
            if (stat.type == VNodeType.DIRECTORY) {
                if (!recursive) {
                    ctx.errLine("rm: $op: Is a directory")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                }
                if (!fsDeleteTree(ctx, "rm", vfs, path, ctx.cancelled)) status = ExecContext.EXIT_GENERAL_ERROR
            } else if (!fsDeleteFile(ctx, "rm", op, vfs, path)) {
                status = ExecContext.EXIT_GENERAL_ERROR
            }
        }
        if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
        return status
    }
}
