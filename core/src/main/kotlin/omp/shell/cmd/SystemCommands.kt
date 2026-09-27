package omp.shell.cmd

import omp.shell.exec.CommandTable
import omp.vm.VmCommand
import omp.vm.launcher.OmpCommand
import omp.vm.doctor.DoctorCommand

/**
 * Commands that report on the device itself. They reach the platform only through
 * `PlatformServices`; the framework-specific ones (`getprop`, `pm`, `dumpsys`, `am`, `settings`,
 * `wm`, `screencap`, `curl`, `hostname`, `ifconfig`, `ip`) live in the `:app` module because they
 * need APIs that have no place in a JVM-only interface.
 */
object SystemCommands {
    fun register(table: CommandTable) {
        table.register(Uname, Uptime, Date, Free, Id, Whoami, Ps, Top, Mount, Df)
        // The door into the namespace. It is here rather than in `:app` because the namespace is
        // made of the same Kotlin commands this table already holds, and a `vm` that existed only
        // in a phone build would make every JVM test of the shell a lie about what ships.
        table.register(VmCommand())
        // The conversation launcher, and the one command both namespaces share: it asks the
        // session's own Vfs where the container is, so the same object manages Documents/omp here
        // and /mnt/omp in there, and the VM inherits it with the copy.
        table.register(OmpCommand())
        // The diagnostic, next to the two things it reports on. It is here for the same reason
        // `vm` is: a user holding a phone needs it in the shell, on the phone's own filesystem,
        // and a doctor that lived only in `:app` would make every JVM test of it a lie.
        table.register(DoctorCommand)
    }
}
