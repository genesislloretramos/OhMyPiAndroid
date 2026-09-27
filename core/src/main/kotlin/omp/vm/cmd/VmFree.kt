package omp.vm.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.FsException

/**
 * `free` inside the namespace, parsed out of this VM's own `/proc/meminfo` — the same way the
 * phone's is parsed out of the kernel's, which is the point: one reader, two files.
 *
 * Four columns, because the generated `/proc/meminfo` carries four figures: total, free and
 * available are read from it, and `used` is total minus available, which is the only arithmetic
 * these lines support. Real `free` also prints `shared` and `buff/cache`; the platform gives an app
 * no way to know either, so they are left out rather than printed as a zero — and the command's
 * help note says so, so a missing column reads as an admission instead of an oversight.
 */
@CommandSpec(
    name = "free",
    synopsis = "[-m]",
    group = "vm",
    notes = "reads the namespace's own /proc/meminfo, so it cannot disagree with cat /proc/meminfo; " +
        "no shared or buff/cache column, because the platform reports neither",
)
object VmFree : FileCommand() {
    override val flagSpec = "m"

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val text = try {
            String(ctx.session.vfs.readBytes("/proc/meminfo"), Charsets.UTF_8)
        } catch (e: FsException) {
            return reportFsError(ctx, "/proc/meminfo", e)
        }
        val values = HashMap<String, Long>()
        for (line in text.lines()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon)
            val number = line.substring(colon + 1).trim().substringBefore(' ').toLongOrNull() ?: continue
            values[key] = number
        }
        val total = values["MemTotal"] ?: 0L
        // MemFree, not MemAvailable: available is what the kernel would let a new process have,
        // free is what is on the free list, and a `free` column reporting the larger of the two is
        // a number the user cannot act on.
        val freeKb = values["MemFree"] ?: 0L
        val available = values["MemAvailable"] ?: freeKb
        val used = (total - available).coerceAtLeast(0L)
        fun fmt(kb: Long): String = if (flags.contains('m')) "${kb / 1024}" else kb.toString()

        ctx.outLine("              total        used        free     available")
        ctx.outLine(
            "Mem:    ${fmt(total).padStart(10)} ${fmt(used).padStart(12)} ${fmt(freeKb).padStart(12)} ${fmt(available).padStart(12)}"
        )
        ctx.outLine("Swap:   ${fmt(values["SwapTotal"] ?: 0L).padStart(10)} ${fmt(0L).padStart(12)} ${fmt(values["SwapFree"] ?: 0L).padStart(12)}")
        return ExecContext.EXIT_OK
    }
}
