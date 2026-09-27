package omp.vm.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext

/**
 * What `vm` answers inside the namespace.
 *
 * The VM's table is a copy of the phone's, so the phone's `vm` would arrive here with it — and a
 * `vm` that opened *another* namespace from inside this one would be a nesting this shell cannot
 * represent: one Screen, one InputChannel, one Back button, and a `SessionHost` that is one deep
 * by design. So the name is registered again over the top of the phone's, and what it answers with
 * is the reason.
 *
 * A refusal in one line beats `sh: vm: command not found`, which is true and says nothing about
 * why a name the phone has does not work here.
 */
@CommandSpec(
    name = "vm",
    synopsis = "",
    group = "vm",
    notes = "refused inside the namespace: the VM you are in is the one this command opens",
)
object VmInside : Command {
    override fun run(ctx: ExecContext): Int {
        ctx.errLine(
            "vm: already inside the VM — this session is the namespace, so there is nothing left to open. " +
                "Type exit to go back to the phone.",
        )
        return ExecContext.EXIT_GENERAL_ERROR
    }
}
