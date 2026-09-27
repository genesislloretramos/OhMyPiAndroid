package omp.vm.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.vm.service.ServiceManager

/**
 * `journalctl` over the text files in `/var/log/journal`.
 *
 * It reads what the units actually wrote — every line here was appended by the VM kernel as the thing
 * it happened — and it says so in `--no-pager` free, binary-free, export-format-free output. The
 * flags are `-u`, `-n`, `-b` and `-p`, and they filter the real lines: `-b` keeps only the current
 * boot id, `-p` compares syslog priorities in their real order.
 */
@CommandSpec(
    name = "journalctl",
    synopsis = "[-u UNIT] [-n N] [-b] [-p PRIORITY]",
    group = "vm",
    notes = "reads /var/log/journal/*.log, which is plain text and not systemd's binary format",
)
object Journalctl : FileCommand() {
    override val flagSpec = "bu"
    // -p and -n take a value; -b does not. A priority that arrives as an operand is a filter that
    // silently does nothing, which is the worst shape an option can have.
    override val valueSpec = "n:p:"
    override val longOptions = mapOf(
        "unit" to true,
        "lines" to true,
        "boot" to false,
        "priority" to true,
        "no-pager" to false,
        "output" to true,
    )

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val units = unitsOf(ctx) ?: return ExecContext.EXIT_GENERAL_ERROR
        val unit = options["unit"] ?: operands.firstOrNull { !it.startsWith("-") }
        val lines = options["n"]?.toIntOrNull()
        val boot = 'b' in flags || options.containsKey("boot")
        val priority = options["p"] ?: options["priority"]

        var selected = units.journal(unit)
        if (boot) selected = selected.filter { it.boot == ServiceManager.bootId }
        if (priority != null) {
            val wanted = units.priorityRank(priority)
            selected = selected.filter { units.priorityRank(it.priority) <= wanted }
        }
        val shown = if (lines != null && lines > 0) selected.takeLast(lines) else selected
        if (shown.isEmpty()) {
            ctx.outLine("-- No entries --")
            return ExecContext.EXIT_OK
        }
        for (line in shown) ctx.outLine(line.format(omp.vm.rootfs.Rootfs.hostName(ctx.services)))
        return ExecContext.EXIT_OK
    }
}
