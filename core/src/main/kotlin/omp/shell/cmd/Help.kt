package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.CommandTable
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage

@CommandSpec(
    name = "help",
    synopsis = "[cmd]",
    group = "builtins",
    notes = "everything below is read from the command table, so it cannot fall out of step with what is registered",
)
object Help : FileCommand() {

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        if (operands.size > 1) {
            ctx.errLine("help: too many arguments")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        val topic = operands.firstOrNull()
        return if (topic == null) listAll(ctx) else entry(ctx, topic)
    }

    private fun listAll(ctx: ExecContext): Int {
        for ((group, names) in ctx.session.table.byGroup()) {
            ctx.outLine()
            ctx.outLine(capitalize(group) + ":")
            for (name in names) {
                val synopsis = ctx.session.table.specOf(name)?.synopsis ?: continue
                val tail = when {
                    synopsis.isEmpty() -> ""
                    synopsis.startsWith(ALIAS_PREFIX) -> "  ($synopsis)"
                    else -> " $synopsis"
                }
                ctx.outLine("  $name$tail")
            }
        }
        ctx.outLine()
        ctx.outLine("Deliberately not registered, and the reason:")
        val width = UNREGISTERED.maxOf { it.first.length }
        for ((name, reason) in UNREGISTERED) {
            ctx.outLine("  " + name.padEnd(width) + "  " + reason)
        }
        return ExecContext.EXIT_OK
    }

    private fun entry(ctx: ExecContext, topic: String): Int {
        val spec = ctx.session.table.specOf(topic) ?: run {
            ctx.errLine("help: no help topics match '$topic'")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        ctx.outLine("${spec.name}  (${spec.group})")
        if (spec.synopsis.startsWith(ALIAS_PREFIX)) {
            ctx.outLine("  ${spec.synopsis}")
        } else {
            val usage = if (spec.synopsis.isEmpty()) spec.name else "${spec.name} ${spec.synopsis}"
            ctx.outLine("  usage: $usage")
        }
        if (spec.notes.isNotEmpty()) ctx.outLine("  note: ${spec.notes}")
        return ExecContext.EXIT_OK
    }

    private fun capitalize(s: String): String =
        if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)

    /** [CommandTable.registerAlias] records an alias's synopsis in exactly this shape. */
    private const val ALIAS_PREFIX = "alias of "

    /**
     * Read out of AOSP's own policy, which is compiled in and cannot be relaxed by an app. Each
     * line is a decision, not an omission: the alternative would be a command that prints plausible
     * fiction.
     */
    private val UNREGISTERED = listOf(
        "lsblk" to "/sys is closed to apps by app_neverallows.te, so there is no block-device table to read",
        "netstat" to "/proc/net is excluded from untrusted apps by app.te; ifconfig and ip read the same facts over netlink",
        "dmesg" to "the kernel log buffer needs CAP_SYSLOG, which no app is granted",
        "logcat" to "READ_LOGS is a signature|privileged permission, so the log is not readable by an ordinary app at all",
        "chmod" to "the kernel honours a mode change only on a file this app owns, so elsewhere it would silently do nothing",
        "chown" to "giving a file away needs CAP_CHOWN, and an app's group is its own uid, so there is nothing to chown to",
        "mount -o" to "mount needs CAP_SYS_ADMIN and /proc/mounts is closed; mount itself lists the volumes from StorageManager",
        "su" to "no su binary is executable by an app, and SELinux denies the app-to-shell domain transition",
        "sudo" to "the same, and setuid bits mean nothing to an app because it has nothing to elevate to",
        "ping" to "raw ICMP needs CAP_NET_RAW; the system ping is in the AID_INET group, which an app is not in",
        "traceroute" to "the same raw-socket capability, and the system binary is not executable by an app",
        "iptables" to "netfilter administration needs CAP_NET_ADMIN, and /proc/net is closed to apps",
        "input" to "injecting key events needs the signature|privileged INJECT_EVENTS permission",
        "svc" to "starting a service means writing the init property ctl.start, which only init (uid 0) may do",
        "setprop" to "writing any system property needs that same init-stage control; this shell reads properties and never writes them",
        "settings put" to "WRITE_SECURE_SETTINGS is signature|privileged; settings get and settings list are registered because reads are allowed",
        "dumpsys <other>" to "the framework serves a service dump only to callers allowed to see it; battery, meminfo and display are answered because this app implements them",
        "fg" to "a background job here is genuinely detached: it keeps running and there is no stopped job to resume",
        "bg" to "nothing in this shell suspends a foreground job, so there is no stopped job to continue; jobs, wait and kill are the whole story",
        "ps (all users)" to "another app's per-pid proc tree is closed by the app-domain neverallow, so ps lists only this app's own process; /proc/meminfo is the one /proc file an app may read",
        "loadavg" to "/proc/stat, /proc/uptime, /proc/loadavg, /proc/mounts and /proc/vmstat are closed to untrusted apps by app_neverallows.te; uptime and top take their numbers from SystemClock and /proc/meminfo instead",
    )
}
