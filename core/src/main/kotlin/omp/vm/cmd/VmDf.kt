package omp.vm.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.FsException
import java.util.Locale

/**
 * `df` inside the namespace, one row per mount. `/proc` and `/sys` have no space of their own and
 * say `0`, which is the truth: they are generated, so their size is not a number anyone can use.
 */
@CommandSpec(
    name = "df",
    synopsis = "[-h]",
    group = "vm",
    notes = "one row per mount, from Vfs.diskUsage; the generated filesystems are 0 because they hold nothing",
)
object VmDf : FileCommand() {
    override val flagSpec = "h"

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val kernel = kernelOf(ctx) ?: return ctx.fail("df: not inside a VM namespace")
        val human = flags.contains('h')
        ctx.outLine("Filesystem           Size  Used Avail Use% Mounted on")
        for (mount in kernel.mountTable()) {
            val usage = try {
                ctx.session.vfs.diskUsage(mount.target)
            } catch (e: FsException) {
                continue
            }
            val used = (usage.totalBytes - usage.freeBytes).coerceAtLeast(0L)
            val pct = if (usage.totalBytes > 0) String.format(Locale.US, "%d%%", (used * 100 / usage.totalBytes).toInt()) else "-"
            ctx.outLine(
                "${mount.target.padEnd(18)} ${omp.shell.cmd.Cmds.humanSize(usage.totalBytes, human).padStart(6)}" +
                    " ${omp.shell.cmd.Cmds.humanSize(used, human).padStart(6)} ${omp.shell.cmd.Cmds.humanSize(usage.freeBytes, human).padStart(6)}" +
                    " ${pct.padStart(4)} ${mount.target}"
            )
        }
        return ExecContext.EXIT_OK
    }

    private fun size(bytes: Long, human: Boolean): String {
        if (!human) return bytes.toString()
        if (bytes <= 0) return "0"
        val units = "KMGTPE"
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f%s", value, units[unit])
    }
}
