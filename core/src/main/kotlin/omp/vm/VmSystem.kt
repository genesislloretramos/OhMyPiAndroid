package omp.vm

import omp.shell.InputChannel
import omp.shell.PlatformServices
import omp.shell.SessionHost
import omp.shell.ShellSession
import omp.shell.exec.CommandTable
import omp.term.Screen
import java.io.File

/**
 * An ultra-light Ubuntu Server, in process, on a phone.
 *
 * This is what the app calls, and it is three things: a [VmKernel] that owns the namespace, a
 * [CommandTable] that is the phone's own commands plus the VM userland, and a way to open a session
 * in it. There is no image to unpack, no kernel to boot, no process to fork and no permission to ask
 * for: the cost is a directory under the app's internal storage and a few hundred bytes of text
 * generated when something reads it.
 *
 * **It is a namespace, not a machine, and not a container.** `sudo` inside it changes the name the
 * shell answers to and crosses no boundary, because every file here is already the app's uid — the
 * one honest place that is said out loud is `/etc/motd`, and the real uid is one `cat` away in
 * `/sys/omp/android/uid`.
 *
 * [boot] is idempotent: it writes only what is missing, so a user's `/etc/motd` survives a hundred
 * boots and a boot is cheap enough to run every time the app starts.
 */
class VmSystem(
    val rootDir: File,
    val services: PlatformServices,
    val now: () -> Long,
) {
    /** The namespace kernel: mounts, processes, users, packages, units. */
    val kernel = VmKernel(rootDir, services, now)

    /**
     * The VM's commands: the phone's whole set, copied so a name registered on the phone cannot be
     * half-copied, with the userland registered over the top.
     *
     * Built from [CommandTable.global] at construction, which is why a [VmSystem] should be created
     * after `:app` has registered its platform commands — the copy is a snapshot, deliberately: a
     * command added to the phone later does not appear in a VM that is already running.
     */
    val table: CommandTable get() = kernel.commands

    /** The VM's users, read out of the rootfs through the namespace. */
    val users: VmUsers get() = kernel.users

    /** The distribution this userland claims to be, for a prompt or a bug report. */
    val prettyName: String get() = omp.vm.rootfs.Rootfs.PRETTY

    /**
     * Boots: the mount table, the rootfs, the dpkg database, one program file per command, the unit
     * files and the enabled units — in that order, one [BootLine] each, and one failed line for
     * every thing that did not work.
     */
    fun boot(): List<BootLine> {
        val lines = ArrayList(kernel.boot())
        lines += BootLine("userland: $prettyName, ${VmArch.of(services)}, ${omp.vm.rootfs.Rootfs.CODENAME}")
        lines += BootLine(
            "userland: ${kernel.packages.installedNames().size} package(s) installed, " +
                "${kernel.units.units().size} unit(s), ${table.names().size} commands",
        )
        return lines
    }

    /**
     * A REPL in the namespace, with the environment a real login would have.
     *
     * `HOME` and `PATH` come from the rootfs and the VM user, not from the phone: `PATH` is
     * `/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin` with no `/system/bin` in it,
     * because nothing in this namespace lives there. The prompt says `ubuntu` rather than `omp`,
     * which is the namespace's name and not the app's.
     *
     * [screen] and [input] are the phone's own, deliberately: `vm enter` passes the terminal it
     * was given, so the two REPLs are two shells on one screen rather than two windows. [host] is
     * the phone session's [SessionHost], and it is what makes the VM's REPL announce itself to the
     * app and hand the terminal back when it ends — an `exit` in here means "back to the phone
     * shell", never "close the app".
     */
    fun openSession(screen: Screen, input: InputChannel, host: SessionHost? = null): ShellSession {
        val user = users.current()
        val shell = ShellSession(services, screen, input, table, kernel.vfs, NAMESPACE)
        val session = shell.session
        session.cwd = user.home
        session.oldPwd = user.home
        session.env["HOME"] = user.home
        session.env["USER"] = user.name
        session.env["LOGNAME"] = user.name
        session.env["SHELL"] = user.shell
        session.env["PATH"] = PATH
        session.env["TERM"] = "xterm-256color"
        session.env["LANG"] = services.locale()
        session.env["HOSTNAME"] = hostName()
        session.env["PWD"] = session.cwd
        session.env["OLDPWD"] = session.oldPwd
        session.env["OMP_VM"] = "1"
        session.env["LSB_RELEASE"] = omp.vm.rootfs.Rootfs.VERSION_ID + ".1"
        // Inherited, not copied by the caller: a namespace that did not know it shared a terminal
        // with the phone's shell would not know to report itself, and an `exit` in here would be
        // the app's `exit` rather than the user's way back out.
        session.input = input
        session.host = host
        // The namespace's own pid 2 and the table's view of this shell's jobs, so `ps` and
        // /proc/<pid>/cmdline show the shell and whatever it is running. Registered per session
        // rather than once at boot because a VM with no session in it has no shell to list.
        kernel.processes.attach(session)
        kernel.startLoginShell(listOf(user.shell, "-i", user.name))
        return shell
    }

    /** Ends every process the VM started. The rootfs is untouched: a shutdown is not a factory reset. */
    fun shutdown() {
        kernel.shutdown()
    }

    private fun hostName(): String = try {
        String(kernel.vfs.readBytes("/etc/hostname"), Charsets.UTF_8).trim()
    } catch (e: Exception) {
        omp.vm.rootfs.Rootfs.hostName(services)
    }

    companion object {
        /** The prompt's name for this namespace. */
        const val NAMESPACE = "ubuntu"

        /** A real Ubuntu `PATH`, and nothing from the phone: no `/system/bin` is visible in here. */
        const val PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    }
}
