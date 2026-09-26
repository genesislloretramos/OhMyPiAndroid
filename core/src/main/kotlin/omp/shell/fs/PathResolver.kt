package omp.shell.fs

import omp.shell.Session
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/** A resolution failure that should reach the user as a plain diagnostic. */
class PathException(message: String) : Exception(message)

/**
 * Turns a user-typed path into an absolute, symlink-free path. A mistake here makes every file
 * command report nonsense, so the steps are explicit and in this order: tilde, alias, absolutise,
 * lexically collapse, then resolve symlinks under a hop budget.
 */
object PathResolver {

    const val MAX_SYMLINK_HOPS = 20

    fun resolve(session: Session, raw: String): String {
        var p = expandTilde(session, raw)
        if (p.isEmpty()) throw PathException("No such file or directory")
        p = applyAliases(session, p)
        if (!p.startsWith("/")) p = session.cwd.trimEnd('/') + "/" + p
        p = normalize(p)
        return canonical(p)
    }

    /** `~` to `$HOME`, `~+` to the previous directory, and the shared-storage aliases. */
    fun expandTilde(session: Session, raw: String): String {
        if (raw == "~") return session.services.homeDir()
        if (raw.startsWith("~/")) return session.services.homeDir().trimEnd('/') + raw.substring(1)
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
     * Resolves symlinks component by component, so `..` is applied before each hop the way the
     * kernel does. A dangling link is followed to its (nonexistent) target, which then fails with
     * `No such file or directory` at the point of use rather than being silently ignored.
     */
    fun canonical(path: String): String {
        var current = path
        var hops = 0
        while (hops <= MAX_SYMLINK_HOPS) {
            val link = firstLink(current) ?: return current
            val linkPath = link.first
            val parent = linkPath.substringBeforeLast('/', "/").ifEmpty { "/" }
            val target = if (link.second.startsWith("/")) link.second else parent.trimEnd('/') + "/" + link.second
            current = normalize(target + current.substring(linkPath.length))
            hops++
        }
        throw PathException("Path too long")
    }

    /** @return the first symlink component as (path, target), or null. */
    private fun firstLink(path: String): Pair<String, String>? {
        var cur = ""
        for (part in path.split('/')) {
            if (part.isEmpty()) continue
            val next = if (cur.isEmpty()) "/$part" else "$cur/$part"
            try {
                val p = Paths.get(next)
                if (Files.isSymbolicLink(p)) return next to Files.readSymbolicLink(p).toString()
            } catch (e: Exception) {
                return null
            }
            cur = next
        }
        return null
    }

    /** The reverse substitution, for prompts and for paths the user will see again. */
    fun contract(session: Session, path: String): String {
        val home = session.services.homeDir().trimEnd('/')
        if (path == home) return "~"
        if (path.startsWith("$home/")) return "~" + path.substring(home.length)
        return path
    }

    private val STORAGE_ALIASES = listOf("/sdcard", "/mnt/sdcard", "/storage/emulated/0", "/storage/self/primary", "/sdcard0")
}
