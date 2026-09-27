package omp.vm

import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.VEntry
import omp.shell.fs.VDiskUsage
import omp.shell.fs.VStat
import omp.shell.fs.Vfs
import omp.shell.fs.resolveSymlinks
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The VM's namespace: a [Vfs] that is a mount table and nothing else. Every path is matched against
 * the longest mountpoint that covers it and handed to that mount's [Vfs], so a command cannot tell
 * which filesystem answered — which is the entire trick, and the reason the phone's commands work
 * here unchanged once they speak the seam.
 *
 * Two rules are the namespace's own and not the kernel's:
 *
 *  - A path that matches no mountpoint is [FsErrno.NO_SUCH_FILE]. Crossing *into* a mountpoint is
 *    fine; leaving the namespace is not. A symlink in the rootfs that points at `/etc/passwd` is
 *    resolved inside the namespace, never handed to the phone's `/etc/passwd`, because a mount
 *    that resolves a link against its own root would silently let the rootfs read the device.
 *  - A read-only mount refuses every mutating method with [FsErrno.READ_ONLY], including the ones
 *    that would otherwise be harmless. `/proc` is not a directory of files that happen to be
 *    read-only; it is generated, and writing to it has to fail at the seam rather than inside a
 *    backend that forgot to check.
 *
 * The table is not frozen: [omp.vm.VmKernel] adds to it when the user runs `vm mount` and takes
 * from it on `vm umount`, and a longest-match lookup against a live table is what makes a bind
 * visible to a program already running in the namespace without rebooting it.
 */
class VmVfs(mounts: List<Mount>) : Vfs {

    /**
     * The mounts in the order they were mounted, which is the order `mount` and boot print.
     *
     * Copy-on-write because the table changes rarely and is read on every path: a `vm mount` on the
     * phone side rewrites the list and the sorted view, and a command in the namespace is looking
     * at the old one at that instant, which is exactly the guarantee a mount table gives — a path
     * keeps answering with the filesystem that was mounted when it was looked up.
     */
    private val ordered: CopyOnWriteArrayList<Mount> = CopyOnWriteArrayList(mounts)

    /** Longest mountpoint first, so `/mnt/android` wins over `/` and over a shorter bind. */
    @Volatile
    private var table: List<Mount> = longestFirst(ordered)

    fun mounts(): List<Mount> = ordered.toList()

    /**
     * Adds a mount to the live table.
     *
     * Only [omp.vm.VmKernel] calls this, through `vm mount`; the kernel is what owns the table, and
     * a command that could add to it behind the kernel's back would leave the boot log and
     * `/etc/fstab` describing a different table than the one answering.
     */
    fun add(mount: Mount) {
        ordered.add(mount)
        resort()
    }

    /**
     * Takes a mount out of the live table.
     *
     * @return the mount that was removed, or null when [mountPoint] is not one — the caller says
     * so in the user's words, because only it knows which of them is a built-in.
     */
    fun remove(mountPoint: String): Mount? {
        val point = if (mountPoint.length > 1) mountPoint.trimEnd('/') else mountPoint
        val found = ordered.firstOrNull { it.mountPoint == point } ?: return null
        ordered.remove(found)
        resort()
        return found
    }

    /**
     * The kernel this filesystem belongs to, set by [VmKernel] once it has finished constructing
     * itself. A VM command finds its process table and mount table through here rather than through
     * a global, so two namespaces in one process stay two namespaces.
     */
    var owner: VmKernel? = null

    private fun resort() {
        table = longestFirst(ordered)
    }

    private fun longestFirst(mounts: List<Mount>): List<Mount> =
        mounts.sortedByDescending { it.mountPoint.length }

    private fun absolute(path: String): String {
        if (path.isEmpty()) return "/"
        val trimmed = if (path.length > 1) path.trimEnd('/') else path
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    private fun resolve(path: String): Pair<Mount, String> {
        val abs = absolute(path)
        for (mount in table) {
            if (mount.matches(abs)) return mount to mount.relative(abs)
        }
        // Nothing is mounted here. Saying so plainly is the only answer that is not a guess.
        throw FsException(FsErrno.NO_SUCH_FILE, path)
    }

    private fun mountOf(path: String): Mount = resolve(path).first

    private fun checkWritable(mount: Mount, path: String) {
        if (mount.readOnly) throw FsException(FsErrno.READ_ONLY, path)
    }

    override fun stat(path: String): VStat {
        val (mount, rel) = resolve(path)
        return mount.vfs.stat(rel)
    }

    override fun readDir(path: String): List<VEntry> {
        val (mount, rel) = resolve(path)
        return mount.vfs.readDir(rel)
    }

    override fun openRead(path: String): InputStream {
        val (mount, rel) = resolve(path)
        return mount.vfs.openRead(rel)
    }

    override fun openWrite(path: String, append: Boolean): OutputStream {
        val (mount, rel) = resolve(path)
        checkWritable(mount, path)
        return mount.vfs.openWrite(rel, append)
    }

    override fun readBytes(path: String): ByteArray {
        val (mount, rel) = resolve(path)
        return mount.vfs.readBytes(rel)
    }

    override fun writeBytes(path: String, bytes: ByteArray) {
        val (mount, rel) = resolve(path)
        checkWritable(mount, path)
        mount.vfs.writeBytes(rel, bytes)
    }

    override fun createFile(path: String) {
        val (mount, rel) = resolve(path)
        checkWritable(mount, path)
        mount.vfs.createFile(rel)
    }

    override fun mkdir(path: String) {
        val (mount, rel) = resolve(path)
        checkWritable(mount, path)
        mount.vfs.mkdir(rel)
    }

    override fun delete(path: String) {
        val (mount, rel) = resolve(path)
        checkWritable(mount, path)
        mount.vfs.delete(rel)
    }

    /**
     * Both ends have to be on the same mount. A cross-mount rename has no honest answer here:
     * `EXDEV` is not an [FsErrno], and copying the bytes and deleting the source would be a lie
     * about what `mv` is.
     */
    override fun rename(from: String, to: String) {
        val (fromMount, fromRel) = resolve(from)
        val (toMount, toRel) = resolve(to)
        checkWritable(fromMount, from)
        checkWritable(toMount, to)
        if (fromMount.mountPoint != toMount.mountPoint) throw FsException(FsErrno.INVALID_ARGUMENT, from)
        fromMount.vfs.rename(fromRel, toRel)
    }

    /** One level, empty only: the [FsErrno.NOT_EMPTY] and [FsErrno.NOT_A_DIRECTORY] come from the
     *  mount, which is the only thing that knows what a directory here is. */
    override fun rmdir(path: String) {
        val (mount, rel) = resolve(path)
        checkWritable(mount, path)
        mount.vfs.rmdir(rel)
    }

    override fun symlink(target: String, link: String) {
        val (mount, rel) = resolve(link)
        checkWritable(mount, link)
        mount.vfs.symlink(target, rel)
    }

    override fun readLink(path: String): String {
        val (mount, rel) = resolve(path)
        return mount.vfs.readLink(rel)
    }

    /**
     * The symlink-free form of [path] inside the namespace. The walk runs against *this* [Vfs], so
     * a chain that crosses a mountpoint is followed across it — and an absolute target is looked up
     * in the namespace from `/`, never in the host filesystem a mount happens to be rooted at.
     */
    override fun realpath(path: String): String = resolveSymlinks(this, absolute(path))

    override fun diskUsage(path: String): VDiskUsage {
        val (mount, rel) = resolve(path)
        return mount.vfs.diskUsage(rel)
    }

    override fun setModified(path: String, millis: Long) {
        val (mount, rel) = resolve(path)
        checkWritable(mount, path)
        mount.vfs.setModified(rel, millis)
    }

    /** The mount that answers for [path], or null when nothing is mounted there. */
    fun mountAt(path: String): Mount? = try {
        mountOf(path)
    } catch (e: FsException) {
        null
    }

    /**
     * True when [path] *is* a mountpoint, as opposed to being covered by one.
     *
     * The root mount covers every path, so `mountAt` answers for everything and cannot answer this
     * question. A caller that wants to know "is this a directory a filesystem is mounted on" — the
     * rootfs writer deciding whether it should `mkdir` — needs the narrower answer, and confusing
     * the two would have the rootfs skip its entire tree.
     */
    fun isMountPoint(path: String): Boolean {
        val abs = absolute(path)
        val trimmed = if (abs.length > 1) abs.trimEnd('/') else abs
        return table.any { it.mountPoint != "/" && it.mountPoint == trimmed }
    }
}
