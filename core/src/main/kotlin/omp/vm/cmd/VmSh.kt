package omp.vm.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.Vfs
import omp.vm.VmExec
import java.io.OutputStream

/**
 * The VM's own exec path, and the only reason this file exists.
 *
 * The host shell can run a VM command — dispatch is by name, and the VM's table is a
 * [omp.shell.exec.CommandTable] like any other — but it cannot *redirect* one, because its `>`
 * opens a `java.io.File` and there is no file at `/dev/full` on the phone. So the redirection is
 * done here, through the session's [Vfs], and a program path is turned into a command by [VmExec].
 *
 * What it is not: a shell. It splits words (single quotes, double quotes and a backslash, nothing
 * else), takes one `>` or `>>`, runs one command and returns its status. No pipelines, no globbing,
 * no `$`, no `;` — a pipeline is the host shell's job and it does that job well. Nothing here can
 * run anything that is not a registered command or a [VmExec] program file, which is the same
 * guarantee the phone gives: no `Runtime.exec`, no ELF, no native code.
 */
@CommandSpec(
    name = "sh",
    synopsis = "[-c] LINE | CMD [ARG ...]",
    group = "vm",
    notes = "redirection and program files through the Vfs; the host shell cannot redirect a namespace path",
)
object VmSh : Command {
    override fun run(ctx: ExecContext): Int {
        val line = when {
            ctx.args.isEmpty() -> return ctx.fail("sh: missing operand")
            ctx.args[0] == "-c" -> ctx.args.drop(1).joinToString(" ")
            else -> ctx.args.joinToString(" ")
        }
        val words = splitWords(line)
        if (words.isEmpty()) return ctx.fail("sh: missing operand")

        val redirect = findRedirect(words)
        val argv = words.subList(0, redirect.at)
        if (argv.isEmpty()) return ctx.fail("sh: missing operand")

        val (command, missing) = lookup(ctx, argv[0])
        if (command == null) return missing

        var out: OutputStream? = null
        val target = redirect.target
        if (target != null) {
            val path = resolvePath(ctx, target) ?: return ExecContext.EXIT_GENERAL_ERROR
            out = try {
                ctx.session.vfs.openWrite(path, redirect.append)
            } catch (e: FsException) {
                ctx.errLine("sh: $target: ${Errno.messageFor(e)}")
                return ExecContext.EXIT_GENERAL_ERROR
            }
        }

        val child = ExecContext(
            argv, ctx.stdin, out ?: ctx.stdout, ctx.stderr, ctx.env,
            ctx.services, ctx.session, ctx.isTty, ctx.cancelled,
        )
        return try {
            command.run(child)
        } catch (e: FsException) {
            // The device that failed is the one that said so: `echo x > /dev/full` loses its bytes
            // at the write, and the write is where the errno is.
            ctx.errLine("sh: ${e.path ?: argv[0]}: ${Errno.messageFor(e)}")
            ExecContext.EXIT_GENERAL_ERROR
        } finally {
            if (out != null) {
                try {
                    out.close()
                } catch (e: Exception) {
                    // The write already failed and was reported; a second complaint helps nobody.
                }
            }
        }
    }

    /**
     * A bare name is a command in the table; anything with a `/` is a file that has to be a program
     * file, and a file that is not one gets the phone's own `Exec format error` wording and the
     * phone's own exit status for it.
     *
     * @return the command, or null with the status to exit with.
     */
    private fun lookup(ctx: ExecContext, name: String): Pair<Command?, Int> {
        if (!name.contains('/')) {
            val command = ctx.session.table.lookup(name)
            if (command == null) ctx.errLine("sh: $name: command not found")
            return Pair(command, ExecContext.EXIT_NOT_FOUND)
        }
        val path = resolvePath(ctx, name) ?: return Pair(null, ExecContext.EXIT_NOT_FOUND)
        return try {
            Pair(VmExec.lookup(ctx.session.vfs, ctx.session.table, path), ExecContext.EXIT_OK)
        } catch (e: FsException) {
            // `FsException`'s own message is the errno, as the Vfs contract says, so the phone's
            // wording is composed here: a file that is not a program has to read exactly like a
            // file the phone will not run.
            val why = if (e.errno == FsErrno.INVALID_ARGUMENT) VmExec.NOT_A_PROGRAM else Errno.messageFor(e)
            ctx.errLine("sh: $name: $why")
            // 126, not 127: the file is there and simply is not something that can be run, which is
            // the verdict the phone's shell reaches for a binary it will not exec.
            val status = if (e.errno == FsErrno.INVALID_ARGUMENT) {
                ExecContext.EXIT_NOT_EXECUTABLE
            } else {
                ExecContext.EXIT_NOT_FOUND
            }
            Pair(null, status)
        }
    }

    private class Redirect(val at: Int, val target: String?, val append: Boolean)

    /** @return where the single `>` is, if there is one. The token after it is the target. */
    private fun findRedirect(words: List<String>): Redirect {
        for (i in words.indices) {
            val w = words[i]
            if (w == ">" || w == ">>") return Redirect(i, words.getOrNull(i + 1), w == ">>")
            if (w.startsWith(">") && w.length > 1) return Redirect(i, w.substring(1), false)
        }
        return Redirect(words.size, null, false)
    }

    /**
     * Words, with single quotes, double quotes and backslash honoured. No expansion of any kind:
     * the host shell has already done that, and doing it twice is how `$HOME` ends up wrong in one
     * of the two passes.
     */
    internal fun splitWords(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote = ' '
        var started = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && i + 1 < line.length -> {
                    cur.append(line[i + 1]); i++
                }
                quote != '\'' && c == '"' -> quote = if (quote == '"') ' ' else '"'
                quote == '\'' && c == '\'' -> quote = ' '
                quote == ' ' && c.isWhitespace() -> {
                    if (started) {
                        out += cur.toString(); cur.setLength(0); started = false
                    }
                }
                else -> {
                    cur.append(c); started = true
                }
            }
            i++
        }
        if (started) out += cur.toString()
        return out
    }
}
