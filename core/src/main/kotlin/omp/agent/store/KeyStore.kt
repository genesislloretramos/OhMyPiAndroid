package omp.agent.store

import omp.vm.workspace.WorkspaceName
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet

/**
 * The user's provider key, one file per provider, in the app's own private storage.
 *
 * **The file is protected by Android's per-app sandbox, and is not encrypted at rest.** A file
 * under `files/` is unreadable by other apps and by a file manager, and that is the whole of it:
 * anything with the app's own uid — a debuggable build, an `adb backup`, a root shell — reads the
 * plaintext, so no part of this class may ever claim otherwise. There is no Keystore-backed
 * wrapping here and no claim of one.
 *
 * [dir] is the app's private files directory, from `PlatformServices.appFilesDir()`, and the
 * subdirectory below it is this class's own business, not the caller's: a caller that could aim
 * this at any directory it liked is exactly the mistake the class exists to make impossible, and
 * the wrong directory to aim it at — `Documents/omp`, a container any app with storage permission
 * can read — is one string away.
 *
 * ### The two places this breaks the `Vfs` seam
 *
 * Both are properties of the *location*, not of the data, and neither has anywhere to live in a
 * [omp.shell.fs.Vfs] interface the whole shell shares:
 *
 *  1. **The private directory itself.** The agent runs inside the VM, whose namespace is a rootfs
 *     with `/mnt/omp` bound onto the user's `Documents`; `appFilesDir()` is not in that namespace at
 *     all, so there is no path this could pass through the seam. What it *cannot* do for that
 *     reason: a conversation-scoped or VM-scoped `Vfs` cannot see or audit a key, and the only way
 *     to inspect one is `pathOf`, on this class.
 *  2. **The POSIX mode.** The `Vfs` has no `chmod` — adding one would change an interface every
 *     command shares, which is the precedent `omp.vm.rootfs.Rootfs.setMode` also followed. What
 *     that leaves undone: on a filesystem with no POSIX modes (below API 26, or a FAT volume) the
 *     file lands at the process umask, and this class cannot narrow it — the sandbox, not the
 *     mode, is what is actually keeping the key in.
 *
 * [omp.vm.rootfs.Rootfs.setMode] is the same two lines against a directory the VM owns; importing
 * it would put the agent's persistence underneath the VM's, so they are written out here and the
 * reason is this paragraph.
 *
 * ### What may never appear in a diagnostic
 *
 * Not the secret, not a prefix of it, not its length: a truncated key is a credential that has
 * leaked. Every message below names the **provider** and the **file**, and [pathOf] is how a
 * command tells the user exactly where theirs is. [toString] is a diagnostic like any other.
 */
class KeyStore(
    private val dir: File,
    /** The wall clock, injected as everywhere else, so a test can say when a key was stored. */
    private val now: () -> Long,
) {

    /**
     * Stores [secret] for [provider], replacing whatever was there.
     *
     * Written to a scratch file in the same directory and renamed over the target, so a reader —
     * this class, or a diagnostic run between two calls — sees the whole old key or the whole new
     * one. The scratch is narrowed to 0600 *before* a byte of the secret is written, so the key is
     * never on disk under any other mode, not even for the length of one write.
     *
     * @throws IllegalArgumentException when the provider name is not a plain name, or the secret is
     *   empty, holds a control character, or is longer than [MAX_SECRET_BYTES]. Every message says
     *   which of those it is and why, and none of them quotes the secret.
     * @throws IOException when the private directory or the file cannot be created or renamed.
     */
    fun put(provider: String, secret: String) {
        val home = keyDir()
        val target = File(home, nameOf(provider) + EXT)
        val scratch = File(home, target.name + SCRATCH_EXT)
        // The file is resolved first so that a refusal can name it: a diagnostic that says which
        // provider and which file is one a user can act on without a second command.
        checkSecret(target, secret)
        if (!home.isDirectory) {
            if (!home.mkdirs() && !home.isDirectory) {
                throw IOException("key store: cannot create the private directory ${home.path}")
            }
            narrow(home, onDirectory = true)
        }
        writeSecret(scratch, secret)
        if (!scratch.renameTo(target)) {
            // Falling back to a copy would be a window in which there is no key at all, which is the
            // failure this class is not allowed to have; saying so is better than doing it.
            throw IOException("key store: cannot replace ${target.path} from ${scratch.path}")
        }
        // After the bytes: writing is what sets the time, and the mtime is the only "when was this
        // stored" a diagnostic can show for a key — there is no header to carry one.
        target.setLastModified(now())
    }

    /**
     * The key stored for [provider], or null when there is none.
     *
     * The file's whole contents, byte for byte: a key is opaque, so nothing is trimmed, normalised
     * or case-folded on the way in or out. A trailing newline the user's clipboard added is
     * therefore a trailing newline the provider will reject, which is a better failure than this
     * class quietly repairing a credential it was given.
     *
     * @throws IllegalArgumentException when the provider name is not a plain name — the same
     *   refusal [put] makes, so a lookup cannot reach a different file than a write would.
     */
    fun get(provider: String): String? {
        val file = fileFor(provider)
        if (!file.isFile) return null
        return file.readText(Charsets.UTF_8)
    }

    /** Whether a key is stored for [provider]. Never reads the file: existence is the question. */
    fun has(provider: String): Boolean = fileFor(provider).isFile

    /**
     * Removes the key for [provider] and nothing else.
     *
     * A scratch file left behind by a write that was cut short goes with it, because a key that
     * `put` was in the middle of writing is a key that must not survive the refusal to write the
     * real one.
     *
     * @return whether there was one to remove, so a command can say "forgot anthropic" and "there
     *   was no anthropic key" differently instead of claiming both did the same thing.
     * @throws IOException when the file is there and could not be removed — a `forget` that
     *   reported success and left the key would be the worst answer this class can give.
     */
    fun forget(provider: String): Boolean {
        val target = fileFor(provider)
        val scratch = File(target.parentFile, target.name + SCRATCH_EXT)
        if (scratch.exists() && !scratch.delete()) {
            throw IOException("key store: cannot remove the leftover ${scratch.path}")
        }
        if (!target.isFile) return false
        if (!target.delete()) throw IOException("key store: cannot remove ${target.path}")
        return true
    }

    /**
     * The providers that have a key, sorted.
     *
     * Sorted because this is what a command lists and a listing whose order comes from the
     * filesystem cannot be talked about. The names are the file names without [EXT] — the secret is
     * not here, and neither is anything about it.
     */
    fun providers(): List<String> {
        val names = keyDir().list() ?: return emptyList()
        return names.asSequence()
            .filter { it.length > EXT.length && it.endsWith(EXT) }
            .map { it.substring(0, it.length - EXT.length) }
            .filter { it.isNotEmpty() }
            .sorted()
            .toList()
    }

    /**
     * The real path of the file that holds [provider]'s key, for a diagnostic that has to tell the
     * user where it is.
     *
     * It exists whether or not there is a key in it, because the answer to "where would mine go"
     * and to "where is mine" should be the same string. A `Vfs` path is not offered and could not
     * be: this directory is not in the namespace the agent runs in, which is the first thing in
     * this class's KDoc.
     */
    fun pathOf(provider: String): String = fileFor(provider).path

    /**
     * Where a key is kept, and for which providers. Never a secret, and never a fact about one: a
     * crash report carrying this line says the store exists and which names are in it, which is the
     * half that is safe to log and the half that is worth having.
     */
    override fun toString(): String = "KeyStore(dir=${dir.path}, providers=${providers()})"

    // ---- the file helpers ---------------------------------------------------------------

    /** The one name a provider is stored under, and the one every message about it uses. */
    private fun nameOf(provider: String): String {
        if (provider.isBlank()) {
            throw IllegalArgumentException("key store: no provider name — a key is stored per provider")
        }
        val clean = WorkspaceName.sanitize(provider)
            ?: throw IllegalArgumentException(
                "key store: '$provider' is not a name at all, so there is no file to put a key in",
            )
        if (clean != provider) {
            // Refused rather than sanitised into a different name. The same string is typed to
            // `get` and to `has`, and a name that changes between the write and the read is a name
            // that misses — the user would be told their key was gone, from a store that has it.
            throw IllegalArgumentException(
                "key store: '$provider' is not a plain provider name; it would be stored as " +
                    "'$clean' in ${File(keyDir(), clean + EXT).path}, and the same string has to " +
                    "be used to read it back",
            )
        }
        return clean
    }

    /**
     * The three ways a pasted key is not a key.
     *
     * A control character is refused rather than stripped because the shell is line-based: a key
     * with a newline in it cannot be typed at the prompt, echoed, or diffed, and a provider's
     * answer to one is a 401 the user will read as a wrong key. Every refusal names [target] and
     * nothing else about the secret: not a prefix, not a length, not a character class it was
     * nearly.
     */
    private fun checkSecret(target: File, secret: String) {
        val where = target.path
        if (secret.isEmpty()) {
            throw IllegalArgumentException(
                "key store: no key for the ${target.name} file at $where — paste the key itself, " +
                    "not an empty line",
            )
        }
        if (secret.isBlank()) {
            throw IllegalArgumentException(
                "key store: the key for $where is only whitespace — that is a paste accident",
            )
        }
        var i = 0
        while (i < secret.length) {
            val cp = secret.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isISOControl(cp)) {
                val shown = if (cp == '\n'.code) "a newline" else "U+%04X".format(cp)
                throw IllegalArgumentException(
                    "key store: the key for $where contains $shown — a key is one line, and a " +
                        "provider rejects the rest of it as a wrong key",
                )
            }
        }
        val bytes = secret.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_SECRET_BYTES) {
            throw IllegalArgumentException(
                "key store: the key for $where is $bytes bytes, over the $MAX_SECRET_BYTES-byte " +
                    "limit — that is a file or a whole response, not an API key",
            )
        }
    }

    /** The directory this class owns inside the app's private storage. */
    private fun keyDir(): File = File(dir, SUBDIR)

    private fun fileFor(provider: String): File = File(keyDir(), nameOf(provider) + EXT)

    /**
     * The file created, narrowed to 0600, and only then written.
     *
     * `createNewFile` answers false both for "it is already there" and for "there is nowhere to put
     * it", so the reason is read off the path rather than off the return value — the same repair
     * `omp.shell.fs.RealVfs.createFile` makes.
     */
    private fun writeSecret(target: File, secret: String) {
        if (!target.createNewFile() && !target.isFile) {
            throw IOException("key store: cannot create ${target.path}")
        }
        narrow(target)
        FileOutputStream(target, false).use { it.write(secret.toByteArray(Charsets.UTF_8)) }
    }

    /**
     * 0600 on a file, 0700 on a directory when [onDirectory] asks for it, best effort — and the
     * best effort is a decision rather than an accident.
     *
     * `java.nio` is the only API that can say "the owner, and nobody else" — `File.setWritable(false,
     * false)` is a statement about everybody and leaves a file any process in the app's uid can
     * rewrite. On a filesystem with no POSIX modes the call throws and the file keeps the umask;
     * the sandbox is still there, and the class's KDoc says exactly that rather than implying the
     * mode is what is protecting the key.
     */
    private fun narrow(target: File, onDirectory: Boolean = false) {
        try {
            val want = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            )
            if (onDirectory) want += PosixFilePermission.OWNER_EXECUTE
            if (Files.getPosixFilePermissions(target.toPath()) != want) {
                Files.setPosixFilePermissions(target.toPath(), want)
            }
        } catch (e: UnsupportedOperationException) {
            // No POSIX modes on this filesystem: see the class KDoc. The file is still inside
            // files/, which is the protection that actually holds.
        } catch (e: IOException) {
            // Same: a filesystem that will not store the mode is not one this class can argue with,
            // and a key that is never written is worse than a key one mode too open inside a
            // sandbox no other app is in.
        }
    }

    companion object {
        /**
         * This class's own directory inside `appFilesDir()`.
         *
         * 0700, created on the first [put] rather than by the constructor: a `KeyStore` built for a
         * diagnostic should not leave a directory behind just by existing.
         */
        const val SUBDIR = "agent"

        /** One file per provider, holding exactly the secret. */
        const val EXT = ".key"

        /** A write in progress, renamed over [EXT] when it is complete. */
        const val SCRATCH_EXT = ".tmp"

        /**
         * 4 KiB, in UTF-8 bytes.
         *
         * Every key in use is a few hundred characters; 4 KiB is three orders of magnitude above
         * that and two below the point where a pasted PEM block, an entire JSON response or a
         * copied document would fit. It is here so that "the user pasted the wrong thing" is a
         * message with a number in it rather than a megabyte of somebody's private file sitting in
         * app storage under a name that says it is a credential.
         */
        const val MAX_SECRET_BYTES = 4096
    }
}
