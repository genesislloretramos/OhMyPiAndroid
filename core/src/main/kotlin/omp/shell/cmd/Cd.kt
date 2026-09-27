package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.fs.FsException
import omp.shell.fs.VNodeType

@CommandSpec(
    name = "cd",
    synopsis = "[dir]",
    group = "files",
    notes = "no argument goes to \$HOME, `cd -` goes to OLDPWD and prints the new directory; " +
        "\$PWD stays the logical path, so `..` is applied to what you typed, not to where a link points",
)
object Cd : Command {

    override fun run(ctx: ExecContext): Int {
        if (ctx.args.size > 1) return ctx.fail("cd: too many arguments")
        val arg = ctx.args.firstOrNull()
        val raw: String
        val announce: Boolean
        if (arg == null) {
            raw = ctx.env["HOME"] ?: ctx.services.homeDir()
            announce = false
        } else if (arg == "-") {
            val old = ctx.env["OLDPWD"] ?: ctx.session.oldPwd
            if (old.isEmpty()) return ctx.fail("cd: OLDPWD not set")
            raw = old
            announce = true
        } else {
            raw = arg
            announce = false
        }
        val path = Cmds.resolve(ctx, raw) ?: return ExecContext.EXIT_GENERAL_ERROR
        // A directory the app may not stat is Permission denied, not "No such file or directory",
        // and that is a distinction the seam's own errnos already make.
        // A link to a directory is a directory to `cd`, and `$PWD` keeps the name that was typed,
        // which is what makes `cd -` and a prompt mean the same thing afterwards.
        val stat = try {
            Cmds.statFollowed(ctx.session.vfs, path)
        } catch (e: FsException) {
            return ctx.fail("cd: $raw: ${e.errno.text}")
        }
        if (stat.type != VNodeType.DIRECTORY) return ctx.fail("cd: $raw: Not a directory")

        ctx.session.oldPwd = ctx.session.cwd
        ctx.session.cwd = path
        ctx.env["OLDPWD"] = ctx.session.oldPwd
        ctx.env["PWD"] = path
        if (announce) ctx.outLine(path)
        return ExecContext.EXIT_OK
    }
}
