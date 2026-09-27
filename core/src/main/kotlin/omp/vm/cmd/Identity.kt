package omp.vm.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.vm.VmKernel
import omp.vm.VmUser

/**
 * The identity commands, all reading the passwd and group files through the Vfs: `whoami`, `id` and
 * `groups` in the layout `id(1)` prints, and `su` to change who the session is.
 *
 * `sudo` gets its own file because it is the one that has to be honest about what it is: it is a
 * name change inside a namespace, not a privilege escalation, and both files say so.
 */
private fun kernelOrReport(ctx: ExecContext): VmKernel? = requireKernel(ctx)

/** The groups a user belongs to: the primary group first, then every group that lists them. */
internal fun groupNamesFor(kernel: VmKernel, user: VmUser): List<String> {
    val out = ArrayList<String>()
    kernel.users.group().firstOrNull { it.gid == user.gid }?.let { out += it.name }
    for (group in kernel.users.group()) {
        if (group.gid == user.gid) continue
        if (group.members.contains(user.name)) out += group.name
    }
    return out.distinct()
}

@CommandSpec(
    name = "whoami",
    synopsis = "",
    group = "vm",
    notes = "the VM user, from /etc/passwd; the app's real uid is in /sys/omp/android/uid",
)
object Whoami : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val kernel = kernelOrReport(ctx) ?: return ExecContext.EXIT_GENERAL_ERROR
        ctx.outLine(kernel.users.currentName())
        return ExecContext.EXIT_OK
    }
}

@CommandSpec(
    name = "id",
    synopsis = "[USER]",
    group = "vm",
    notes = "uid, gid and groups, in the layout id(1) prints, all read through the Vfs",
)
object Id : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val kernel = kernelOrReport(ctx) ?: return ExecContext.EXIT_GENERAL_ERROR
        val name = operands.firstOrNull() ?: kernel.users.currentName()
        val user = kernel.users.byName(name)
        if (user == null) {
            ctx.errLine("id: ‘$name’: no such user")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        val groups = groupNamesFor(kernel, user)
        ctx.outLine(
            "uid=${user.uid}(${user.name}) gid=${user.gid}(${groups.firstOrNull() ?: user.gid}) " +
                "groups=" + groups.joinToString(",") { "${kernel.users.group().firstOrNull { g -> g.name == it }?.gid ?: 0}($it)" }
        )
        return ExecContext.EXIT_OK
    }
}

@CommandSpec(
    name = "groups",
    synopsis = "[USER]",
    group = "vm",
    notes = "the groups the passwd and group files really define, not the phone's",
)
object Groups : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val kernel = kernelOrReport(ctx) ?: return ExecContext.EXIT_GENERAL_ERROR
        val name = operands.firstOrNull() ?: kernel.users.currentName()
        val user = kernel.users.byName(name)
        if (user == null) {
            ctx.errLine("groups: ‘$name’: no such user")
            return ExecContext.EXIT_NOT_FOUND
        }
        ctx.outLine("${user.name} : ${groupNamesFor(kernel, user).joinToString(" ")}")
        return ExecContext.EXIT_OK
    }
}
