package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.util.Locale

@CommandSpec(
    name = "uptime",
    synopsis = "",
    group = "system",
    notes = "uptime comes from SystemClock.elapsedRealtime(); /proc/uptime is closed to apps by SELinux",
)
object Uptime : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val seconds = ctx.services.monotonicMillis() / 1000
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(ctx.services.wallClockMillis()))
        val up = if (days > 0) "${days} day${if (days == 1L) "" else "s"}, ${hours}:${minutes.toString().padStart(2, '0')}"
        else "${hours}:${minutes.toString().padStart(2, '0')}"
        ctx.outLine(" $time  up $up,  1 user,  load average: unavailable to apps")
        return ExecContext.EXIT_OK
    }
}
