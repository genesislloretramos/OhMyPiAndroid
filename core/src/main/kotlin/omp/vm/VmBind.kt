package omp.vm

import omp.shell.PlatformServices
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * One user bind, as `/etc/fstab` records it: a real directory on this device, the namespace path it
 * is to appear at, and whether it is read-only.
 *
 * The line this writes is a real fstab line, all six fields, and it is the *bind* form a real one
 * uses — `<device> <mountpoint> none bind[,ro] 0 0` — because a bind has no filesystem of its own to
 * name. The device column is the host path, which is what a real bind's fstab entry also carries
 * (a block device is not reachable from here, and `/proc/mounts` is closed to an app, so the path
 * is the only honest device this process can name). Whitespace in a field is escaped the way a real
 * fstab escapes it, `\040` and friends, because `/storage/emulated/0/My Documents` is a perfectly
 * ordinary Android directory and an unescaped space would split one field into two.
 *
 * Parsing is the inverse and deliberately forgiving: a comment, a blank line, a field count short
 * of six, a line that is not a bind — all of them are somebody else's line or a mistake, and the
 * answer to both is to leave the mount table alone.
 */
data class UserMount(
    val hostPath: String,
    val mountPoint: String,
    val readOnly: Boolean,
) {
    /** The fstab line, without its newline. */
    fun line(): String = "${escape(hostPath)} ${escape(mountPoint)} none ${options()} 0 0"

    /** `bind`, and `bind,ro` for the read-only ones — the two forms a real fstab writes. */
    fun options(): String = if (readOnly) "$BIND,$READ_ONLY" else BIND

    companion object {
        private const val BIND = "bind"
        private const val READ_ONLY = "ro"

        /** @return the bind [line] describes, or null when it is not one. */
        fun parse(line: String): UserMount? {
            val fields = fields(line)
            if (fields.size < 4) return null
            val options = fields[3].split(',').map { it.trim() }
            if (BIND !in options) return null
            return UserMount(
                hostPath = unescape(fields[0]),
                mountPoint = unescape(fields[1]),
                readOnly = READ_ONLY in options,
            )
        }

        /** Every bind in an fstab, in file order, which is the order they are mounted in. */
        fun parseAll(text: String): List<UserMount> =
            text.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { parse(it) }
                .toList()

        /** fstab's own escaping: a backslash, and the three whitespace bytes a field cannot hold. */
        fun escape(field: String): String = buildString {
            for (c in field) when (c) {
                '\\' -> append("\\\\")
                ' ' -> append("\\040")
                '\t' -> append("\\011")
                '\n' -> append("\\012")
                else -> append(c)
            }
        }

        fun unescape(field: String): String {
            if (!field.contains('\\')) return field
            val out = StringBuilder()
            var i = 0
            while (i < field.length) {
                val c = field[i]
                if (c != '\\') {
                    out.append(c)
                    i++
                    continue
                }
                val octal = field.substring(i + 1, minOf(i + 4, field.length))
                val value = if (octal.length == 3 && octal.all { it in '0'..'7' }) {
                    octal.toInt(8)
                } else {
                    // `\x` is not an escape fstab defines, so the backslash is the character.
                    '\\'.code
                }
                out.append(value.toChar())
                i += if (value == '\\'.code) 2 else 4
            }
            return out.toString()
        }

        /** Whitespace-separated fields, with the octal escapes already resolved. */
        private fun fields(line: String): List<String> {
            val out = ArrayList<String>()
            val current = StringBuilder()
            var i = 0
            while (i < line.length) {
                val c = line[i]
                val octal = if (c == '\\') line.substring(i + 1, minOf(i + 4, line.length)) else ""
                when {
                    octal.length == 3 && octal.all { it in '0'..'7' } -> {
                        current.append(octal.toInt(8).toChar())
                        i += 4
                    }
                    c == ' ' || c == '\t' -> {
                        if (current.isNotEmpty()) {
                            out += current.toString()
                            current.setLength(0)
                        }
                        i++
                    }
                    else -> {
                        current.append(c)
                        i++
                    }
                }
            }
            if (current.isNotEmpty()) out += current.toString()
            return out
        }
    }
}

/**
 * What a bind did, or why it did not happen.
 *
 * Three cases and no fourth, because a caller has to be able to tell a mount from a refusal from a
 * removal without reading a string: [omp.vm.VmCommand] prints one and exits 0, prints the other
 * and exits 1, and the boot log marks the third as a failure. A refusal carries the `vm mount: `
 * prefix, because the user typed that and a diagnostic naming a different command is a diagnostic
 * about the wrong thing.
 */
sealed class BindResult {
    /** A bind is in the table and in `/etc/fstab`. */
    data class Mounted(val user: UserMount) : BindResult()

    /** A bind the user made is out of the table and out of `/etc/fstab`. */
    data class Unmounted(val user: UserMount) : BindResult()

    /** Nothing was changed, and [message] says why. */
    data class Refused(val message: String) : BindResult()

    /** The message, whichever case this is; a mounted bind has a line of its own to print. */
    fun describe(): String = when (this) {
        is Mounted -> "${user.hostPath} on ${user.mountPoint}"
        is Unmounted -> "${user.mountPoint} no longer shows ${user.hostPath}"
        is Refused -> message
    }
}

/**
 * What this app is allowed to bind, which is the whole honest limit of the feature.
 *
 * A bind here is a [omp.shell.fs.Vfs] over a directory on the device, and the app is already
 * running as its own uid: there is no `mount(2)`, no `CAP_SYS_ADMIN` and no namespace to change
 * anything. So a bind can only ever expose what the app can *already* read, and the question
 * "can it?" has exactly two answers on Android — the app's own storage, which needs no permission
 * at all, and the shared storage tree, which needs "All files access" and nothing else. Anything
 * else — `/system`, `/data`, another app's `/data/data/…` — is closed by the platform, and a bind
 * cannot grant what the app does not have. Saying so in one sentence, and refusing, is the only
 * honest answer; binding an empty directory and calling it a success would be the opposite.
 */
object HostAccess {

    /**
     * @return why the app cannot reach [path], or null when it can. [path] is compared in its
     * canonical form, so `..` and a symlink out of the app's own storage cannot smuggle a bind
     * past the roots below.
     */
    fun refusal(services: PlatformServices, path: String): String? {
        val target = canonical(path)
        val roots = roots(services)
        // The longest root wins, so a grant-gated root nested inside a private one still decides:
        // it is the shared-storage rule that governs a shared-storage path, and the two trees
        // only ever nest in a test stub. On a device they are disjoint.
        val root = roots.filter { contains(it.first, target) }.maxByOrNull { it.first.length }
            ?: return "this app cannot read $path: Android does not grant it, and a bind cannot " +
                "grant what the app does not have"
        if (!root.second || services.isExternalStorageManager()) return null
        return "this app cannot read $path: it is shared storage and this app has no \"All files " +
            "access\" grant; run grant-storage on the phone and reboot the VM — a bind cannot " +
            "grant what the app does not have"
    }

    /**
     * The directories a bind may point at, each with whether reaching it needs the all-files grant.
     *
     * The app's own storage is two entries because [PlatformServices.homeDir] is a subdirectory of
     * [PlatformServices.appFilesDir] on a real device and a sibling in the stub, and a policy that
     * only knew one of them would be right on one and wrong on the other.
     */
    private fun roots(services: PlatformServices): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        for (own in listOf(services.appFilesDir(), services.homeDir())) {
            if (own.isBlank()) continue
            out += canonical(own) to false
        }
        services.externalStorageDir()?.takeIf { it.isNotBlank() }?.let { out += canonical(it) to true }
        // Removable volumes are shared storage too, and the same grant is what opens them; the
        // platform reports where they are mounted and an app reaches them by that path or not at
        // all. An empty list on a device with no removable volume, which is most of them.
        for (volume in services.storageVolumes()) {
            if (!volume.mountPoint.startsWith("/")) continue
            out += canonical(volume.mountPoint) to true
        }
        return out
    }

    /**
     * The filesystem type the platform will name for [path], or `unknown`.
     *
     * `getFileStore` is the only answer available here: `/proc/mounts` is closed to an app, so
     * there is no kernel's own name for the filesystem to be had. It often has one anyway and
     * sometimes says nothing, and `unknown` is the honest rendering of "nothing" — the app
     * guessing `ext4` because the rest of the table says it would be the same small lie as a
     * `df` column copied from `/`.
     */
    fun fstypeOf(path: String): String = try {
        Files.getFileStore(File(path).toPath()).type().trim().ifEmpty { UNKNOWN }
    } catch (e: IOException) {
        UNKNOWN
    } catch (e: SecurityException) {
        UNKNOWN
    }

    /** The last path segment, which is what a default mount point is named after. */
    fun basename(path: String): String = path.trimEnd('/').substringAfterLast('/')

    private fun canonical(path: String): String = try {
        File(path).canonicalPath
    } catch (e: IOException) {
        File(path).absolutePath
    }

    /** Containment on a path boundary: `/data/data/com.omp.term` does not contain `/data/data/com.omp.terms`. */
    private fun contains(root: String, path: String): Boolean =
        path == root || path.startsWith(if (root.endsWith('/')) root else "$root/")

    /** What `mount` prints when it cannot name the filesystem. */
    const val UNKNOWN = "unknown"
}
