package omp.vm

import omp.shell.fs.Vfs

/**
 * One entry in the VM's mount table: what a path means. [mountPoint] is the namespace path, [vfs]
 * is the thing that answers for it, and [source]/[fstype] are what `mount` and `/proc/mounts` print
 * — which is the whole point of keeping them next to the [Vfs] instead of in a string table
 * somewhere else.
 *
 * [bind] is the user's own mount when there is one: the [UserMount] this entry was made from, and
 * null for every mount the kernel provides. It is what `vm umount` is allowed to take away — a
 * built-in mount is refused by name — and what tells [omp.vm.rootfs.Rootfs] that the fstab line for
 * this entry is a bind line rather than a filesystem the VM mounts for itself.
 *
 * [note] is the honest caveat for a mount that is present but not what its path suggests: the
 * `/mnt/android` bind with no all-files grant behind it, a user bind whose host directory has
 * since been deleted, a bind mounted read-only. A mount that needs an explanation gets one; a
 * mount that quietly lies does not exist here.
 */
class Mount(
    val mountPoint: String,
    val source: String,
    val fstype: String,
    val vfs: Vfs,
    val readOnly: Boolean = false,
    val failed: Boolean = false,
    /** The fourth column of `/proc/mounts`; defaults to what a real mount of this type carries. */
    val options: String = optionsFor(readOnly),
    val note: String? = null,
    /**
     * True when the app owns this mount rather than the user: it is made at every boot from the
     * app's own facts, never read out of `/etc/fstab`, and `vm umount` will not take it away.
     *
     * It is [bind] that is null for such a mount, and the two are not the same thing — the user
     * can add a bind, the app can add a mount — so this is its own flag rather than a sentence
     * about [bind]. It also keeps the mount out of the generated `/etc/fstab`: the next boot makes
     * it again, and a second record of the same thing could only go stale.
     */
    val appOwned: Boolean = false,
    val bind: UserMount? = null,
) {
    init {
        require(mountPoint.startsWith("/")) { "mountPoint must be absolute: $mountPoint" }
    }

    companion object {
        /**
         * The options a mount of this kind carries, in one place.
         *
         * `mount`, `/proc/mounts` and the boot line all print this, so a string built twice is a
         * string that can disagree with itself — and a mount table that says `noexec` in one place
         * and not in another is a table nobody can trust to describe what is mounted.
         */
        fun optionsFor(readOnly: Boolean): String =
            if (readOnly) "ro,nosuid,nodev,noexec,relatime" else "rw,relatime"
    }

    /** True when the user made this mount with `vm mount`, as opposed to the kernel providing it. */
    fun isUserBind(): Boolean = bind != null

    /**
     * True when [path] is this mountpoint or something below it. The root is spelled out rather
     * than left to the prefix test, because `"/etc".startsWith("//")` is false and a root mount
     * that matched nothing would leave the whole namespace unreachable.
     */
    fun matches(path: String): Boolean =
        mountPoint == "/" || path == mountPoint || path.startsWith("$mountPoint/")

    /** [path] as the mounted [Vfs] wants it: absolute, and `/` for the mountpoint itself. */
    fun relative(path: String): String = when {
        mountPoint == "/" -> path
        path == mountPoint -> "/"
        else -> path.substring(mountPoint.length)
    }

    fun info(): MountInfo = MountInfo(source, mountPoint, fstype, options, note)
}

/** What `mount` and `/proc/mounts` print for one mount. */
data class MountInfo(
    val source: String,
    val target: String,
    val fstype: String,
    val options: String,
    /** The caveat, when there is one; `mount` prints it after the line, never instead of it. */
    val note: String? = null,
)

/** One line of boot output. [failed] lines are the ones a user has to read. */
data class BootLine(val text: String, val failed: Boolean = false)
