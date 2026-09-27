package omp.vm.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.FsException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `uptime` inside the namespace, read out of the VM's own `/proc/uptime` and `/proc/loadavg` —
 * the same two files a real uptime reads, which is the only way to be sure they are right.
 */
@CommandSpec(
    name = "uptime",
    synopsis = "",
    group = "vm",
    notes = "reads /proc/uptime and /proc/loadavg from the namespace, so it cannot drift from what cat shows",
)
object VmUptime : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val uptime = read(ctx, "/proc/uptime") ?: return ExecContext.EXIT_GENERAL_ERROR
        val load = read(ctx, "/proc/loadavg")?.trim()?.split(" ")?.take(3)?.joinToString(" ") ?: "0.00 0.00 0.00"
        val seconds = uptime.trim().substringBefore(' ').toDoubleOrNull() ?: 0.0
        val days = (seconds / 86400).toInt()
        val hours = ((seconds % 86400) / 3600).toInt()
        val minutes = ((seconds % 3600) / 60).toInt()
        val up = if (days > 0) "$days day${if (days == 1) "" else "s"}, $hours:${minutes.toString().padStart(2, '0')}"
        else "$hours:${minutes.toString().padStart(2, '0')}"
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ctx.services.wallClockMillis()))
        ctx.outLine(" $clock  up $up,  1 user,  load average: $load")
        return ExecContext.EXIT_OK
    }

    private fun read(ctx: ExecContext, path: String): String? = try {
        String(ctx.session.vfs.readBytes(path), Charsets.UTF_8)
    } catch (e: FsException) {
        reportFsError(ctx, path, e)
        null
    }
}

/**
 * `uname` inside the namespace, from `/proc/uname`, in the field order uname(2) defines: sysname,
 * nodename, release, version, machine, domainname.
 */
@CommandSpec(
    name = "uname",
    synopsis = "[-asnrvmo]",
    group = "vm",
    notes = "reads /proc/uname from the namespace; the version string says there is no kernel",
)
object VmUname : FileCommand() {
    override val flagSpec = "asnrvmo"

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        // NUL-separated, the way the kernel writes it, so a device name with a space in it is still
        // one field; `-a` prints them joined by spaces, as uname(1) does.
        val text = try {
            String(ctx.session.vfs.readBytes("/proc/uname"), Charsets.UTF_8).trimEnd('\n')
        } catch (e: FsException) {
            return reportFsError(ctx, "/proc/uname", e)
        }
        val f = text.split(omp.vm.proc.ProcBackend.SEPARATOR)
        if (flags.isEmpty()) {
            ctx.outLine(f.getOrElse(0) { "Linux" })
            return ExecContext.EXIT_OK
        }
        if (flags.contains('a')) {
            ctx.outLine(f.joinToString(" "))
            return ExecContext.EXIT_OK
        }
        val sb = StringBuilder()
        if (flags.contains('s')) sb.append(f.getOrElse(0) { "Linux" }).append(' ')
        if (flags.contains('n')) sb.append(f.getOrElse(1) { "omp-vm" }).append(' ')
        if (flags.contains('r')) sb.append(f.getOrElse(2) { "unknown" }).append(' ')
        if (flags.contains('v')) sb.append(f.getOrElse(3) { "unknown" }).append(' ')
        if (flags.contains('m')) sb.append(f.getOrElse(4) { "unknown" }).append(' ')
        if (flags.contains('o')) sb.append("omp-vm").append(' ')
        ctx.outLine(sb.toString().trimEnd())
        return ExecContext.EXIT_OK
    }
}

/** `hostname`, from the namespace's `/proc/hostname`. */
@CommandSpec(
    name = "hostname",
    synopsis = "",
    group = "vm",
    notes = "the VM's name, which is the device name when the user set one",
)
object VmHostname : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val text = try {
            String(ctx.session.vfs.readBytes("/proc/hostname"), Charsets.UTF_8).trimEnd('\n')
        } catch (e: FsException) {
            return reportFsError(ctx, "/proc/hostname", e)
        }
        if (operands.isEmpty()) {
            ctx.outLine(text)
            return ExecContext.EXIT_OK
        }
        // Writing a hostname needs a syscall this app does not have; saying so beats pretending.
        ctx.errLine("hostname: setting the name needs CAP_SYS_ADMIN, which no app is granted")
        return ExecContext.EXIT_GENERAL_ERROR
    }
}
