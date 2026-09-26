package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.util.Locale

/**
 * `ps` can only honestly report this app's own process: AOSP's `app_domain()` neverallow denies an
 * app any read access to another app's domain-labelled files, which is every file under `/proc/<pid>`.
 */
@CommandSpec(
    name = "ps",
    synopsis = "[-A] [-o COLS]",
    group = "system",
    notes = "lists only this app's own process: other processes' /proc entries are denied to apps by SELinux. " +
        "RSS is the process PSS from Debug.getMemoryInfo(), not a true RSS; PPID and VSZ are omitted rather than invented",
)
object Ps : FileCommand() {
    override val flagSpec = "A"
    override val valueSpec = "o:"

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val requested = options["o"]?.split(',')?.map { it.trim() } ?: DEFAULT_COLUMNS
        ctx.outLine(requested.joinToString(" ") { headerFor(it) })
        val row = psRow(ctx)
        ctx.outLine(requested.joinToString(" ") { col -> row[col.uppercase()] ?: "?" })
        return ExecContext.EXIT_OK
    }

    private fun headerFor(col: String): String = when (col.uppercase()) {
        "PID" -> "PID"
        "USER" -> "USER"
        "%CPU", "CPU" -> "%CPU"
        "%MEM", "MEM" -> "%MEM"
        "RSS" -> "RSS"
        "NAME", "CMD", "COMMAND" -> "NAME"
        else -> col.uppercase()
    }
}

private val DEFAULT_COLUMNS = listOf("PID", "USER", "%CPU", "%MEM", "RSS", "NAME")

/** Shared with `top`, which is the only other consumer of the process's own numbers. */
private var lastCpuTimes: Pair<Long, Long>? = null

fun psRow(ctx: ExecContext): Map<String, String> {
    val pssKb = ctx.services.processPssKb()
    val mem = ctx.services.systemMemory()
    val memPct = if (mem.totalBytes > 0) pssKb * 1024.0 * 100.0 / mem.totalBytes else 0.0
    val cpu = String.format(Locale.US, "%.1f", cpuPercent(ctx))
    val memStr = String.format(Locale.US, "%.1f", memPct)
    return mapOf(
        "PID" to ctx.services.processPid().toString(),
        "USER" to "app_${ctx.services.appUid()}",
        "%CPU" to cpu,
        "CPU" to cpu,
        "%MEM" to memStr,
        "MEM" to memStr,
        "RSS" to "${pssKb}K",
        "NAME" to ctx.services.processName(),
        "CMD" to ctx.services.processName(),
        "COMMAND" to ctx.services.processName(),
    )
}

/** Percentage since the previous call; the first call has no baseline and honestly reports 0. */
fun cpuPercent(ctx: ExecContext): Double {
    val now = ctx.services.processCpuTimes() ?: return 0.0
    val prev = lastCpuTimes
    lastCpuTimes = now
    if (prev == null || now.second <= prev.second) return 0.0
    val delta = now.first - prev.first
    if (delta <= 0) return 0.0
    return delta * 100.0 / (now.second - prev.second)
}
