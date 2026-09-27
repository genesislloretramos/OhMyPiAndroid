package omp.shell.fs

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission

/**
 * The phone's own filesystem behind the [Vfs] seam, optionally moved or bound.
 *
 * [root] is a real directory every path is resolved inside, and the empty string means the device
 * root, so `RealVfs()` is exactly the namespace the shell has always had. [prefixes] maps absolute
 * paths onto real ones (`/mnt/android` onto the user's storage) with the longest match winning,
 * which is what a mount is: the same path means a different directory, and *every* method has to
 * agree about that, including [diskUsage] and [setModified]. So the mapping lives in [host] and
 * nowhere else — a second place that resolved paths would be a bind honoured by `cat` and ignored
 * by `df`, which is worse than no bind at all.
 *
 * Failures are translated by [at] from the JDK's own wording into an [FsErrno]. One honest gap: the
 * JDK throws [java.nio.file.FileSystemException] with a reason string but no portable errno, and
 * `File.delete`/`File.mkdir` swallow the real one and answer `false`, so a failure the wording does
 * not name is reported as [FsErrno.NO_SUCH_FILE] — the diagnostic this shell has always fallen back
 * to.
 */
class RealVfs(
    private val root: String = "",
    private val prefixes: List<Pair<String, String>> = emptyList(),
) : Vfs {

    /** Longest first, so `/mnt/android` wins over `/mnt` and a bind is never half-honoured. */
    private val binds: List<Pair<String, String>> =
        prefixes.map { (vm, real) -> vm.trimEnd('/') to real }.sortedByDescending { it.first.length }

    /**
     * True for the phone's own filesystem with nothing bound onto it, and only then does a session
     * prepare `$HOME` itself: a namespace that brings its own Vfs brings its own home with it.
     */
    val isDeviceRoot: Boolean = root.isEmpty() && prefixes.isEmpty()

    /** The one place a path in the shell's namespace becomes a path on this device. */
    private fun host(path: String): File {
        val absolute = if (path.startsWith("/")) path else "/$path"
        for ((vm, real) in binds) {
            if (absolute == vm) return File(real)
            if (absolute.startsWith("$vm/")) return File(real, absolute.substring(vm.length + 1))
        }
        if (root.isEmpty()) return File(absolute)
        return File(root, absolute.substring(1))
    }

    /**
     * The real path [path] names on this device, in this filesystem's own terms: a bound path
     * resolves through the bind map, and anything else through [root]. Read-only and additive —
     * every method above keeps resolving through [host], which is still the one place that happens.
     *
     * It exists for the one question a [Vfs] holder cannot answer from the namespace alone: where
     * are these bytes *really*. A bind is the whole idea behind `/mnt/omp`, and a caller that has
     * only the namespace path is holding a name, not a location — so it has to be able to ask the
     * filesystem that did the binding rather than being handed a second string to keep in step with
     * the first. The path is not resolved to a canonical form on purpose: the answer is the path
     * the user types into a file manager, and `File.getCanonicalPath` would expand a symlinked
     * shared-storage root into something that does not exist from their point of view.
     */
    fun hostPathOf(path: String): String = host(path).path

    private fun hostPath(path: String): Path = host(path).toPath()

    /** Appends a [readDir] entry name to the directory it came from. */
    private fun child(dir: String, name: String): String = dir.trimEnd('/') + "/" + name

    override fun stat(path: String): VStat {
        val file = host(path)
        val node = file.toPath()
        val attrs = at(path) {
            Files.readAttributes(node, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }
        return VStat(
            type = when {
                // A device node is not something `java.io.File` can tell apart from a socket or a
                // fifo, so it lands in OTHER; a Vfs built in memory can say DEVICE outright.
                attrs.isSymbolicLink -> VNodeType.SYMLINK
                attrs.isDirectory -> VNodeType.DIRECTORY
                attrs.isRegularFile -> VNodeType.FILE
                else -> VNodeType.OTHER
            },
            size = attrs.size(),
            mtimeMillis = attrs.lastModifiedTime().toMillis(),
            mode = modeOf(node),
            // Follows the link, which is what `ls -l` prints: the target's bits, with `l` for the type.
            readable = file.canRead(),
            writable = file.canWrite(),
            executable = file.canExecute(),
        )
    }

    override fun readDir(path: String): List<VEntry> {
        val file = host(path)
        if (!file.exists()) throw FsException(FsErrno.NO_SUCH_FILE, path)
        if (!file.isDirectory) throw FsException(FsErrno.NOT_A_DIRECTORY, path)
        if (!file.canRead()) throw FsException(FsErrno.PERM_DENIED, path)
        val names = at(path) { file.list() } ?: throw FsException(FsErrno.PERM_DENIED, path)
        val out = ArrayList<VEntry>(names.size)
        // Sorted: the order the kernel hands entries back is arbitrary, and a listing the user
        // cannot predict is a listing `ls` would have to sort again to be worth printing.
        for (name in names.sorted()) out += VEntry(name, stat(child(path, name)))
        return out
    }

    override fun openRead(path: String): InputStream {
        val file = host(path)
        if (file.isDirectory) throw FsException(FsErrno.IS_A_DIRECTORY, path)
        if (!file.exists()) throw FsException(FsErrno.NO_SUCH_FILE, path)
        return at(path) { FileInputStream(file) }
    }

    override fun openWrite(path: String, append: Boolean): OutputStream {
        val file = host(path)
        if (file.isDirectory) throw FsException(FsErrno.IS_A_DIRECTORY, path)
        return at(path) { FileOutputStream(file, append) }
    }

    override fun readBytes(path: String): ByteArray {
        val file = host(path)
        if (file.isDirectory) throw FsException(FsErrno.IS_A_DIRECTORY, path)
        if (!file.exists()) throw FsException(FsErrno.NO_SUCH_FILE, path)
        return at(path) { file.readBytes() }
    }

    override fun writeBytes(path: String, bytes: ByteArray) {
        val file = host(path)
        if (file.isDirectory) throw FsException(FsErrno.IS_A_DIRECTORY, path)
        at(path) { file.writeBytes(bytes) }
    }

    override fun createFile(path: String) {
        val file = host(path)
        if (file.exists()) throw FsException(FsErrno.FILE_EXISTS, path)
        val parent = file.parentFile ?: throw FsException(FsErrno.NO_SUCH_FILE, path)
        if (!parent.isDirectory) throw FsException(FsErrno.NO_SUCH_FILE, path)
        // `createNewFile` answers false both for "it is already there" and for "there is nowhere to
        // put it", so the reason has to be read off the path rather than off its return value.
        if (file.createNewFile()) return
        throw if (file.exists()) FsException(FsErrno.FILE_EXISTS, path) else FsException(FsErrno.NO_SUCH_FILE, path)
    }

    override fun mkdir(path: String) {
        val file = host(path)
        if (file.exists()) throw FsException(FsErrno.FILE_EXISTS, path)
        val parent = file.parentFile ?: throw FsException(FsErrno.NO_SUCH_FILE, path)
        if (!parent.isDirectory) throw FsException(FsErrno.NO_SUCH_FILE, path)
        if (file.mkdir()) return
        throw if (file.exists()) FsException(FsErrno.FILE_EXISTS, path) else FsException(FsErrno.NO_SUCH_FILE, path)
    }

    override fun delete(path: String) {
        // `File.isDirectory` follows a link, so asking it would refuse to unlink a link that points
        // at a directory — and `File.exists` would call a dangling link absent, so `rm` could not
        // remove one either. `unlink(2)` answers about the name, which is the no-follow stat.
        if (stat(path).type == VNodeType.DIRECTORY) throw FsException(FsErrno.IS_A_DIRECTORY, path)
        // `delete` unlinks the link itself when the path is one, which is what `rm` does.
        if (!at(path) { host(path).delete() }) throw FsException(FsErrno.PERM_DENIED, path)
    }

    override fun rename(from: String, to: String) {
        at(from) { Files.move(hostPath(from), hostPath(to), REPLACE_EXISTING) }
    }

    override fun symlink(target: String, link: String) {
        val node = hostPath(link)
        // `exists` follows the link, so a dangling one looks absent: ask about the link itself too.
        if (Files.exists(node, LinkOption.NOFOLLOW_LINKS)) throw FsException(FsErrno.FILE_EXISTS, link)
        at(link) { Files.createSymbolicLink(node, Paths.get(target)) }
    }

    override fun readLink(path: String): String {
        val node = hostPath(path)
        if (!Files.exists(node, LinkOption.NOFOLLOW_LINKS)) throw FsException(FsErrno.NO_SUCH_FILE, path)
        if (!Files.isSymbolicLink(node)) throw FsException(FsErrno.INVALID_ARGUMENT, path)
        return at(path) { Files.readSymbolicLink(node).toString() }
    }

    /**
     * `rmdir(2)`, which [delete] deliberately does not do: a directory needs its own name in the
     * seam, or `rm -r` and `rmdir` would be a syscall apart and one of them would have to reach
     * around the Vfs to close the gap. A symlink is [FsErrno.NOT_A_DIRECTORY] even when it points
     * at a directory, and [FsErrno.NOT_EMPTY] is what an occupied one says — the two things
     * `rmdir` reports verbatim.
     */
    override fun rmdir(path: String) {
        if (stat(path).type != VNodeType.DIRECTORY) throw FsException(FsErrno.NOT_A_DIRECTORY, path)
        val file = host(path)
        val left = at(path) { file.list() }
        if (left != null && left.isNotEmpty()) throw FsException(FsErrno.NOT_EMPTY, path)
        if (!at(path) { file.delete() }) throw FsException(FsErrno.PERM_DENIED, path)
    }

    /**
     * The symlink-free absolute form of [path], resolved against this filesystem only. The walk
     * itself is [resolveSymlinks], shared with the VM's namespace: what a mount means is this
     * class's business, how a link chain is followed is the seam's.
     */
    override fun realpath(path: String): String = resolveSymlinks(this, path)

    /**
     * The space of the filesystem holding [path]. A path that is not there yet walks up to the
     * nearest directory that is: `df` on a mount point that has not been created should report the
     * filesystem it would land on, not fail.
     */
    override fun diskUsage(path: String): VDiskUsage {
        var probe = host(path)
        while (!probe.exists() && probe.parentFile != null) probe = probe.parentFile
        val store = at(path) { Files.getFileStore(probe.toPath()) }
        return VDiskUsage(store.getTotalSpace(), store.getUsableSpace())
    }

    override fun setModified(path: String, millis: Long) {
        val node = hostPath(path)
        if (!Files.exists(node)) throw FsException(FsErrno.NO_SUCH_FILE, path)
        at(path) { Files.setLastModifiedTime(node, FileTime.fromMillis(millis)) }
    }

    /**
     * The nine permission bits, or 0 on a filesystem with no POSIX mode to read. Kotlin has no
     * octal literal, so the bits are written in hex: 0x1A4 is 0644, 0x1ED is 0755.
     */
    private fun modeOf(node: Path): Int = try {
        var mode = 0
        for (p in Files.getPosixFilePermissions(node, LinkOption.NOFOLLOW_LINKS)) {
            mode = mode or when (p) {
                PosixFilePermission.OWNER_READ -> 0x100
                PosixFilePermission.OWNER_WRITE -> 0x080
                PosixFilePermission.OWNER_EXECUTE -> 0x040
                PosixFilePermission.GROUP_READ -> 0x020
                PosixFilePermission.GROUP_WRITE -> 0x010
                PosixFilePermission.GROUP_EXECUTE -> 0x008
                PosixFilePermission.OTHERS_READ -> 0x004
                PosixFilePermission.OTHERS_WRITE -> 0x002
                PosixFilePermission.OTHERS_EXECUTE -> 0x001
            }
        }
        mode
    } catch (e: UnsupportedOperationException) {
        0
    } catch (e: IOException) {
        0
    }

    /**
     * Runs one filesystem call and answers in the shell's own vocabulary. [FsException] passes
     * through untouched, [SecurityException] is the [FsErrno.PERM_DENIED] an Android app gets for
     * another app's files, and a missing path is the [java.io.FileNotFoundException] the JDK
     * throws before it ever looks at the reason.
     */
    private inline fun <T> at(path: String, body: () -> T): T = try {
        body()
    } catch (e: FsException) {
        throw e
    } catch (e: SecurityException) {
        throw FsException(FsErrno.PERM_DENIED, path)
    } catch (e: IllegalArgumentException) {
        throw FsException(FsErrno.INVALID_ARGUMENT, path)
    } catch (e: FileNotFoundException) {
        throw FsException(FsErrno.NO_SUCH_FILE, path)
    } catch (e: IOException) {
        throw FsException(translate(e), path)
    }

    /** The JDK reports a reason string; `fs: File exists` and `File exists: fs` both read. */
    private fun translate(e: IOException): FsErrno {
        val text = e.message ?: return FsErrno.NO_SUCH_FILE
        for (errno in FsErrno.entries) {
            if (text.contains(errno.text)) return errno
        }
        return FsErrno.NO_SUCH_FILE
    }
}
