package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.CommandTable
import omp.shell.exec.ExecContext

@CommandSpec(
    name = "command",
    synopsis = "NAME [args ...]",
    group = "builtins",
    notes = "runs NAME without alias expansion, which is its only job here; it never re-enters the executor",
)
object CommandCmd : Command {
    override fun run(ctx: ExecContext): Int {
        val rest = ctx.argv.drop(1)
        if (rest.isEmpty()) return ExecContext.EXIT_OK
        val target = ctx.session.table.lookup(rest[0]) ?: return reportUnrunnable(ctx, rest[0])
        // The child sees its own argv, so the inner command's `name` is the command it runs and a
        // redirect or pipe it sets up is its own business.
        val child = ExecContext(
            rest, ctx.stdin, ctx.stdout, ctx.stderr, ctx.env,
            ctx.services, ctx.session, ctx.isTty, ctx.cancelled,
        )
        return target.run(child)
    }
}
