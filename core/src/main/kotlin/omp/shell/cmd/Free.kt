package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

@CommandSpec(
    name = "free",
    synopsis = "[-h] [-m]",
    group = "system",
    notes = "from /proc/meminfo, the one /proc file AOSP policy lets an untrusted app read",
)
object Free : FileCommand() {
    override val flagSpec = "hm"

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val unit = 1024L
        val mem = readMeminfo(ctx) ?: run {
            ctx.errLine("free: /proc/meminfo: Permission denied")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        val total = mem["MemTotal"] ?: 0L
        val available = mem["MemAvailable"] ?: mem["MemFree"] ?: 0L
        val free = mem["MemFree"] ?: 0L
        val buffers = mem["Buffers"] ?: 0L
        val cached = (mem["Cached"] ?: 0L) + (mem["SReclaimable"] ?: 0L)
        val shared = mem["Shmem"] ?: 0L
        val buffCache = buffers + cached
        val used = total - free - buffCache

        fun fmt(kb: Long): String = if (flags.contains('m')) "${kb / 1024}"
        else Cmds.humanSize(kb * 1024, flags.contains('h'))

        ctx.outLine("              total        used        free      shared  buff/cache   available")
        ctx.outLine(
            "Mem:    ${fmt(total).padStart(10)} ${fmt(used).padStart(12)} ${fmt(free).padStart(12)} " +
                "${fmt(shared).padStart(12)} ${fmt(buffCache).padStart(12)} ${fmt(available).padStart(12)}"
        )
        return ExecContext.EXIT_OK
    }

    /** Through the seam, because `/proc/meminfo` is a file like any other and a phone may close it. */
    private fun readMeminfo(ctx: ExecContext): Map<String, Long>? = try {
        val out = HashMap<String, Long>()
        for (line in String(ctx.session.vfs.readBytes("/proc/meminfo"), Charsets.UTF_8).lines()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon)
            val value = line.substring(colon + 1).trim().split(' ')[0].toLongOrNull() ?: continue
            out[key] = value
        }
        out
    } catch (e: Exception) {
        null
    }
}
