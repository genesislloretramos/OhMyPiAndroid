package omp.vm.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.FsErrno
import omp.vm.service.ServiceManager

/**
 * `systemctl` over the units this kernel really has.
 *
 * There is no `ssh.service`, because there is no sshd and nothing is listening: a unit that claimed
 * otherwise would be a lie with a status line attached to it. Every state word here comes out of the
 * unit's own state file and the VM's process table, so `status` cannot say "active" about a pid
 * that is not there.
 */
@CommandSpec(
    name = "systemctl",
    synopsis = "status|start|stop|restart|is-active|is-enabled|enable|disable|list-units|list-unit-files|daemon-reload UNIT",
    group = "vm",
    notes = "systemd-lite: the unit files and the journal are real, the processes are the VM's own",
)
object Systemctl : FileCommand() {
    override val flagSpec = "aqu"
    override val longOptions = mapOf(
        "no-pager" to false,
        "full" to false,
        "all" to false,
        "failed" to false,
    )

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val units = unitsOf(ctx) ?: return ExecContext.EXIT_GENERAL_ERROR
        val verb = operands.firstOrNull() ?: return listUnits(ctx, units)
        val name = operands.getOrNull(1)
        if (verb in setOf("list-units", "list-unit-files", "daemon-reload")) return when (verb) {
            "list-units" -> listUnits(ctx, units)
            "list-unit-files" -> listUnitFiles(ctx, units)
            else -> {
                ctx.outLine("Reloading unit files, nothing to do: the VM writes them as it boots.")
                ExecContext.EXIT_OK
            }
        }
        if (name == null) return ctx.fail("systemctl: $verb requires a unit name")
        return when (verb) {
            "status" -> status(ctx, units, name)
            "start" -> act(ctx, units.start(name), "started")
            "stop" -> act(ctx, units.stop(name), "stopped")
            "restart" -> act(ctx, units.restart(name), "restarted")
            "is-active" -> check(ctx, units, name) { it.describe().substringBefore(' ') }
            "is-enabled" -> check(ctx, units, name) { if (units.isEnabled(name)) "enabled" else "disabled" }
            "enable" -> act(ctx, units.enable(name), "enabled")
            "disable" -> act(ctx, units.disable(name), "disabled")
            "daemon-reload" -> ExecContext.EXIT_OK
            else -> {
                ctx.errLine("systemctl: invalid operation $verb")
                ExecContext.EXIT_USAGE
            }
        }
    }

    /** A refusal comes back as text from the manager, and a refusal is not a success. */
    private fun act(ctx: ExecContext, result: String, verb: String): Int {
        if (result.endsWith("unit not found") || result.endsWith(FsErrno.READ_ONLY.text)) {
            ctx.errLine("systemctl: $result")
            return ExecContext.EXIT_NOT_FOUND
        }
        ctx.outLine("$verb $result")
        return ExecContext.EXIT_OK
    }

    private fun check(ctx: ExecContext, units: ServiceManager, name: String, value: (ServiceManager.UnitState) -> String): Int {
        val state = units.stateOf(name)
        if (state == null) {
            ctx.outLine("unknown")
            return ExecContext.EXIT_NOT_FOUND
        }
        ctx.outLine(value(state))
        return if (value(state) == "active" || value(state) == "enabled") ExecContext.EXIT_OK else ExecContext.EXIT_GENERAL_ERROR
    }

    private fun status(ctx: ExecContext, units: ServiceManager, name: String): Int {
        val state = units.stateOf(name) ?: run {
            // 4 is the code systemd itself uses for "program or service status is unknown".
            ctx.errLine("systemctl: $name: loaded not-found")
            ctx.outLine("Active: inactive (dead)")
            return EXIT_UNIT_NOT_FOUND
        }
        ctx.outLine("● ${state.unit.name}.service - ${state.unit.description}")
        ctx.outLine("     Loaded: ${state.unit.unitFilePath} (${if (units.isEnabled(name)) "enabled" else "disabled"})")
        ctx.outLine("     Active: ${state.describe()}")
        if (state.sinceMillis > 0) ctx.outLine("   Since: ${omp.vm.cmd.VmTimes.format(state.sinceMillis)}")
        ctx.outLine("   Main PID: ${if (state.pid > 0) state.pid.toString() else "-"}")
        state.reason?.let { ctx.outLine("  Reason: $it") }
        ctx.outLine("    Tasks: 0 (this userland forks nothing)")
        val journal = units.journal(state.unit.name)
        if (journal.isEmpty()) {
            ctx.outLine("   Journal: no entries")
        } else {
            ctx.outLine("   Journal:")
            for (line in journal.takeLast(3)) ctx.outLine("     ${line.format(omp.vm.rootfs.Rootfs.hostName(ctx.services))}")
        }
        return if (state.state == "active") ExecContext.EXIT_OK else ExecContext.EXIT_GENERAL_ERROR
    }

    private fun listUnits(ctx: ExecContext, units: ServiceManager): Int {
        ctx.outLine("UNIT                    LOAD   ACTIVE SUB     DESCRIPTION")
        for (unit in units.units()) {
            val state = units.stateOf(unit.name)
            val active = state?.describe()?.substringBefore(' ') ?: "inactive"
            val sub = if (state != null && state.pid > 0) "running" else "dead"
            ctx.outLine("${unit.name}.service".padEnd(24) + "loaded " + active.padEnd(6) + sub.padEnd(7) + unit.description)
        }
        return ExecContext.EXIT_OK
    }

    private fun listUnitFiles(ctx: ExecContext, units: ServiceManager): Int {
        ctx.outLine("UNIT FILE                     STATE")
        for (unit in units.units()) {
            val state = if (units.isEnabled(unit.name)) "enabled" else "disabled"
            ctx.outLine(unit.unitFilePath.padEnd(30) + state)
        }
        return ExecContext.EXIT_OK
    }
}

/** What systemd returns for a unit it has never heard of. */
private const val EXIT_UNIT_NOT_FOUND = 4

/** One timestamp, formatted the way systemd prints it, for `Since:` in `systemctl status`. */
internal object VmTimes {
    fun format(millis: Long): String =
        java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", java.util.Locale.US)
            .format(java.util.Date(millis))
}
