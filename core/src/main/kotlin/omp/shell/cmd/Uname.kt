package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

@CommandSpec(
    name = "uname",
    synopsis = "[-asnrvmo]",
    group = "system",
    notes = "release and version come from Build.VERSION, not from uname(2), which is not reachable from an app",
)
object Uname : FileCommand() {
    override val flagSpec = "asnrvmo"

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val props = ctx.services.buildProperties()
        fun prop(key: String, fallback: String) = props[key]?.takeIf { it.isNotBlank() } ?: fallback

        val kernelName = "Linux"
        val release = prop("ro.build.version.release", prop("ro.build.version.release", "unknown"))
        val version = prop("ro.build.version.incremental", prop("ro.build.fingerprint", "unknown"))
        val machine = prop("ro.product.cpu.abilist", prop("ro.product.cpu.abi", "unknown"))
        val system = "Android"

        if (flags.isEmpty()) {
            ctx.outLine(kernelName)
            return ExecContext.EXIT_OK
        }
        if (flags.contains('a')) {
            ctx.outLine("$kernelName $release $version $machine $system")
            return ExecContext.EXIT_OK
        }
        val sb = StringBuilder()
        if (flags.contains('s')) sb.append(kernelName).append(' ')
        if (flags.contains('n')) sb.append(system).append(' ')
        if (flags.contains('r')) sb.append(release).append(' ')
        if (flags.contains('v')) sb.append(version).append(' ')
        if (flags.contains('m')) sb.append(machine).append(' ')
        if (flags.contains('o')) sb.append("Android").append(' ')
        ctx.outLine(sb.toString().trimEnd())
        return ExecContext.EXIT_OK
    }
}
