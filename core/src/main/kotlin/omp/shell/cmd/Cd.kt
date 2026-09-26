package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

@CommandSpec(
    name = "cd",
    synopsis = "[dir]",
    group = "files",
    notes = "no argument goes to \$HOME, `cd -` goes to OLDPWD and prints the new directory",
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
        val file = File(path)
        // A directory the app may not stat is Permission denied, not "No such file or directory".
        val attrs = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        } catch (e: IOException) {
            return ctx.fail("cd: $raw: ${Errno.messageFor(e)}")
        } catch (e: SecurityException) {
            return ctx.fail("cd: $raw: Permission denied")
        }
        if (!attrs.isDirectory) return ctx.fail("cd: $raw: Not a directory")

        ctx.session.oldPwd = ctx.session.cwd
        ctx.session.cwd = path
        ctx.env["OLDPWD"] = ctx.session.oldPwd
        ctx.env["PWD"] = path
        if (announce) ctx.outLine(path)
        return ExecContext.EXIT_OK
    }
}
