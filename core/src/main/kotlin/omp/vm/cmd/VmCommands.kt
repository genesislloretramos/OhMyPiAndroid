package omp.vm.cmd

import omp.shell.exec.CommandTable

/**
 * The VM's command set: the shell's own builtins, plus the commands that have to speak the [Vfs]
 * rather than `java.io.File`.
 *
 * The list is short on purpose. A command that only reads its operands and writes stdout works
 * unchanged once the shell expands through the seam; the ones here are the ones that *open a path*
 * or that need the kernel — `cat`, `ls`, `head`, `wc`, `sh`, `ps`, `df`, `mount`, `free`, `uptime`,
 * `uname`, `hostname` — and each one is registered over the phone's version in the same table, so
 * inside the namespace the same name answers with a command that knows it is in one.
 *
 * The userland set (`apt`, `apt-get`, `dpkg`, `dpkg-query`, `systemctl`, `journalctl`, `whoami`,
 * `id`, `groups`, `su`, `sudo`, `login`) exists because the phone deliberately has none of them:
 * a package manager that downloads nothing and a service manager that forks nothing are only honest
 * inside a namespace, and inside the phone's session they would be lies.
 */
object VmCommands {

    fun register(table: CommandTable) {
        table.register(
            VmCat, VmHead, VmWc, VmLs, VmSh,
            VmPs, VmDf, VmMount, VmFree, VmUptime, VmUname, VmHostname,
        )
        // The userland: a package manager over a real on-disk database, systemd-lite over real unit
        // files and a real journal, and the identity commands.
        table.register(
            Apt, AptGet, Dpkg, DpkgQuery, Systemctl, Journalctl,
            Whoami, Id, Groups, Su, Sudo, Login,
        )
        // Over the top of the phone's `vm`, which arrived with the copy: see [VmInside] for why the
        // namespace does not carry the door that leads into it.
        table.register(VmInside)
    }
}
