package omp.vm.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.vm.VmProcess

/**
 * `ps` inside the namespace, over the VM's own pid table.
 *
 * The pids here are the VM's own, and they are not the phone's: an app cannot
 * see or signal another process's pid on Android, so printing the host's numbers would be a lie
 * that `kill` could not honour. A `VmProcess` with no real process behind it is a bookkeeping entry,
 * and the `?` in the TTY column is where this says so — this is a namespace, not a scheduler.
 */
@CommandSpec(
    name = "ps",
    synopsis = "",
    group = "vm",
    notes = "lists the VM's own processes, pid 1 up; the phone's pids are not visible to an app and are not invented",
)
object VmPs : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val kernel = kernelOf(ctx) ?: return ctx.fail("ps: not inside a VM namespace")
        ctx.outLine("  PID TTY      STAT USER     COMMAND")
        for (p in kernel.processes.snapshot()) {
            val user = kernel.users.nameOf(p.uid) ?: p.uid.toString()
            ctx.outLine("${p.pid.toString().padStart(5)} ?        ${state(p)}     ${user.padEnd(9)} ${p.argv.joinToString(" ")}")
        }
        return ExecContext.EXIT_OK
    }

    private fun state(p: VmProcess): String = when (p.state) {
        'R' -> "R+"
        'S' -> "S "
        'Z' -> "Z "
        else -> "? "
    }
}
