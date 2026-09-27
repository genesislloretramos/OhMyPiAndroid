package omp.vm.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

/**
 * `mount` inside the namespace: the real mount table, which the phone's `mount` cannot show because
 * the kernel's own `/proc/mounts` is closed to an app. A mount that is not what its path suggests
 * prints its reason underneath, so an empty `/mnt/android` explains itself instead of looking like
 * a bug.
 *
 * A bind the user added is a line like any other — its host path as the device, the type the
 * platform will name for it, and the options it was mounted with — and a bind `/etc/fstab` asked
 * for that this kernel could not honour is printed under the table as *not mounted*, with the
 * reason. It is the one line here that is about something that is not there, and leaving it out
 * would make a program that came back after a restart find an empty directory where its files were.
 */
@CommandSpec(
    name = "mount",
    synopsis = "",
    group = "vm",
    notes = "the VM's own table; a mount with a caveat prints it, which is the point",
)
object VmMount : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val kernel = kernelOf(ctx) ?: return ctx.fail("mount: not inside a VM namespace")
        for (m in kernel.mountTable()) {
            ctx.outLine("${m.source} on ${m.target} type ${m.fstype} (${m.options})")
            m.note?.let { ctx.outLine("  note: $it") }
        }
        for ((user, reason) in kernel.missingBinds()) {
            ctx.outLine("${user.hostPath} on ${user.mountPoint}: not mounted — $reason")
        }
        return ExecContext.EXIT_OK
    }
}
