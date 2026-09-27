package omp.shell.fs

/**
 * Kinds of node a [Vfs] can hold. A real filesystem is reached through [RealVfs]; a namespace
 * synthesised in memory will report its own, which is exactly why this is a closed enum instead of
 * a `File`-shaped object the shell has to know the internals of.
 */
enum class VNodeType { FILE, DIRECTORY, SYMLINK, DEVICE, OTHER }

/**
 * Metadata for one path, or one entry of a directory listing. The size of a [VNodeType.SYMLINK] is
 * the length of its target, as `ls -l` shows it, and the access bits answer for the caller of this
 * [Vfs] — the process that will run the command — not for some abstract root.
 */
data class VStat(
    val type: VNodeType,
    val size: Long,
    val mtimeMillis: Long,
    /** Unix permission bits (0o644) when the backend has them, else 0. */
    val mode: Int = 0,
    /** The access(2) answer for the caller of this Vfs: `ls -l` prints these. */
    val readable: Boolean = true,
    val writable: Boolean = true,
    val executable: Boolean = false,
)

/** Free-space facts, so `df` works on a filesystem it has never heard of. */
data class VDiskUsage(val totalBytes: Long, val freeBytes: Long)

/** Every failure a [Vfs] reports, in the wording a Unix tool expects. */
enum class FsErrno(val text: String) {
    PERM_DENIED("Permission denied"), NO_SUCH_FILE("No such file or directory"),
    NOT_A_DIRECTORY("Not a directory"), IS_A_DIRECTORY("Is a directory"),
    NO_SPACE("No space left on device"), FILE_EXISTS("File exists"),
    SYMLINK_LOOP("Too many levels of symbolic links"), READ_ONLY("Read-only file system"),
    NOT_EMPTY("Directory not empty"), INVALID_ARGUMENT("Invalid argument"),
    DEVICE_BUSY("Device or resource busy"),
}

/**
 * Thrown by every [Vfs] method that can fail. `message` is [errno]'s [FsErrno.text] and nothing
 * else, so `Errno.messageFor` can hand the same wording to the user whether the failure came from
 * this seam or from an Android framework exception the shell has not wrapped yet.
 */
class FsException(val errno: FsErrno, val path: String? = null) :
    java.io.IOException(errno.text) { override val message: String get() = errno.text }

/** One name in a directory listing, with the [VStat] of the node behind it. */
data class VEntry(val name: String, val stat: VStat)

/**
 * The seam every path in the shell goes through. Paths are absolute and slash-separated, the way
 * the user typed them, and a [Vfs] decides what they name: [RealVfs] answers with the phone's own
 * filesystem, and a namespace that does not exist on disk will answer for itself. Nothing here
 * mentions `java.io.File` — that is the point: a command cannot reach around the seam by accident.
 *
 * Every method is total. A failure is an [FsException] with a [FsErrno], never a `null` and never a
 * silently empty result, because a command that cannot tell "empty directory" from "denied" would
 * have to guess which one to print.
 */
interface Vfs {
    fun stat(path: String): VStat                       // FsException(NO_SUCH_FILE) when absent
    fun readDir(path: String): List<VEntry>             // names + stats; PERM_DENIED / NOT_A_DIRECTORY
    fun openRead(path: String): java.io.InputStream     // IS_A_DIRECTORY for dirs
    fun openWrite(path: String, append: Boolean): java.io.OutputStream
    fun readBytes(path: String): ByteArray
    fun writeBytes(path: String, bytes: ByteArray)      // creates + truncates
    fun createFile(path: String)                        // O_CREAT|O_EXCL; FILE_EXISTS
    fun mkdir(path: String)                             // one level; FILE_EXISTS when it exists
    fun delete(path: String)                            // unlink; IS_A_DIRECTORY for a directory
    fun rename(from: String, to: String)
    fun symlink(target: String, link: String)           // FILE_EXISTS when link exists
    fun readLink(path: String): String                  // NO_SUCH_FILE / INVALID_ARGUMENT when not a link
    fun rmdir(path: String)                             // empty directory; NOT_EMPTY / NOT_A_DIRECTORY
    fun realpath(path: String): String                  // symlink-resolved absolute path, SYMLINK_LOOP past 20 hops
    fun diskUsage(path: String): VDiskUsage             // of the filesystem holding that path
    fun setModified(path: String, millis: Long)
}

/**
 * The component-by-component symlink walk every [Vfs] needs, on the [Vfs] rather than on
 * `java.nio`, so a namespace that is not on disk answers the same question by the same rules: `..`
 * is applied before each hop the way the kernel does, and a relative link target is read against
 * the directory holding the link.
 *
 * [RealVfs] answers for one filesystem and the VM's namespace answers for a mount table, but it is
 * the same walk, and a second copy of it is a second thing to get subtly wrong. A dangling link is
 * followed to its (nonexistent) target, which then fails with [FsErrno.NO_SUCH_FILE] at the point of
 * use rather than being silently ignored; a loop past [PathResolver.MAX_SYMLINK_HOPS] is
 * [FsErrno.SYMLINK_LOOP].
 */
fun resolveSymlinks(vfs: Vfs, path: String): String {
    var current = path
    var hops = 0
    while (hops <= PathResolver.MAX_SYMLINK_HOPS) {
        val link = firstSymlink(vfs, current) ?: return current
        val linkPath = link.first
        val parent = linkPath.substringBeforeLast('/', "/").ifEmpty { "/" }
        val target = if (link.second.startsWith("/")) link.second else parent.trimEnd('/') + "/" + link.second
        current = PathResolver.normalize(target + current.substring(linkPath.length))
        hops++
    }
    throw FsException(FsErrno.SYMLINK_LOOP, path)
}

/** @return the first symlink component of [path] as (path, target), or null. */
private fun firstSymlink(vfs: Vfs, path: String): Pair<String, String>? {
    var cur = ""
    for (part in path.split('/')) {
        if (part.isEmpty()) continue
        val next = if (cur.isEmpty()) "/$part" else "$cur/$part"
        val stat = try {
            vfs.stat(next)
        } catch (e: FsException) {
            // Nothing below a path that does not exist can itself be a link, so the walk carries on;
            // any other failure (a component this app may not look at) ends it, as it always has.
            if (e.errno != FsErrno.NO_SUCH_FILE) return null
            null
        }
        if (stat != null && stat.type == VNodeType.SYMLINK) {
            val target = try {
                vfs.readLink(next)
            } catch (e: FsException) {
                return null
            }
            return next to target
        }
        cur = next
    }
    return null
}
