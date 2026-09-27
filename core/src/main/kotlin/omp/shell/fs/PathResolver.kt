package omp.shell.fs

import omp.shell.Session

/** A resolution failure that should reach the user as a plain diagnostic. */
class PathException(message: String) : Exception(message)

/**
 * Turns a user-typed path into an absolute, lexically collapsed one. A mistake here makes every
 * file command report nonsense, so the steps are explicit and in this order: tilde, alias,
 * absolutise against the working directory, then collapse `//`, `/./` and `..` without touching
 * the filesystem.
 *
 * **It does not follow symbolic links, and that is deliberate.** A name a user types is the thing
 * they mean to act on, so `rm link` must unlink the link rather than whatever it points at, and
 * `ls -l link` must show a link. The old behaviour resolved first, which made every command operate
 * on the target and turned `rm -r link` into a recursive delete of the target's contents.
 *
 * The price is the one bash also pays without `-P`: `..` is collapsed against the *written* path, so
 * `cd /a/link/..` where `link -> /b` lands in `/a` and not `/b`. `cd` and `pwd` keep the logical
 * path for that reason, and a command that genuinely wants the object a link points at asks for it
 * once, through [Vfs.realpath] — never in a loop.
 */
object PathResolver {

    const val MAX_SYMLINK_HOPS = 20

    fun resolve(session: Session, raw: String): String {
        var p = expandTilde(session, raw)
        if (p.isEmpty()) throw PathException("No such file or directory")
        p = applyAliases(session, p)
        if (!p.startsWith("/")) p = session.cwd.trimEnd('/') + "/" + p
        return normalize(p)
    }

    /**
     * `~` to `$HOME`, `~+` to the previous directory, and the shared-storage aliases. `$HOME` comes
     * from the environment, so a namespace whose home is somewhere else gets its own.
     */
    fun expandTilde(session: Session, raw: String): String {
        if (raw == "~") return session.home()
        if (raw.startsWith("~/")) return session.home().trimEnd('/') + raw.substring(1)
        if (raw == "~+") return session.oldPwd
        if (raw.startsWith("~+/")) return session.oldPwd.trimEnd('/') + raw.substring(2)
        return raw
    }

    fun applyAliases(session: Session, path: String): String {
        val ext = session.services.externalStorageDir() ?: return path
        val trimmed = path.trimEnd('/')
        for (alias in STORAGE_ALIASES) {
            if (trimmed == alias) return ext
            if (trimmed.startsWith("$alias/")) return ext + trimmed.substring(alias.length)
        }
        return path
    }

    /** Collapses `//`, `/./` and `..` without touching the filesystem. */
    fun normalize(path: String): String {
        val absolute = path.startsWith("/")
        val out = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.size - 1) else if (!absolute) out.add("..")
                else -> out.add(part)
            }
        }
        val joined = out.joinToString("/")
        return if (absolute) "/$joined" else if (joined.isEmpty()) "." else joined
    }

    /**
     * The symlink-free absolute form of [path] as [vfs] sees it: component by component, so `..`
     * is applied before each hop the way the kernel does, and a relative link target is read
     * against the directory holding the link. A dangling link is followed to its (nonexistent)
     * target, which then fails with `No such file or directory` at the point of use rather than
     * being silently ignored.
     *
     * A loop past [MAX_SYMLINK_HOPS] is [FsErrno.SYMLINK_LOOP], and anything else the [Vfs] could
     * not resolve comes back the same way; both reach the user as a [PathException], which is
     * what every caller already catches.
     */
    fun canonical(vfs: Vfs, path: String): String = try {
        vfs.realpath(path)
    } catch (e: FsException) {
        throw PathException(e.errno.text)
    }

    /** The reverse substitution, for prompts and for paths the user will see again. */
    fun contract(session: Session, path: String): String {
        val home = session.home().trimEnd('/')
        if (path == home) return "~"
        if (path.startsWith("$home/")) return "~" + path.substring(home.length)
        return path
    }

    private val STORAGE_ALIASES = listOf("/sdcard", "/mnt/sdcard", "/storage/emulated/0", "/storage/self/primary", "/sdcard0")
}
