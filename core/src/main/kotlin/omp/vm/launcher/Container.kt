package omp.vm.launcher

import omp.shell.PlatformServices
import omp.shell.exec.ExecContext
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.RealVfs
import omp.shell.fs.Vfs
import omp.vm.BootLine
import omp.vm.HostAccess
import omp.vm.Mount
import omp.vm.VmKernel
import omp.vm.VmVfs
import omp.vm.rootfs.Rootfs
import omp.vm.workspace.Workspace

/**
 * Where the conversations are, seen from the namespace the session is in.
 *
 * **The same container has two names and this class holds both.** [root] is the namespace path — a
 * command inside the VM takes `/mnt/omp/notes`, a command on the phone takes
 * `/storage/emulated/0/Documents/omp/notes` — and [hostRoot] is where the bytes really are, which
 * is the one a file manager shows. Inside the VM the two are different strings for one directory,
 * because the namespace is a bind and not a copy; on the phone they are the same string, because
 * there is no second name for a directory the user can already see.
 *
 * [vfs] is the session's own filesystem, and the only one this class reaches: a folder the user
 * can open in a file manager has to be one this code reached through the same seam a program in the
 * VM reached. That is also why [root] can be handed straight to [omp.shell.Session.cwd] — it is a
 * path in the session's namespace, whatever the user calls it.
 *
 * [kernel] is the namespace's kernel when there is one, and null on the phone. It is the only way
 * to make the bind, and it is null exactly where there is nothing to bind: the phone's shell is
 * already looking at the shared-storage tree, so its "container" needs no mount of its own.
 *
 * **A [refusal] is not an empty container.** [isReady] is false whenever [refusal] is not empty,
 * and a caller that skipped that check would go on to create a folder under a path this app cannot
 * reach and print a listing that looks like a working one. That is the failure this whole class
 * exists to make impossible, so the reason and the readiness are the same field.
 */
class Container(
    val vfs: Vfs,
    val services: PlatformServices,
    /** The namespace path: `/mnt/omp` in the VM, the shared-storage path on the phone. */
    val root: String,
    /** The real path on the device. Empty only when [refusal] says why there is none. */
    val hostRoot: String,
    val kernel: VmKernel?,
    /** Why there is nothing to manage here, in one line; empty when [isReady]. */
    val refusal: String,
) {

    val isReady: Boolean get() = refusal.isEmpty()

    /**
     * The userland a conversation in this namespace runs, or null when there is none to name.
     *
     * The phone shell is not a distribution, and recording one there would put a claim in a file
     * the user can open in a file manager — so [Workspace.DISTRO] is written only where there is
     * genuinely a userland, and the phone leaves the key out instead of filling it with a guess.
     */
    val distro: String? get() = if (kernel == null) null else Rootfs.PRETTY

    /**
     * The model over this container. [now] is injected like everything else in
     * [omp.vm.workspace.Workspace], so a generated name and the `created` stamp are the same
     * millisecond, and a test can name a session in 2021.
     */
    fun workspace(now: () -> Long): Workspace = Workspace(vfs, root, hostRoot, now)

    /** A conversation's namespace path, joined the one way the rest of the shell joins. */
    fun child(name: String): String = Workspace.child(root, name)
}

/**
 * The one place that decides where the conversations are, and the one place that makes the
 * container and its bind.
 *
 * **The root is derived, never declared.** Inside the namespace it is [VmKernel.LAUNCHER_MOUNT] and
 * the real directory comes from the mount — from the bind map, which is the authority on where a
 * namespace path lands, asked through [omp.shell.fs.RealVfs.hostPathOf] rather than read out of a
 * string. On the phone it is the `Documents/omp` directory under the platform's own shared storage,
 * and the device filesystem answers with the path itself, which is also true: on such a filesystem
 * the namespace path *is* the real path. A session whose filesystem can say neither — one built
 * over something that is not a device and not a namespace — is refused in one line, because a
 * launcher that guessed would write conversations somewhere no file manager will ever show.
 *
 * **The grant is the first thing asked about and never worked around.** [omp.vm.HostAccess] is the
 * whole policy and it already carries the sentence the rest of the app uses, so the launcher says
 * the same words as `vm mount` and the `/mnt/android` boot note rather than a fourth version of
 * "permission denied". Nothing is created without it, and a container that cannot be created is
 * reported with the errno that stopped it.
 *
 * **The bind belongs to the app, so it is made and not recorded.** [mountAtBoot] is what
 * `VmKernel.boot()` calls; it creates the container through the seam and then asks the kernel to
 * mount it. No `/etc/fstab` line is written, because the next boot makes it again and a second
 * source of truth could only go stale — and a bind the app owns is not a bind the user can `umount`
 * away from under it. [ensure] is the same work for a command that is run when the container is not
 * there yet, which is the ordinary case on a phone that has never had a conversation.
 */
object Containers {

    /**
     * The container's own name, and the directory it lives in. `Documents/omp` is what the user
     * finds at *Internal storage ▸ Documents ▸ omp*, and one container means "forget everything"
     * is a single folder to delete.
     */
    const val NAME = "omp"

    /** The user's own documents directory, the one every Android file manager opens on. */
    const val DOCUMENTS = "Documents"

    /**
     * What the container is, on the device. Null when the platform reports no shared storage at
     * all, which is a device with no mounted volume rather than a missing grant: the two need
     * different sentences and only one of them is worth asking the user to fix.
     */
    fun hostRootOf(services: PlatformServices): String? =
        services.externalStorageDir()
            ?.takeIf { it.isNotBlank() }
            ?.let { Workspace.child(Workspace.child(it.trimEnd('/'), DOCUMENTS), NAME) }

    /**
     * The container for the session [ctx] is in, with the reason it is not usable when there is
     * none. Nothing is created here: this answers "where would they be", which is a question with
     * an answer even on a phone that cannot write to the place it names.
     */
    fun locate(ctx: ExecContext): Container {
        val vfs = ctx.session.vfs
        val kernel = (vfs as? VmVfs)?.owner
        return if (kernel == null) onPhone(vfs, ctx.services) else inNamespace(vfs, ctx.services, kernel)
    }

    /**
     * Makes the container and, in a namespace, the bind that puts it at [VmKernel.LAUNCHER_MOUNT].
     *
     * @return null when it is there, or the one line that says why it is not. Never both, and
     * never a success line: a caller that got null may print the table, and one that got a string
     * has to say it and change nothing.
     */
    fun ensure(container: Container): String? {
        val kernel = container.kernel
            ?: return if (container.isReady) mkdirs(container.vfs, container.root) else container.refusal
        val host = hostRootOf(container.services)
        if (host == null) {
            kernel.noteLauncherRefusal(NO_SHARED_STORAGE)
            return NO_SHARED_STORAGE
        }
        // The grant is asked before anything is made, not after: a `mkdir` under shared storage
        // this app cannot read fails as `No such file or directory`, and reporting that would send
        // a user with no all-files access to go looking for a folder that was never missing.
        val refused = HostAccess.refusal(container.services, host)
        if (refused != null) {
            kernel.noteLauncherRefusal(refused)
            return refused
        }
        // The container is a directory on the device, and inside the namespace it is reached
        // through the phone's own filesystem — the same one the bind is made from, so the folder
        // this makes is the folder the mount will show.
        val made = mkdirs(RealVfs(), host)
        if (made != null) {
            kernel.noteLauncherRefusal(made)
            return made
        }
        if (kernel.bindLauncher(host) == null) return kernel.launcherRefusal()
        return null
    }

    /**
     * The boot's own bind for the conversations, as the lines a boot log prints for it: one line
     * with the mount when it is there, and one failed line with the reason when it is not.
     *
     * Nothing here may end a boot. A namespace whose container could not be bound is still a
     * namespace worth entering — to be told exactly why `ls /mnt/omp` is not there — and a phone
     * whose user has not granted all-files access must be able to open the app, run `grant-storage`
     * and come back without the VM having refused to start. The reason is also left with the
     * kernel, because the first command to ask about `/mnt/omp` runs long after this boot.
     */
    fun mountAtBoot(kernel: VmKernel): List<BootLine> {
        val point = VmKernel.LAUNCHER_MOUNT
        val host = hostRootOf(kernel.services)
            ?: return refused(kernel, point, "", NO_SHARED_STORAGE)
        val grant = HostAccess.refusal(kernel.services, host)
        if (grant != null) return refused(kernel, point, host, grant)
        val made = mkdirs(RealVfs(), host)
        if (made != null) return refused(kernel, point, host, made)
        val mount = kernel.bindLauncher(host)
        if (mount == null) return refused(kernel, point, host, kernel.launcherRefusal().orEmpty())
        return listOf(BootLine(kernel.bootLine(mount)))
    }

    /** The failed line, and the same words kept for whoever asks inside the namespace. */
    private fun refused(kernel: VmKernel, point: String, host: String, reason: String): List<BootLine> {
        kernel.noteLauncherRefusal(reason)
        val where = if (host.isEmpty()) point else "$point -> $host"
        return listOf(BootLine("bind: $where: $reason", failed = true))
    }

    /**
     * [omp.shell.fs.Vfs.mkdir] is one level, and `Documents` may not be there on a phone that has
     * never had anything written to it, so the parents are made one at a time.
     *
     * `File exists` is the answer for every level that is already there, which is the case both a
     * second run and a second namespace hit on the way in — so it is the one errno that is not a
     * failure here, and every other one is reported as itself rather than swallowed into a success
     * line. A level that is a *file* comes back as the seam's errno, which is what the user needs
     * to know: something is in the way and it is not a directory.
     *
     * @return null when the directory is there, or the line that says why it is not.
     */
    fun mkdirs(vfs: Vfs, path: String): String? {
        val at = StringBuilder()
        for (part in path.split('/')) {
            if (part.isEmpty()) continue
            at.append('/').append(part)
            try {
                vfs.mkdir(at.toString())
            } catch (e: FsException) {
                if (e.errno != FsErrno.FILE_EXISTS) return "${at}: ${e.errno.text}"
            }
        }
        return null
    }

    // ---- the two namespaces ----------------------------------------------------------------

    /**
     * The phone. There is no bind to make: the session's filesystem *is* shared storage, so the
     * container is a directory in it and the only question is whether this app may write there.
     *
     * **Both halves of the cast are checked, and the second one is the one that matters.**
     * [omp.shell.fs.RealVfs.isDeviceRoot] is what says a path in this session means a path on the
     * device; a `RealVfs` built with a root answers with `<root>/<path>`, which is a directory
     * inside somebody else's namespace and not one this session's own filesystem will serve. Today
     * the only `RealVfs` reachable without a [omp.vm.VmVfs] owner is the device-rooted default, so
     * this refuses nothing a user can reach — it is here so that the next caller that hands a
     * session a rooted `RealVfs` gets one line and not a container rooted in the wrong place.
     */
    private fun onPhone(vfs: Vfs, services: PlatformServices): Container {
        val host = hostRootOf(services) ?: return refused(vfs, services, "", NO_SHARED_STORAGE)
        val real = vfs as? RealVfs
        if (real == null || !real.isDeviceRoot) {
            return refused(
                vfs,
                services,
                host,
                "this session's filesystem does not say which directories on this device a path is, " +
                    "so there is nowhere this app can honestly put a conversation",
            )
        }
        // Asked of the filesystem rather than assumed: a device-rooted Vfs answers with the path
        // itself, and that is a fact about the bind map instead of a claim about it.
        val root = real.hostPathOf(host)
        val refusal = HostAccess.refusal(services, root)
        return Container(vfs, services, root, root, null, refusal.orEmpty())
    }

    /**
     * The namespace. The mount is the authority on where the bytes are, so a session that finds no
     * mount gets a refusal naming the reason the kernel kept — the same words a boot printed, and
     * the grant sentence among them — rather than a host path invented from the platform.
     */
    private fun inNamespace(vfs: Vfs, services: PlatformServices, kernel: VmKernel): Container {
        val point = VmKernel.LAUNCHER_MOUNT
        val mount = kernel.mounts().firstOrNull { it.mountPoint == point && it.appOwned }
        if (mount == null) {
            val host = hostRootOf(services).orEmpty()
            val refusal = kernel.launcherRefusal()
                ?: "$point is not mounted: nothing has bound the conversations container yet; " +
                    "'omp new' makes it"
            return Container(vfs, services, point, host, kernel, refusal)
        }
        val host = hostOf(mount)
            ?: return Container(
                vfs,
                services,
                point,
                "",
                kernel,
                "the bind at $point does not say which directory on this device it is, so this " +
                    "app will not print a path it cannot stand behind",
            )
        return Container(vfs, services, point, host, kernel, "")
    }

    /**
     * The real directory behind [mount], asked of the filesystem that is rooted at it.
     *
     * `hostPathOf("/")` rather than [Mount.source]: the source is what was typed, and the bind map
     * is what is true. On the mount this class builds they are the same string, and asking is what
     * keeps them that way rather than what keeps them agreeing today.
     */
    private fun hostOf(mount: Mount): String? = (mount.vfs as? RealVfs)?.hostPathOf("/")

    private fun refused(vfs: Vfs, services: PlatformServices, host: String, reason: String) =
        Container(vfs, services, host, host, null, reason)

    /**
     * A device with no shared storage at all. Not a missing grant: there is nothing to grant
     * access *to*, and telling such a user to run `grant-storage` would send them to a system
     * screen that changes nothing.
     */
    private const val NO_SHARED_STORAGE =
        "the platform reports no shared storage on this device, and this app cannot make its own"
}

