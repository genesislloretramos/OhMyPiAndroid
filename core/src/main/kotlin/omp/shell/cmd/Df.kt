package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.VDiskUsage
import java.util.Locale

@CommandSpec(
    name = "df",
    synopsis = "[-h] [path ...]",
    group = "system",
    notes = "sizes come from getTotalSpace()/getUsableSpace() on each volume, not from a df binary",
)
object Df : FileCommand() {
    override val flagSpec = "h"

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val human = flags.contains('h')
        val vfs = ctx.session.vfs
        val rows = ArrayList<Row>()
        if (operands.isEmpty()) {
            for (v in ctx.services.storageVolumes()) {
                if (v.usableBytes <= 0L && v.totalBytes <= 0L) continue
                rows += Row(v.mountPoint, v.totalBytes, v.totalBytes - v.usableBytes, v.usableBytes)
            }
            // The two volumes Android does not report through StorageManager, asked of the
            // filesystem itself: `/data` only when it is there, and `/` only when nothing else
            // already claims the root.
            if (fsStatOrNull(vfs, "/data") != null) {
                rows += vfs.diskUsage("/data").toRow("/data")
            }
            if (rows.none { it.mount == "/" }) {
                rows += vfs.diskUsage("/").toRow("/")
            }
        } else {
            for (op in operands) {
                val path = Cmds.resolve(ctx, op) ?: return ExecContext.EXIT_GENERAL_ERROR
                if (Cmds.statFollowedOrNull(vfs, path) == null) {
                    ctx.errLine("df: $op: No such file or directory")
                    return ExecContext.EXIT_GENERAL_ERROR
                }
                rows += vfs.diskUsage(path).toRow(path)
            }
        }
        if (rows.isEmpty()) {
            ctx.errLine("df: no filesystems reported")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        ctx.outLine("Filesystem         ${"Size".padStart(9)}  ${"Used".padStart(9)}  ${"Avail".padStart(9)}  ${"Use%".padStart(4)} Mounted on")
        for (r in rows) {
            val size = Cmds.humanSize(r.total, human)
            val used = Cmds.humanSize(r.used, human)
            val avail = Cmds.humanSize(r.avail, human)
            val pct = if (r.total > 0) String.format(Locale.US, "%d%%", ((r.used * 100) / r.total).toInt()) else "-"
            ctx.outLine("${r.mount.padEnd(20)}${size.padStart(9)}  ${used.padStart(9)}  ${avail.padStart(9)}  ${pct.padStart(4)} ${r.mount}")
        }
        return ExecContext.EXIT_OK
    }

    private class Row(val mount: String, val total: Long, val used: Long, val avail: Long)

    private fun VDiskUsage.toRow(mount: String): Row = Row(mount, totalBytes, totalBytes - freeBytes, freeBytes)
}
