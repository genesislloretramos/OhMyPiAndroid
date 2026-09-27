package omp.vm.cmd

import omp.shell.cmd.Cmds
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.VNodeType
import omp.shell.fs.VStat

/**
 * `ls` inside the namespace. One name per line, and options only where a second answer is worth
 * having: the point of this one is not to be a better `ls` than the phone's — it is to be an `ls`
 * whose answer came from the mount table, so `ls /` lists a VM, `ls /mnt/android` lists the phone's
 * storage, and `ls -l /usr/bin/ls` shows the mode a file in the rootfs really has.
 */
@CommandSpec(
    name = "ls",
    synopsis = "[-a] [-l] [PATH ...]",
    group = "vm",
    notes = "lists through the Vfs, so /proc and /sys are directories like any other",
)
object VmLs : FileCommand() {
    override val flagSpec = "al"
    override val longOptions = mapOf("all" to false, "long" to false)

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val showAll = flags.contains('a') || options.containsKey("all")
        val long = flags.contains('l') || options.containsKey("long")
        val targets = if (operands.isEmpty()) listOf(".") else operands
        var status = ExecContext.EXIT_OK
        for (op in targets) {
            val path = resolvePath(ctx, op) ?: return ExecContext.EXIT_GENERAL_ERROR
            try {
                for (entry in ctx.session.vfs.readDir(path)) {
                    if (!showAll && entry.name.startsWith(".")) continue
                    ctx.outLine(if (long) longLine(ctx, "$path/${entry.name}", entry.name) else entry.name)
                }
            } catch (e: FsException) {
                if (e.errno == FsErrno.NOT_A_DIRECTORY) {
                    // `ls somefile` prints the file, which is what ls(1) does and what makes
                    // `ls /usr/bin/ls` answer at all.
                    val name = path.substringAfterLast('/')
                    ctx.outLine(
                        when {
                            long -> longLine(ctx, path, name)
                            showAll || !name.startsWith(".") -> path
                            else -> path
                        },
                    )
                    continue
                }
                if (reportFsError(ctx, op, e) != ExecContext.EXIT_OK) status = ExecContext.EXIT_GENERAL_ERROR
            }
        }
        return status
    }

    /** `perms size mtime name` for one entry, from the [Cmds] helpers the phone's `ls -l` uses. */
    private fun longLine(ctx: ExecContext, path: String, name: String): String {
        val stat = ctx.session.vfs.stat(path)
        return "${perms(stat)} ${Cmds.sizeOf(stat)} ${Cmds.timestamp(stat.mtimeMillis)} $name"
    }

    /**
     * The file's real mode, from [VStat.mode].
     *
     * Not [Cmds.perms], which deliberately shows only what *this app* can do — an app cannot read a
     * file's uid or group, so that listing keeps the group and other columns as dashes whatever the
     * mode says, which is the right answer for the phone's own filesystem. In here the mode is
     * knowable, and printing a 0755 program file as `-rwx------` would be the namespace lying about
     * its own disk. A backend with no POSIX mode to read still falls back to the phone's letters.
     */
    private fun perms(stat: VStat): String {
        if (stat.mode == 0) return Cmds.perms(stat)
        val sb = StringBuilder(10)
        sb.append(
            when (stat.type) {
                VNodeType.SYMLINK -> 'l'
                VNodeType.DIRECTORY -> 'd'
                VNodeType.FILE -> '-'
                else -> '?'
            },
        )
        for (shift in intArrayOf(6, 3, 0)) {
            val bits = (stat.mode shr shift) and 7
            sb.append(if (bits and 4 != 0) 'r' else '-')
            sb.append(if (bits and 2 != 0) 'w' else '-')
            sb.append(if (bits and 1 != 0) 'x' else '-')
        }
        return sb.toString()
    }
}
