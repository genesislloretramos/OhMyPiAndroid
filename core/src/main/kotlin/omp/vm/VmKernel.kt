package omp.vm

import omp.shell.PlatformServices
import omp.shell.exec.CommandTable
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.RealVfs
import omp.shell.fs.Vfs
import omp.vm.cmd.VmCommands
import omp.vm.dev.DevBackend
import omp.vm.pkg.DpkgDatabase
import omp.vm.pkg.PackageIndex
import omp.vm.pkg.PackageOps
import omp.vm.proc.ProcBackend
import omp.vm.rootfs.Rootfs
import omp.vm.service.ServiceManager
import omp.vm.sys.SysBackend
import java.io.File

/**
 * An in-process, ultra-light userspace VM. Not an emulator, and not a hypervisor: a namespace.
 *
 * What it is, concretely. A [Vfs] over a mount table, a process table that starts at pid 1, a
 * passwd file, and four generated filesystems (`/proc`, `/sys`, `/dev`, plus the rootfs's own
 * `/run` and `/tmp`). The same Kotlin commands run in it. The cost is one directory under the
 * app's own storage and a few hundred bytes of generated text per read; there is no image, no
 * native code, no `Runtime.exec`, and nothing here needs a permission an ordinary app lacks.
 *
 * What it is not, and the one thing a reader should hold onto: **the VM's root is not a privilege
 * escalation.** It is the app's own uid, and every file in the namespace is that uid's file. The
 * kernel here declines to enforce a boundary it cannot enforce — no `setuid` to drop, no
 * `CAP_SETUID`, no second process to confuse — so `root` inside the VM is a name the shell answers
 * to. The real uid is in `/sys/omp/android/uid`, and the phone's storage is reached only through
 * the `/mnt/android` bind, which is a real bind and nothing more.
 *
 * [rootDir] is the only real path this class takes, and it is the VM's disk: a directory under the
 * app's internal storage that survives a reboot. Everything else is generated on the way out.
 */
class VmKernel(
    val rootDir: File,
    val services: PlatformServices,
    val now: () -> Long,
) {
    /**
     * The namespace, and the mount table it is built from.
     *
     * The table belongs to the [VmVfs] and not to a list beside it, because `vm mount` adds to it
     * after the constructor has run and two lists would be two tables that drift; the kernel is the
     * only thing that mutates it, and every reader goes through [mountTable].
     */
    val vfs: VmVfs

    val processes = VmProcessTable(services, now) { users.current() }

    /** The VM's users, read out of the rootfs through [vfs]. */
    val users: VmUsers

    /**
     * The VM's command set: a copy of the phone's whole set, with the userland and the commands
     * that have to speak the [omp.shell.fs.Vfs] registered over the top, so a namespace has every
     * command the phone has plus the ones that only make sense in here.
     */
    var commands: CommandTable = CommandTable.global.copy().also { VmCommands.register(it) }
        private set

    /** The package database: `/var/lib/dpkg`, read and written through [vfs]. */
    val packages: DpkgDatabase

    /** systemd-lite: the units this kernel really has, and the journal they write to. */
    val units: ServiceManager

    private var packageOps: PackageOps? = null

    private var running = false

    /**
     * The binds `/etc/fstab` asked for and this kernel could not honour, in fstab order.
     *
     * They are not mounts: nothing is exposed at those paths, and the point of keeping them is
     * that `mount` and `vm mounts` can say *why* rather than pretend the request was never made. A
     * directory the user deleted between two runs comes back as a line and a reason, not as a
     * silent gap in the table.
     */
    private val missing = ArrayList<Pair<UserMount, String>>()

    /**
     * Why the app's own bind at [LAUNCHER_MOUNT] is not in the table, in the words a boot printed.
     *
     * Kept rather than recomputed, because the caller is usually a command in a namespace that
     * came up after the boot that failed: asking again would produce an answer nobody recorded, and
     * the one thing a user can act on is the sentence that named the missing grant. Null while
     * the mount is there, and null when nothing has tried to make it yet.
     */
    private var launcherProblem: String? = null

    /** @see launcherProblem */
    fun launcherRefusal(): String? = launcherProblem

    /**
     * Remembers why the app's bind is not in the table, for a caller that found that out before
     * [bindLauncher] was ever called — the missing grant above all, which is decided before there
     * is a directory to bind. Without it a command in the namespace that found no mount would have
     * to invent a reason, and inventing one is the failure this app does not get to have.
     */
    fun noteLauncherRefusal(reason: String) {
        launcherProblem = reason
    }

    init {
        // The mount table, in the order a real kernel builds one: the root first, then the binds,
        // then the filesystems that are generated rather than stored.
        vfs = VmVfs(emptyList())
        vfs.owner = this
        addRoot()
        addAndroidBind()
        addGenerated()
        addEphemeral()
        makeMountPoints()
        users = VmUsers(vfs)
        packages = DpkgDatabase(vfs) { VmArch.of(services) }
        units = ServiceManager(vfs, services, processes) { Rootfs.hostName(services) }
    }

    /**
     * The install/remove machinery, with this kernel's table and its one host-side permission call.
     * Built once and kept, because a table is fixed after construction and a closure per command
     * would rebuild it every time.
     */
    fun packageOps(): PackageOps = packageOps ?: PackageOps(
        vfs = vfs,
        database = packages,
        services = services,
        makeExecutable = { makeExecutable(it) },
        availablePrograms = commands.names().toSet(),
    ).also { packageOps = it }

    /**
     * Makes a file the VM wrote executable, and refuses to do it anywhere else.
     *
     * The [omp.shell.fs.Vfs] has no `chmod`, and adding one would change an interface the whole
     * shell shares — so this is the one host-side permission call in the VM, and it only touches
     * paths on the VM's own root mount. `/mnt/android` is the user's storage and not ours to chmod.
     */
    fun makeExecutable(path: String) {
        if (vfs.mountAt(path)?.mountPoint != "/") return
        Rootfs.makeExecutable(rootDir, path)
    }

    /**
     * Builds the rootfs and reports it, one line per mount and one per thing that failed. The order
     * is the order the table was built in, so a failure is visible next to the mount that caused it.
     *
     * Calling it twice changes the disk not at all: the directories exist and the seeds are already
     * there, so the second call writes nothing. Its *log* does differ, deliberately — the systemd
     * lines say `keep <unit>` instead of `start <unit>`, because nothing was started a second time.
     */
    fun boot(): List<BootLine> {
        val lines = ArrayList<BootLine>()
        // A new boot id only for a cold kernel. Re-issuing it on a second `vm boot` would move the
        // boot boundary without a reboot, and `journalctl -b` would filter away the very log of the
        // boot that is still running.
        if (!running) ServiceManager.newBoot(now())
        running = true
        lines += BootLine("omp vm: in-process userspace, no kernel, no root; host ${services.processName()} pid ${services.processPid()}")

        for (mount in vfs.mounts()) {
            // The app's own bind is not in this list: it is made further down and has a line of its
            // own, with a reason when it could not be made. Printing it here as well would say it
            // twice on the second boot and say nothing on the first.
            if (mount.appOwned) continue
            lines += BootLine(
                "mount: ${mount.source} on ${mount.mountPoint} type ${mount.fstype} (${mount.options})",
                failed = mount.failed,
            )
            mount.note?.let { lines += BootLine("  note: $it", failed = mount.failed) }
        }

        // The user's own binds, in `/etc/fstab` order, and before the rootfs is written — which is
        // the order a real init uses: the table is read first, and the tree that holds the table is
        // whatever is already on disk. A bind that cannot be honoured is a line and a reason, and
        // never a boot that stops: a namespace that came up with one directory missing is still a
        // namespace worth entering, and a program that comes back after a restart deserves to be
        // told that its directory is gone rather than to find an empty one.
        for (line in applyBinds()) lines += line

        // The app's own bind, made here rather than read from `/etc/fstab`: one directory of
        // shared storage at /mnt/omp, so a project in the namespace and a folder in the user's
        // Documents are the same folder and not a copy of one. It is the last mount of the boot and
        // the first thing a command in here asks about, and a container that could not be made is
        // one failed line with the reason and a namespace that still comes up — the same deal the
        // /mnt/android bind makes when the all-files grant is missing.
        for (line in omp.vm.launcher.Containers.mountAtBoot(this)) lines += line

        // The rootfs: only what is missing, so a user's edit to /etc/motd is still there. A root
        // that could not be made is one honest failed line and a boot that carries on, because a
        // namespace that cannot be written is still worth entering to be told why.
        try {
            val created = Rootfs.ensure(vfs, services)
            // /etc/sudoers is 0440 on a real system and whatever the app's umask says here.
            Rootfs.setMode(rootDir, "/etc/sudoers", Rootfs.READ_ONLY_FOR_OWNER_AND_GROUP)
            lines += BootLine(
                if (created.isEmpty()) "rootfs: nothing to create; ${rootDir.path} was already there"
                else "rootfs: ${created.size} path(s) created under ${rootDir.path}"
            )
        } catch (e: Exception) {
            lines += BootLine("rootfs: cannot materialise the tree: ${e.message}", failed = true)
        }

        try {
            if (packages.seedFromIndex()) {
                val installed = PackageIndex.installedAtBootstrap().size
                lines += BootLine("dpkg: ${DpkgDatabase.STATUS} seeded from the local index, $installed package(s) installed")
            }
        } catch (e: Exception) {
            lines += BootLine("dpkg: cannot seed the database: ${e.message}", failed = true)
        }

        // One program file per command, and one deletion for a command that is gone.
        try {
            val programs = Rootfs.syncProgramFiles(vfs, commands, services)
            for (path in programs) makeExecutable(path)
            lines += BootLine("userland: ${programs.size} program file(s) in step with the command table")
        } catch (e: Exception) {
            lines += BootLine("userland: cannot synchronise the program files: ${e.message}", failed = true)
        }

        processes.registerFixed(
            VmProcessTable.INIT_PID,
            listOf("omp-init", "--root=/", "--host=${services.processName()}"),
            "omp-init",
            'S',
            users.uid(),
            users.gid(),
        )

        try {
            val unitFiles = units.ensureUnits()
            if (unitFiles.isNotEmpty()) {
                lines += BootLine("systemd: ${unitFiles.size} unit(s) written under ${ServiceManager.UNIT_DIR}")
            }
            for (line in units.boot()) lines += BootLine("systemd: $line")
        } catch (e: Exception) {
            lines += BootLine("systemd: units unavailable: ${e.message}", failed = true)
        }

        lines += BootLine(
            "init: pid 1 omp-init ready, ${processes.size()} process(es), uid ${users.uid()} (${users.currentName()})"
        )
        return lines
    }

    /** The mount table as `mount` and `/proc/mounts` see it, in mount order. */
    fun mountTable(): List<MountInfo> = vfs.mounts().map { it.info() }

    fun mounts(): List<Mount> = vfs.mounts()

    /** The binds `/etc/fstab` asked for that this kernel could not honour, and why, in fstab order. */
    fun missingBinds(): List<Pair<UserMount, String>> = missing.toList()

    fun isRunning(): Boolean = running

    // ---- the user's own binds ----------------------------------------------------------

    /**
     * Binds a directory on this device into the namespace at [mountPoint], or says why it will not.
     *
     * [check] makes every decision and nothing is written until it has made them all, and the two
     * that are worth stating here are the ones a bind cannot get round:
     *
     *  - **the app cannot reach further than the app can reach.** There is no `mount(2)` here and
     *    no `CAP_SYS_ADMIN`; a bind is a [omp.shell.fs.Vfs] over a directory this uid already
     *    opens, so it can only ever expose what is already the app's to read. `/system` is a
     *    sentence saying so, never an empty directory that looks like it worked. [HostAccess] is
     *    the whole policy, and it is the half of this feature that has to be right.
     *  - **the read-only flag is the [Mount]'s, not this method's.** A program inside the namespace
     *    is refused at the same seam the command was: `writeBytes` on a `--read-only` bind is
     *    [omp.shell.fs.FsErrno.READ_ONLY] however it was asked for, and the host directory is not
     *    changed.
     *
     * The line in `/etc/fstab` goes in before the mount does, so a bind that cannot survive the
     * next app start is never reported as one that will.
     */
    fun mount(hostPath: String, mountPoint: String?, readOnly: Boolean): BindResult {
        val host = trimPath(hostPath.trim())
        if (host.isEmpty()) return BindResult.Refused("give a directory on this device to bind")
        val point = mountPoint?.let { trimPath(it.trim()) } ?: defaultMountPoint(host)
        if (point.isEmpty()) {
            return BindResult.Refused(
                "no mount point given, and $host has no name to take one from; say where in the " +
                    "namespace it should appear",
            )
        }
        val user = UserMount(host, point, readOnly)
        val refused = check(user)
        if (refused != null) return BindResult.Refused(refused)
        // A bind that is not in `/etc/fstab` is gone at the next app start, and a program that comes
        // back to a directory that is no longer there has been lied to. So the line goes in first: if
        // the file cannot be written, the mount is not made and the user is told why.
        try {
            Rootfs.appendBind(vfs, user)
        } catch (e: FsException) {
            return BindResult.Refused("cannot record the bind in ${Rootfs.FSTAB}: ${e.message}")
        }
        return apply(user)
    }

    /**
     * The checks [mount] and the boot both make, and the order of them is the order of what the
     * user has to be told first.
     *
     * The namespace path, because that is the half they typed twice. Then whether the host path is
     * there at all, in the errno wording every other command in the shell uses for the same
     * mistake — a launcher that is about to create a directory and bind it needs to hear
     * "No such file or directory" and nothing else, and the phone's own `ls` says the same about a
     * path the platform has hidden from this uid. Then whether the app can reach it, which is the
     * one refusal with a whole sentence in it, and last whether it may be read at all.
     *
     * @return why this bind must not be made, in the user's own words and with no command name in
     * it — the caller knows whether it is the phone's `vm mount` or a boot line, and prefixing it
     * here would have put one command's name in the other's output.
     */
    private fun check(user: UserMount): String? {
        val pointError = whyNotMountPoint(user.mountPoint)
        if (pointError != null) return "${user.mountPoint}: $pointError"
        val hostError = checkHost(user)
        if (hostError != null) return hostError
        val made = makeMountPoint(user.mountPoint)
        if (made != null) return "${user.mountPoint}: $made"
        return null
    }

    /**
     * The host-directory half of [check], which the app's own bind at [LAUNCHER_MOUNT] makes too:
     * there it is a directory, this app can reach it, and it may be read. Nothing here is about a
     * mount point, because the app's bind is not a choice a user made and has no mount point to
     * argue with — it is the one path the namespace reserves for itself.
     */
    private fun checkHost(user: UserMount): String? {
        val file = File(user.hostPath)
        if (!file.exists()) return "${user.hostPath}: ${FsErrno.NO_SUCH_FILE.text}"
        if (!file.isDirectory) return "${user.hostPath}: ${FsErrno.NOT_A_DIRECTORY.text}"
        val refusal = HostAccess.refusal(services, user.hostPath)
        if (refusal != null) return refusal
        if (!file.canRead()) return "${user.hostPath}: ${FsErrno.PERM_DENIED.text}"
        return null
    }

    /**
     * Puts an already-checked [user] bind into the live table.
     *
     * A mount point that is already a bind is replaced rather than refused, because a boot re-applies
     * the whole file and a host directory that came back should not need the user to unmount it
     * first. A [Mount] that is not a bind is left alone: [check] has already refused that case, and
     * arriving here with one would mean the table changed under us.
     */
    private fun apply(user: UserMount): BindResult {
        val existing = vfs.mounts().firstOrNull { it.mountPoint == user.mountPoint }
        if (existing != null && !existing.isUserBind()) {
            return BindResult.Refused("${user.mountPoint} became a mount of the kernel's own in the meantime")
        }
        vfs.remove(user.mountPoint)
        vfs.add(
            Mount(
                mountPoint = user.mountPoint,
                source = user.hostPath,
                fstype = HostAccess.fstypeOf(user.hostPath),
                vfs = RealVfs(user.hostPath),
                readOnly = user.readOnly,
                note = bindNote(user.readOnly),
                bind = user,
            ),
        )
        missing.removeAll { it.first.mountPoint == user.mountPoint }
        return BindResult.Mounted(user)
    }

    /**
     * Takes a user bind back out of the namespace, and out of `/etc/fstab` so it does not come back
     * at the next boot.
     *
     * A mount the kernel provides is refused by name: `/proc` is generated, `/` is the rootfs and
     * the ephemeral directories are part of the tree, and taking any of them away would leave a
     * namespace that cannot answer for a path the rest of it still claims. The empty directory a
     * mount point leaves behind is left behind, exactly as a real `umount` leaves it.
     */
    fun umount(mountPoint: String): BindResult {
        val point = trimPath(mountPoint.trim()).ifEmpty { "/" }
        val existing = vfs.mounts().firstOrNull { it.mountPoint == point }
            ?: return BindResult.Refused("$point is not mounted")
        if (!existing.isUserBind()) {
            return BindResult.Refused(
                "$point is a mount of the VM kernel's own (${existing.source}) and cannot be " +
                    "unmounted; a bind added with 'vm mount' can be",
            )
        }
        val bind = existing.bind!!
        vfs.remove(point)
        missing.removeAll { it.first.mountPoint == point }
        Rootfs.removeBind(vfs, point)
        return BindResult.Unmounted(bind)
    }

    /**
     * The app's own bind at [LAUNCHER_MOUNT]: the one directory of shared storage the whole
     * conversation launcher is built on, made by the boot and by `omp` rather than by a user.
     *
     * **It is a bind and not a user bind.** It appears in the table exactly as a bind does — its
     * real device, the type the platform names, the options — and `mount` and `/proc/mounts` print
     * it like any other. What it is not is a *user* bind: nothing is written to `/etc/fstab`, no
     * line is read back at the next boot, and `vm umount` will not take it away. A user's bind is a
     * thing they asked for and may take back; this one is how a project in the namespace and a
     * folder in the user's `Documents` are the same folder, and it comes back at every boot
     * whatever anyone does to the table. [omp.vm.launcher.Containers] decides where the container
     * is and creates it; this method is the kernel's half, and it is the only way the mount is
     * made — the `vm mount` command refuses that path by name, on purpose.
     *
     * Idempotent in the only sense that matters: asked twice for the same directory it is a no-op
     * and hands back the mount that is already there, so a second boot adds no second mount and
     * changes nothing. A different directory replaces it, which is what a user who moved their
     * `Documents` folder has asked for.
     *
     * @return the mount now in the table at [LAUNCHER_MOUNT], or null with the reason kept in
     * [launcherRefusal] — the same sentence a boot printed, so a command in the namespace can say
     * the same thing the boot log did.
     */
    fun bindLauncher(hostPath: String): Mount? {
        val user = UserMount(trimPath(hostPath.trim()), LAUNCHER_MOUNT, false)
        if (user.hostPath.isEmpty()) return refuseLauncher("give a directory on this device to bind")
        val hostError = checkHost(user)
        if (hostError != null) return refuseLauncher(hostError)
        val existing = vfs.mounts().firstOrNull { it.mountPoint == LAUNCHER_MOUNT }
        if (existing != null && existing.appOwned && existing.source == user.hostPath) {
            launcherProblem = null
            return existing
        }
        val made = makeMountPoint(LAUNCHER_MOUNT)
        if (made != null) return refuseLauncher("$LAUNCHER_MOUNT: $made")
        vfs.remove(LAUNCHER_MOUNT)
        val mount = Mount(
            mountPoint = LAUNCHER_MOUNT,
            source = user.hostPath,
            fstype = HostAccess.fstypeOf(user.hostPath),
            vfs = RealVfs(user.hostPath),
            note = LAUNCHER_NOTE,
            appOwned = true,
        )
        vfs.add(mount)
        launcherProblem = null
        return mount
    }

    private fun refuseLauncher(reason: String): Mount? {
        launcherProblem = reason
        return null
    }

    /**
     * The one line a mount contributes to a boot, in the same shape as every other mount.
     *
     * A bind's own line and this are the same string, because they are the same fact: the source
     * is the host directory the platform will name a filesystem for, which for a bind is the only
     * honest device an app can put in that column.
     */
    fun bootLine(mount: Mount): String =
        "mount: ${mount.source} on ${mount.mountPoint} type ${mount.fstype} (${mount.options})"

    /**
     * Reads `/etc/fstab` and mounts every bind in it, in file order, one [BootLine] each.
     *
     * Nothing here is allowed to end the boot. A directory the user deleted, a grant revoked in
     * system settings, a path this app can no longer read: each is one line saying which, an entry
     * in [missingBinds] that `mount` prints under the table, and a namespace that comes up without
     * it. A bind that failed last time is tried again, so a host directory that came back is
     * mounted again by the next boot with nothing asked of the user.
     */
    private fun applyBinds(): List<BootLine> {
        val lines = ArrayList<BootLine>()
        for (user in Rootfs.bindLines(vfs)) {
            val refused = check(user)
            if (refused != null) {
                missing.removeAll { it.first == user }
                missing += user to refused
                lines += BootLine("bind: ${user.mountPoint} -> ${user.hostPath}: $refused", failed = true)
                continue
            }
            when (val applied = apply(user)) {
                is BindResult.Mounted -> lines += BootLine(bootLine(mountOf(user.mountPoint)))
                // Not reachable: a bind that passed every check and then could not be applied anyway
                // is reported as what it is rather than as a success.
                else -> lines += BootLine(
                    "bind: ${user.mountPoint} -> ${user.hostPath}: ${applied.describe()}",
                    failed = true,
                )
            }
        }
        return lines
    }

    /** The mount that answers at [point]; [apply] has just put it there. */
    private fun mountOf(point: String): Mount = vfs.mounts().first { it.mountPoint == point }

    /** The namespace path a bind takes by default: the host directory's own name under `/mnt`. */
    private fun defaultMountPoint(host: String): String {
        val name = HostAccess.basename(host)
        if (name.isEmpty()) return ""
        return "/mnt/$name"
    }

    /**
     * A path as one field: trailing slashes off, and `/` left as `/`.
     *
     * The root is spelled out because `"/".trimEnd('/')` is `""`, and a `vm mount /somewhere /` that
     * arrived as an empty string would be refused for the wrong reason — as though no mount point
     * had been typed at all.
     */
    private fun trimPath(path: String): String = when {
        path.length <= 1 -> path
        else -> path.trimEnd('/')
    }

    /**
     * @return why [point] cannot be a mount point, in the user's words and naming what it collided
     * with, or null when it can be. A mount point inside another mount is fine — that is how a
     * bind of a subdirectory works — but the mount point itself may not be one.
     */
    private fun whyNotMountPoint(point: String): String? {
        if (!point.startsWith("/")) return "a mount point in the namespace is absolute, like $LAUNCHER_MOUNT"
        if (point == "/") return "the root of the namespace is always mounted; choose a path under it"
        if (point == LAUNCHER_MOUNT) {
            return "it is reserved for the app's project launcher, which binds shared storage there; " +
                "a mount you type cannot be put on top of it"
        }
        if (point in RESERVED) return "it is a mount of the VM kernel's own, and a bind cannot be mounted over it"
        val existing = vfs.mounts().firstOrNull { it.mountPoint == point }
        if (existing != null) {
            return "it is already a mount point (${existing.source}); 'vm umount $point' first, if it is yours"
        }
        return null
    }

    /**
     * Makes the mount point directory, on the host tree the way [makeMountPoints] makes the
     * kernel's own: before the mount goes into the table, and without asking the [omp.shell.fs.Vfs],
     * which would refuse a `mkdir` under a read-only mount and has nothing to say about a path that
     * is not mounted yet.
     *
     * A mount point *inside* another mount — `/mnt/android/Documents`, say — gets its directory
     * under the rootfs rather than under the directory that mount is showing. Nothing is lost by
     * it: the bind covers the path the moment it is made, and the empty directory underneath is
     * what a real mount leaves behind as well.
     *
     * @return the reason it could not be made, or null when it is there.
     */
    private fun makeMountPoint(point: String): String? {
        val dir = File(rootDir, point)
        if (dir.isDirectory) return null
        if (dir.exists()) return "${FsErrno.NOT_A_DIRECTORY.text}: ${dir.path} is a file"
        if (dir.mkdirs() || dir.isDirectory) return null
        return "cannot create the mount point ${dir.path}: ${FsErrno.PERM_DENIED.text}"
    }

    /** What a mounted bind says about itself under `mount`: one line, the truth and nothing else. */
    private fun bindNote(readOnly: Boolean): String = if (readOnly) {
        "bound read-only: a write through the namespace is refused with ${FsErrno.READ_ONLY.text}, and the host directory is not changed"
    } else {
        "bound read-write: a file written through the namespace is a file in the host directory"
    }

    private fun addRoot() {
        val root = File(rootDir, "")
        val error = if (root.isDirectory || root.mkdirs()) null else "cannot create ${root.path}"
        vfs.add(
            Mount(
                mountPoint = "/",
                source = if (error == null) "omp-root" else "omp-root(broken)",
                fstype = "ext4",
                vfs = RealVfs(root.path),
                note = error,
                failed = error != null,
            ),
        )
    }

    /**
     * The connection to the phone's own storage, and the only reason the VM can see a photo the
     * user took. Without the all-files grant there is nothing to bind, so the mountpoint still
     * exists — as a real, empty directory — and says why, which is better than a path that is not
     * there at all and an `ls` that says "No such file or directory".
     */
    private fun addAndroidBind() {
        val external = services.externalStorageDir()
        val granted = external != null && services.isExternalStorageManager()
        if (granted) {
            vfs.add(
                Mount(
                    mountPoint = "/mnt/android",
                    source = "shared-storage",
                    fstype = "ext4",
                    vfs = RealVfs(external!!),
                ),
            )
            return
        }
        val placeholder = File(rootDir, "mnt/android")
        val error = if (placeholder.isDirectory || placeholder.mkdirs()) null else "cannot create ${placeholder.path}"
        vfs.add(
            Mount(
                mountPoint = "/mnt/android",
                source = "shared-storage(unbound)",
                fstype = "ext4",
                vfs = RealVfs(placeholder.path),
                note = when {
                    external == null -> "empty: the platform reports no shared storage, and an app cannot mount its own"
                    error != null -> "empty: $error"
                    else -> "empty: this app has no all-files access grant; run grant-storage on the phone and reboot the VM"
                },
            ),
        )
    }

    private fun addGenerated() {
        vfs.add(Mount("/proc", "proc", "proc", ProcBackend(services, processes, { mountTable() }, { users }), readOnly = true))
        vfs.add(Mount("/sys", "sysfs", "sysfs", SysBackend(services), readOnly = true))
        vfs.add(Mount("/dev", "devtmpfs", "devtmpfs", DevBackend(services), readOnly = false))
    }

    /**
     * The mountpoint directories, made the way a kernel makes them: before anything is mounted
     * there, and without asking the Vfs — because `mkdir /proc` through the seam is refused, and it
     * should be. This is also why `ls /` shows `dev`, `proc` and `sys` on a real system.
     */
    private fun makeMountPoints() {
        for (name in listOf("proc", "sys", "dev", "run", "tmp", "mnt/android", "mnt/omp")) {
            val dir = File(rootDir, name)
            if (!dir.isDirectory) dir.mkdirs()
        }
    }

    /** tmpfs semantics without a tmpfs: a directory whose contents a wipe removes. */
    private fun addEphemeral() {
        for ((point, name) in listOf("/run" to "run", "/tmp" to "tmp")) {
            val dir = File(rootDir, name)
            if (!dir.isDirectory) dir.mkdirs()
            vfs.add(Mount(point, "tmpfs", "tmpfs", RealVfs(dir.path), options = "rw,nosuid,nodev,relatime"))
        }
    }

    /**
     * The mount points a hand-typed `vm mount` may not take, and why each is spoken for.
     *
     * `/`, `/proc`, `/sys` and `/dev` are the kernel's own filesystems and a bind cannot be put on
     * top of any of them. [LAUNCHER_MOUNT] is the app's project launcher: it binds one directory of
     * shared storage there so every project the app creates lives under "Internal storage >
     * Documents > omp" and the namespace mirrors the host exactly, and a mount the user typed must
     * not be able to bury it under something else. Nothing about it is secret — `mount` prints the
     * bind and the directory it came from — it is simply not the user's to overwrite.
     */
    private val RESERVED: Set<String> = setOf("/", "/proc", "/sys", "/dev", LAUNCHER_MOUNT)

    companion object {
        /**
         * Where the app's project launcher binds shared storage: `/storage/emulated/0/Documents/omp`
         * on the device, `/mnt/omp` in the namespace, so a program in here and a file manager on the
         * phone are looking at the same directory rather than at a copy of it.
         */
        const val LAUNCHER_MOUNT = "/mnt/omp"

        /**
         * What [LAUNCHER_MOUNT] says about itself under `mount`. A bind is not a secret — the line
         * above it already names the directory on the device — so what this says is the one thing
         * `mount` cannot show: that the mount belongs to the app, that no boot will read it out of
         * `/etc/fstab` because every boot makes it again, and that a file written through it is a
         * file in the user's `Documents`.
         */
        private const val LAUNCHER_NOTE =
            "the app's own bind for its conversations, made at every boot rather than recorded in " +
                "the fstab: a file written through this path is a file in the folder above"
    }

    // ---- the process table -------------------------------------------------------------

    /**
     * Ends the VM: every process released, nothing left pretending to be one. The disk and the
     * generated filesystems are not touched — a shutdown is a shutdown, not a factory reset, and
     * `boot()` after it works. The binds in `/etc/fstab` are not touched either: they come back at
     * the next boot, which is what a mount the app is still running should do.
     */
    fun shutdown() {
        running = false
        processes.clear()
    }

    /** Starts the login shell as pid 2, once. */
    fun startLoginShell(argv: List<String> = listOf("/usr/bin/sh", "-i")): VmProcess {
        val existing = processes.of(VmProcessTable.LOGIN_PID)
        if (existing != null && existing.state != 'Z') return existing
        return processes.registerFixed(
            VmProcessTable.LOGIN_PID,
            argv,
            VmProcessTable.shortName(argv),
            'S',
            users.uid(),
            users.gid(),
        )
    }
}
