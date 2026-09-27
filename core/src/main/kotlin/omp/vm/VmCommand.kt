package omp.vm

import omp.shell.InputChannel
import omp.shell.PlatformServices
import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.printUsage
import omp.term.Screen
import java.io.File
import java.io.IOException

/**
 * `vm`: the door into [VmSystem], and the only command the app registers for it.
 *
 * The namespace is a real thing with a real directory behind it, so this command reports it the way
 * a real tool would — the path, the bytes, the units, whether `/mnt/android` is bound and why not
 * if it is not — and every answer comes out of the live objects rather than out of a string. The
 * one line it always ends `status` with is the one that matters: `root` in here is a name the VM
 * kernel answers to, and the app's real uid is one `cat /sys/omp/android/uid` away.
 *
 * **The [VmSystem] is built on first use, not at registration.** Constructing one makes the mount
 * table, and a table means directories on the phone's disk; `help` and every other command in the
 * shell would then be creating a rootfs they never asked for. It is also created per
 * [PlatformServices.appFilesDir] rather than once for the process, so a test that points the stub
 * somewhere else gets its own VM instead of the first one's.
 *
 * This is the one command in the shell that owns state, and it is kept out of the namespace: the
 * VM's table has `omp.vm.cmd.VmInside` under this name instead, so `vm` typed in there is refused
 * in one line rather than trying to open a second VM inside the first.
 */
@CommandSpec(
    name = "vm",
    synopsis = "[status|boot|enter|exec 'LINE'|mounts|mount HOSTPATH [MOUNTPOINT] [--read-only]|umount MOUNTPOINT|services|reset --force]",
    group = "system",
    notes = "an in-process Ubuntu namespace on a directory in the app's own storage; root in there is a name, not a privilege",
)
class VmCommand : Command {

    override fun run(ctx: ExecContext): Int {
        val args = ctx.args
        return when (val sub = args.firstOrNull()) {
            null -> status(ctx)
            "status" -> if (args.size == 1) status(ctx) else usage(ctx, sub)
            "boot" -> if (args.size == 1) boot(ctx) else usage(ctx, sub)
            "enter" -> if (args.size == 1) enter(ctx) else usage(ctx, sub)
            "exec" -> execLine(ctx, args.drop(1))
            "mounts" -> if (args.size == 1) mounts(ctx) else usage(ctx, sub)
            "services" -> if (args.size == 1) services(ctx) else usage(ctx, sub)
            "reset" -> reset(ctx, args.drop(1))
            "mount" -> mount(ctx, args.drop(1))
            "umount" -> umount(ctx, args.drop(1))
            else -> {
                ctx.errLine("vm: unknown subcommand '$sub'")
                printUsage(ctx.stderr, USAGE)
                ExecContext.EXIT_USAGE
            }
        }
    }

    // ---- status ------------------------------------------------------------------------

    private fun status(ctx: ExecContext): Int {
        val services = ctx.services
        val system = systemOf(services)
        val kernel = system.kernel
        ctx.outLine(
            if (kernel.isRunning()) "vm: booted, ${system.prettyName}, ${kernel.processes.size()} process(es) in the pid namespace"
            else "vm: not booted; nothing has been written yet, and 'vm enter' or 'vm boot' will do it",
        )
        ctx.outLine("disk: ${system.rootDir.absolutePath} (${sizeOnDisk(system.rootDir)} bytes on disk)")
        ctx.outLine("packages: ${kernel.packages.installedNames().size} installed")
        for (unit in kernel.units.units()) {
            val state = kernel.units.stateOf(unit.name)
            ctx.outLine("  unit ${unit.name}.service: ${state?.describe() ?: "unknown"}")
        }
        val bind = kernel.mountTable().firstOrNull { it.target == ANDROID_MOUNT }
        ctx.outLine(
            if (bind != null && bind.source == ANDROID_SOURCE) {
                "$ANDROID_MOUNT: bound to ${services.externalStorageDir()}"
            } else {
                "$ANDROID_MOUNT: not bound — ${bind?.note ?: "the kernel has no such mount"}"
            },
        )
        // The line that keeps this honest, printed by the one command that can be asked about it.
        ctx.outLine(
            "note: root inside the VM is a name the VM kernel answers to, not a privilege escalation on the phone; " +
                "the app's real uid is ${services.appUid()} and it is in /sys/omp/android/uid",
        )
        return ExecContext.EXIT_OK
    }

    private fun boot(ctx: ExecContext): Int {
        printBootLines(ctx, systemOf(ctx.services).boot())
        return ExecContext.EXIT_OK
    }

    // ---- enter -------------------------------------------------------------------------

    /**
     * The nested REPL: a real [omp.shell.ShellSession] over the VM's Vfs, on the phone's own
     * [omp.term.Screen] and [InputChannel], run here on this thread so the two shells are one
     * terminal. `exit`, Ctrl-D and Back all end it, and the `finally` is what makes all three mean
     * the same thing to the app: the VM session stops being the front one, and the phone shell is
     * what the user is back to.
     *
     * The status it returns is the VM's last status, so `$?` in the phone shell is the answer to
     * whatever the user last ran *in there*, which is the only reading that means anything.
     */
    private fun enter(ctx: ExecContext): Int {
        val input = ctx.session.input
            ?: return ctx.fail("vm: this session has no input channel; only an interactive shell can enter the VM")
        val system = systemOf(ctx.services)
        val cold = !system.kernel.isRunning()
        val bootLines = system.boot()
        if (cold) printBootLines(ctx, bootLines)

        val host = ctx.session.host
        val shell = system.openSession(ctx.session.screen, input, host)
        host?.sessionPushed(shell.session)
        return try {
            shell.run()
        } finally {
            host?.sessionPopped(shell.session)
            ctx.outLine()
            ctx.outLine("back on the phone; the VM is still booted and still on disk")
            ctx.flush()
        }
    }

    // ---- exec, mounts, services ---------------------------------------------------------

    /** One command line in the namespace, with this command's stdout, stderr and terminal-ness. */
    private fun execLine(ctx: ExecContext, operands: List<String>): Int {
        if (operands.isEmpty()) return usage(ctx, "exec")
        val services = ctx.services
        val system = systemOf(services)
        system.boot()
        // A throwaway terminal: the line is not a session, so it must not be able to prompt or to
        // steal a keypress. It reads the same streams this command was given.
        val shell = system.openSession(Screen(24, 80), InputChannel())
        return shell.shell.executeLine(
            operands.joinToString(" "),
            ctx.stdin,
            ctx.stdout,
            ctx.stderr,
            ctx.isTty,
        )
    }

    /**
     * The VM's own mount table, from the phone namespace, where `mount` would print the phone's.
     *
     * The binds `/etc/fstab` asked for and this kernel could not honour are printed under it rather
     * than left out: a mount point that is missing because a host directory was deleted is the one
     * thing in this table a user would otherwise have to guess about, and the boot line that
     * mentioned it is gone by the time they come and look.
     */
    private fun mounts(ctx: ExecContext): Int {
        val kernel = systemOf(ctx.services).kernel
        for (m in kernel.mountTable()) {
            ctx.outLine("${m.source} on ${m.target} type ${m.fstype} (${m.options})")
            m.note?.let { ctx.outLine("  note: $it") }
        }
        for ((user, reason) in kernel.missingBinds()) {
            ctx.outLine("${user.hostPath} on ${user.mountPoint}: not mounted — $reason")
        }
        return ExecContext.EXIT_OK
    }

    private fun services(ctx: ExecContext): Int {
        val units = systemOf(ctx.services).kernel.units
        val states = units.units().map { it.name to units.stateOf(it.name) }
        if (states.isEmpty()) {
            ctx.outLine("no unit files under ${omp.vm.service.ServiceManager.UNIT_DIR} yet; 'vm boot' writes them")
            return ExecContext.EXIT_OK
        }
        ctx.outLine("UNIT                            STATE")
        for ((name, state) in states) {
            val pid = state?.pid?.takeIf { it > 0 }?.let { " pid $it" } ?: ""
            ctx.outLine("${(name + ".service").padEnd(32)}${state?.describe() ?: "unknown"}$pid")
        }
        return ExecContext.EXIT_OK
    }


    // ---- mount, umount ------------------------------------------------------------------

    /**
     * `vm mount HOSTPATH [MOUNTPOINT] [--read-only]`: a real bind of a directory on this device.
     *
     * The VM is booted first, quietly, because the bind is recorded in `/etc/fstab` inside the
     * rootfs and a rootfs that has never been written has no fstab to record it in — the same
     * reason `vm exec` boots before it runs anything. The default mount point is the host
     * directory's own name under `/mnt`, which is what makes `/storage/emulated/0/Documents` land
     * at `/mnt/Documents` and read and write the same files the phone does.
     *
     * `--read-only` is the [omp.vm.Mount]'s flag and not a rule in this method: a program inside
     * the namespace is refused at the same seam the command was, with the same errno. The `vm mount: `
     * prefix on a refusal is added here rather than in [omp.vm.VmKernel], which has no idea whether
     * it is answering a command or a boot.
     */
    private fun mount(ctx: ExecContext, operands: List<String>): Int {
        val readOnly = operands.contains(READ_ONLY_FLAG)
        val paths = operands.filter { it != READ_ONLY_FLAG }
        val unknown = paths.filter { it.startsWith("-") }
        if (unknown.isNotEmpty()) {
            ctx.errLine("vm mount: unknown option '${unknown.first()}'")
            printUsage(ctx.stderr, "$USAGE_NAME mount HOSTPATH [MOUNTPOINT] [$READ_ONLY_FLAG]")
            return ExecContext.EXIT_USAGE
        }
        if (paths.isEmpty()) {
            printUsage(ctx.stderr, "$USAGE_NAME mount HOSTPATH [MOUNTPOINT] [$READ_ONLY_FLAG]")
            return ExecContext.EXIT_USAGE
        }
        if (paths.size > 2) {
            ctx.errLine("vm mount: a bind takes one directory and at most one mount point")
            printUsage(ctx.stderr, "$USAGE_NAME mount HOSTPATH [MOUNTPOINT] [$READ_ONLY_FLAG]")
            return ExecContext.EXIT_USAGE
        }
        val system = systemOf(ctx.services)
        system.boot()
        val result = system.kernel.mount(paths[0], paths.getOrNull(1), readOnly)
        return when (result) {
            is BindResult.Mounted -> {
                ctx.outLine("${result.user.hostPath} on ${result.user.mountPoint}: mounted ${if (readOnly) "read-only" else "read-write"}")
                ctx.outLine("${result.user.mountPoint} is that directory, not a copy; it is recorded in ${omp.vm.rootfs.Rootfs.FSTAB} and comes back at every boot")
                ExecContext.EXIT_OK
            }
            else -> ctx.fail("vm mount: ${result.describe()}")
        }
    }

    /**
     * `vm umount MOUNTPOINT`: takes a bind out of the table and out of `/etc/fstab`, so a later boot
     * does not put it back. A mount of the kernel's own is refused by name, and the message says
     * which one — `mount` inside the namespace has no way to take `/proc` away, and a tool that
     * quietly did nothing would be worse than one that says so.
     */
    private fun umount(ctx: ExecContext, operands: List<String>): Int {
        if (operands.size != 1) {
            if (operands.isEmpty()) ctx.errLine("vm umount: name the mount point to unmount")
            printUsage(ctx.stderr, "$USAGE_NAME umount MOUNTPOINT")
            return ExecContext.EXIT_USAGE
        }
        // Booted first, for the same reason `mount` boots: the answer has to be about the table the
        // user is looking at, and the binds in it are read out of `/etc/fstab` at boot.
        val system = systemOf(ctx.services)
        system.boot()
        val result = system.kernel.umount(operands[0])
        return when (result) {
            is BindResult.Unmounted -> {
                ctx.outLine("${result.user.mountPoint} is no longer mounted; it no longer showed ${result.user.hostPath}")
                ctx.outLine("the empty directory is left where it was, and the line is gone from ${omp.vm.rootfs.Rootfs.FSTAB}")
                ExecContext.EXIT_OK
            }
            else -> ctx.fail("vm umount: ${result.describe()}")
        }
    }

    // ---- reset -------------------------------------------------------------------------

    /**
     * A factory reset, and the only destructive thing this app can be talked into.
     *
     * It refuses without `--force`, and it says exactly which directory and how many bytes it
     * would take, because "delete the VM" is otherwise a thing a user cannot picture. With
     * `--force` the tree goes, the VM shuts down first so no half-live process table survives, and
     * the namespace is booted again so the next `vm` finds a working system rather than a gap.
     */
    private fun reset(ctx: ExecContext, operands: List<String>): Int {
        val unknown = operands.filter { it != "--force" }
        if (unknown.isNotEmpty()) {
            ctx.errLine("vm reset: unknown option '${unknown.first()}'")
            printUsage(ctx.stderr, "$USAGE_NAME reset --force")
            return ExecContext.EXIT_USAGE
        }
        val dir = systemOf(ctx.services).rootDir
        val usage = usageOf(dir)
        if (!dir.exists()) {
            ctx.outLine("vm reset: ${dir.absolutePath} does not exist; there is nothing to delete")
            return ExecContext.EXIT_OK
        }
        if (!operands.contains("--force")) {
            ctx.outLine("vm reset: this would delete ${dir.absolutePath} and everything under it, forever")
            ctx.outLine("vm reset: ${usage.files} file(s), ${usage.bytes} bytes on disk")
            ctx.outLine("vm reset: nothing was deleted; pass --force to do it")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        // Shutdown first, so no live process table is left pointing at files that are about to go,
        // then a *new* VmSystem: the old one's kernel made its root directory when it was
        // constructed, and a factory reset that left the kernel holding a mount rooted at a
        // directory that no longer exists would boot into nothing.
        systemOf(ctx.services).shutdown()
        val stuck = deleteTree(dir)
        if (stuck != null) return ctx.fail("vm reset: cannot delete ${stuck.absolutePath}")
        dropSystem()
        printBootLines(ctx, systemOf(ctx.services).boot())
        ctx.outLine("vm reset: removed ${usage.files} file(s) and ${usage.bytes} bytes; the namespace has been rebooted")
        return ExecContext.EXIT_OK
    }

    // ---- the system behind it -----------------------------------------------------------

    private var cachedPath: String? = null
    private var cached: VmSystem? = null

    /**
     * The one [VmSystem] this process is using, built on first use.
     *
     * Keyed by the resolved path rather than kept unconditionally, because the two callers that
     * can disagree about it are a test that moved the stub's storage and an app whose storage moved
     * under a restore; in both cases a stale [VmSystem] would be writing to a directory that is no
     * longer the one on disk. The clock is the platform's monotonic one, which is the only clock
     * that means the same thing across a suspend.
     */
    private fun systemOf(services: PlatformServices): VmSystem {
        val dir = File(services.appFilesDir(), DISK_DIR).absoluteFile
        val key = dir.path
        synchronized(this) {
            cached?.takeIf { cachedPath == key }?.let { return it }
            val fresh = VmSystem(dir, services) { services.monotonicMillis() }
            cached = fresh
            cachedPath = key
            return fresh
        }
    }

    /**
     * Forgets the cached [VmSystem] so the next call builds a new one.
     *
     * Only [reset] uses this, and only because a kernel's mount table is decided once in its
     * constructor: a tree that has just been deleted underneath it cannot be re-mounted by asking
     * it nicely. A fresh kernel makes the directory and the mount table together, which is what
     * "rebooted into a new rootfs" actually means.
     */
    private fun dropSystem() = synchronized(this) {
        cached = null
        cachedPath = null
    }

    private fun printBootLines(ctx: ExecContext, lines: List<BootLine>) {
        for (line in lines) {
            if (line.failed) ctx.errLine(line.text) else ctx.outLine(line.text)
        }
        ctx.flush()
    }

    private fun usage(ctx: ExecContext, sub: String): Int {
        ctx.errLine("vm: $sub takes no arguments")
        printUsage(ctx.stderr, USAGE)
        return ExecContext.EXIT_USAGE
    }

    private data class DiskUsage(val files: Int, val bytes: Long)

    /** The real number of files and bytes the VM's disk occupies, counting what is really there. */
    private fun usageOf(dir: File): DiskUsage {
        var files = 0
        var bytes = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val file = stack.removeLast()
            val link = isLink(file)
            if (file.isDirectory && !link) {
                for (child in file.listFiles() ?: emptyArray()) stack.addLast(child)
                continue
            }
            files++
            // A symlink is a name, not a copy; counting the target's size would report bytes the
            // VM never stores, and the one number here that has to be real is the byte count.
            if (!link) bytes += file.length()
        }
        return DiskUsage(files, bytes)
    }

    private fun sizeOnDisk(dir: File): Long = usageOf(dir).bytes

    /**
     * Bottom-up, because a directory has to be empty before it can go, and a symlink is unlinked
     * rather than followed: a link points at a name inside the rootfs, and following one that
     * pointed elsewhere would delete something the user never agreed to.
     *
     * @return the first path that would not go, or null when the tree is gone. Naming it beats
     * "cannot delete" with nothing after it, because the user is holding the only permission that
     * could fix it and needs to know which file is in the way.
     */
    private fun deleteTree(dir: File): File? {
        for (child in dir.listFiles() ?: emptyArray()) {
            val stuck = if (isLink(child) || !child.isDirectory) {
                if (child.delete()) null else child
            } else {
                deleteTree(child)
            }
            if (stuck != null) return stuck
        }
        return if (dir.delete()) null else dir
    }

    /**
     * A link's own path and the path it resolves to differ; a real file's do not. `java.nio` says
     * this in one call, and the question is worth one comparison more than an API level.
     */
    private fun isLink(file: File): Boolean = try {
        file.canonicalPath != file.absolutePath
    } catch (e: IOException) {
        false
    }

    companion object {
        /** The VM's disk, under the app's own storage: `files/rootfs`. */
        const val DISK_DIR = "rootfs"

        private const val ANDROID_MOUNT = "/mnt/android"
        private const val ANDROID_SOURCE = "shared-storage"
        private const val READ_ONLY_FLAG = "--read-only"
        private const val USAGE_NAME = "vm"
        private val USAGE = "$USAGE_NAME [status|boot|enter|exec 'LINE'|mounts|" +
            "mount HOSTPATH [MOUNTPOINT] [$READ_ONLY_FLAG]|umount MOUNTPOINT|services|reset --force]"
    }
}
