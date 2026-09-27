package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.util.Locale

@CommandSpec(
    name = "top",
    synopsis = "[-d SECS] [-n COUNT]",
    group = "system",
    notes = "system-wide CPU percentages and the load average are not exposed to apps, so they are not shown; " +
        "process list is this app's own only, and RSS is the process PSS",
)
object Top : FileCommand() {
    private const val ESC = 27.toChar()
    private const val CSI = "$ESC["
    private const val H = "${CSI}H"
    private const val CSI_2J = "${CSI}2J"
    private const val ALT_ENTER = "${CSI}?1049h"
    private const val ALT_LEAVE = "${CSI}?1049l"

    override val flagSpec = "b"
    override val valueSpec = "d:n:"

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val delayMs = ((options["d"]?.toDoubleOrNull() ?: 2.0) * 1000).toLong().coerceIn(200L, 60_000L)
        val iterations = options["n"]?.toIntOrNull() ?: Int.MAX_VALUE
        val screen = ctx.session.screen
        ctx.out("$ALT_ENTER$H$CSI_2J")
        var i = 0
        var status = ExecContext.EXIT_OK
        while (i < iterations) {
            if (ctx.cancelled.get() || drainQuit(ctx)) {
                status = ExecContext.EXIT_INTERRUPTED
                break
            }
            ctx.out("$CSI$H$CSI_2J")
            ctx.out(render(ctx, i))
            i++
            val deadline = System.currentTimeMillis() + delayMs
            while (System.currentTimeMillis() < deadline) {
                if (ctx.cancelled.get() || drainQuit(ctx)) break
                Thread.sleep(50)
            }
        }
        ctx.out("$ALT_LEAVE$H")
        return status
    }

    private fun drainQuit(ctx: ExecContext): Boolean {
        val stdin = ctx.stdin
        while (stdin.available() > 0) {
            val c = stdin.read()
            if (c < 0) return true
            if (c == 'q'.code || c == 0x03 || c == 0x1B) return true
        }
        return false
    }

    private fun render(ctx: ExecContext, iteration: Int): String {
        val sb = StringBuilder()
        val now = System.currentTimeMillis()
        val upSeconds = ctx.services.monotonicMillis() / 1000
        sb.append("Tasks: ").append(1).append(" (this app only), ").append(iteration + 1)
            .append(" refreshes\n")
        sb.append(Cmds.timestampSec(now)).append("  up ")
            .append(upSeconds / 3600).append(':').append((upSeconds % 3600) / 60)
            .append("  load average: unavailable to apps\n")
        val mem = readMeminfo(ctx)
        if (mem != null) {
            val total = mem["MemTotal"] ?: 0
            val avail = mem["MemAvailable"] ?: 0
            sb.append("Mem: ").append(String.format(Locale.US, "%.0fM", total / 1024.0))
                .append(" total, ").append(String.format(Locale.US, "%.0fM", (total - avail) / 1024.0))
                .append(" used, ").append(String.format(Locale.US, "%.0fM", avail / 1024.0)).append(" free\n")
        }
        sb.append("System-wide CPU usage is not exposed to apps; only this app's own process is listed.\n\n")
        sb.append("    PID USER         %CPU     %MEM       RSS NAME\n")
        val row = psRow(ctx)
        sb.append(String.format(Locale.US, "%7s %-12s %5s %6s %9s %s",
            row["PID"], row["USER"], row["%CPU"], row["%MEM"], row["RSS"], row["NAME"]))
        sb.append("\n\nPress q to quit.")
        return sb.toString()
    }

    /** Through the seam, for the same reason `free` reads it that way: a phone may close it. */
    private fun readMeminfo(ctx: ExecContext): Map<String, Long>? = try {
        val out = HashMap<String, Long>()
        for (line in String(ctx.session.vfs.readBytes("/proc/meminfo"), Charsets.UTF_8).lines()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            out[line.substring(0, colon)] = line.substring(colon + 1).trim().split(' ')[0].toLongOrNull() ?: continue
        }
        out
    } catch (e: Exception) {
        null
    }
}
