package omp.shell.cmd

import omp.shell.exec.CommandTable
import omp.shell.exec.ExecContext
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.VNodeType
import omp.shell.fs.VStat
import omp.shell.fs.Vfs
import omp.shell.fs.resolveSymlinks
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The file-manipulating half of the command surface, and the tree walks that more than one of these
 * commands needs. Registration is explicit because `help` reads the same table, so the two cannot
 * drift apart.
 */
object FileCommands {

    fun register(table: CommandTable) {
        table.register(
            Ls, Cd, Pwd, Mkdir, Rmdir, Rm, Cp, Mv, Ln, Touch, Cat, Head, Tail, Less,
            Stat, FileCmd, Readlink, Realpath, Du, Find, Tree, Sync, Truncate,
            Basename, Dirname, Zip, Unzip,
        )
        table.registerAlias("more", "less", "files", "same paginator, GNU's name for it")
    }
}

// The helpers below are package-private and prefixed `fs` so they cannot collide with anything else
// in `omp.shell.cmd`. All of them speak the [Vfs] rather than the disk: a path a command resolves is
// a path in its own namespace, and a `java.io.File` behind it would quietly answer for the device.

/** [dir] with [name] appended, the way a directory listing spells a child. */
internal fun fsChild(dir: String, name: String): String = dir.trimEnd('/') + "/" + name

/** The last component of a path, which is what `-name` matches and what a listing prints. */
internal fun fsName(path: String): String = path.substringAfterLast('/')

/** The [VStat] of [path], or null when it is absent or this app may not look at it. */
internal fun fsStatOrNull(vfs: Vfs, path: String): VStat? = try {
    vfs.stat(path)
} catch (e: FsException) {
    null
}

/**
 * A [VStat] is taken without following a link, which is what almost every question a file command
 * asks needs; this is where a link has to be followed instead, because `File.isDirectory` and
 * `File.isFile` always follow one. A link that dangles is neither a file nor a directory, which is
 * what `java.io` answers for it too.
 */
private fun followed(vfs: Vfs, path: String, stat: VStat): VStat? {
    if (stat.type != VNodeType.SYMLINK) return stat
    val resolved = try {
        resolveSymlinks(vfs, path)
    } catch (e: FsException) {
        return null
    }
    return fsStatOrNull(vfs, resolved)
}

/** What `File.isDirectory` answers: a link to a directory is a directory. */
internal fun fsIsDirFollowing(vfs: Vfs, path: String, stat: VStat): Boolean =
    followed(vfs, path, stat)?.type == VNodeType.DIRECTORY

/** What `File.isFile` answers: a link to a regular file is a regular file. */
internal fun fsIsFileFollowing(vfs: Vfs, path: String, stat: VStat): Boolean =
    followed(vfs, path, stat)?.type == VNodeType.FILE

/** What `File.isFile && File.canExecute` answers, which is what a `$PATH` search asks. */
internal fun fsIsExecutableFile(vfs: Vfs, path: String): Boolean {
    val stat = fsStatOrNull(vfs, path) ?: return false
    return stat.executable && fsIsFileFollowing(vfs, path, stat)
}

internal fun fsIsLink(vfs: Vfs, path: String): Boolean =
    fsStatOrNull(vfs, path)?.type == VNodeType.SYMLINK

internal fun fsLinkTarget(vfs: Vfs, path: String): String? = try {
    vfs.readLink(path)
} catch (e: FsException) {
    null
}

internal fun fsExists(vfs: Vfs, path: String): Boolean = fsStatOrNull(vfs, path) != null

/** Unlinks one file, reporting in the caller's command name. A directory is [Vfs.delete]'s refusal. */
internal fun fsDeleteFile(ctx: ExecContext, op: String, raw: String, vfs: Vfs, path: String): Boolean = try {
    vfs.delete(path)
    true
} catch (e: FsException) {
    ctx.errLine("$op: $raw: ${e.errno.text}")
    false
} catch (e: SecurityException) {
    ctx.errLine("$op: $raw: Permission denied")
    false
}

/** Removes one empty directory, which [Vfs.delete] refuses to do and `rmdir` has to be able to. */
internal fun fsRmdir(ctx: ExecContext, op: String, raw: String, vfs: Vfs, path: String): Boolean = try {
    vfs.rmdir(path)
    true
} catch (e: FsException) {
    ctx.errLine("$op: $raw: ${e.errno.text}")
    false
} catch (e: SecurityException) {
    ctx.errLine("$op: $raw: Permission denied")
    false
}

/**
 * `mkdir -p`, one level at a time, which is the only shape [Vfs.mkdir] has. An existing directory is
 * the success `mkdir -p` promises; a *file* in the way is the failure it has always been.
 *
 * @return false when a level could not be created, with nothing reported — the caller has a command
 *   name and a phrasing of its own for that case.
 */
internal fun fsMakeDirs(vfs: Vfs, path: String): Boolean {
    val built = StringBuilder()
    for (part in path.split('/')) {
        if (part.isEmpty()) continue
        built.append('/').append(part)
        val level = built.toString()
        try {
            vfs.mkdir(level)
        } catch (e: FsException) {
            if (e.errno != FsErrno.FILE_EXISTS) return false
            if (fsStatOrNull(vfs, level)?.type != VNodeType.DIRECTORY) return false
        }
    }
    return true
}

/**
 * The bytes of one file, written over another. A write failure is an [FsException] the caller
 * reports in its own voice, which is how `cp` and `mv` have always worded a destination they cannot
 * write to.
 */
internal fun fsCopyOne(vfs: Vfs, from: String, to: String) {
    vfs.writeBytes(to, vfs.readBytes(from))
}

/** One node of a walk: where it is, where it is going, and whether this visit finally removes it. */
private class Node(val path: String, val dest: String, val closing: Boolean)

/**
 * Recursive copy that keeps a symlink as a symlink. Diagnostics carry the caller's command name, so
 * `cp` and `mv` do not have to each re-implement the walk.
 *
 * The seam has no visitor, so the walk is an explicit stack: pre-order, children in listing order,
 * the destination directory built before its contents. A directory this app may not list is
 * reported and the walk carries on, the way a failed read always did; a destination it cannot write
 * to is thrown instead, because that is a failure of the copy rather than of one branch of it.
 */
internal fun fsCopyTree(
    ctx: ExecContext,
    op: String,
    vfs: Vfs,
    from: String,
    to: String,
    cancelled: AtomicBoolean,
): Boolean {
    var ok = true
    val pending = ArrayDeque<Node>()
    pending.addLast(Node(from, to, false))
    while (pending.isNotEmpty()) {
        if (cancelled.get()) return ok
        val node = pending.removeLast()
        val entries = try {
            vfs.readDir(node.path)
        } catch (e: FsException) {
            if (e.errno != FsErrno.NOT_A_DIRECTORY) {
                ok = false
                ctx.errLine("$op: ${node.path}: ${e.errno.text}")
                continue
            }
            copyEntry(vfs, node.path, node.dest)
            continue
        }
        if (!fsMakeDirs(vfs, node.dest)) throw FsException(FsErrno.NO_SUCH_FILE, node.dest)
        // Reversed, so the stack hands them back in the order the listing gave them.
        for (i in entries.indices.reversed()) {
            val name = entries[i].name
            pending.addLast(Node(fsChild(node.path, name), fsChild(node.dest, name), false))
        }
    }
    return ok
}

/** One entry of a copied tree: a link is recreated as a link, anything else is copied byte for byte. */
private fun copyEntry(vfs: Vfs, from: String, to: String) {
    if (fsIsLink(vfs, from)) {
        val target = vfs.readLink(from)
        // `symlink` is O_CREAT|O_EXCL and a copy never follows a link, so whatever is in the way goes
        // first — and only the name at `to`, never the file it happens to point at.
        try {
            vfs.delete(to)
        } catch (e: FsException) {
            if (e.errno != FsErrno.NO_SUCH_FILE) throw e
        }
        vfs.symlink(target, to)
        return
    }
    fsCopyOne(vfs, from, to)
}

/**
 * Deletes a tree bottom-up, and an unreadable entry is reported while the walk carries on. A node is
 * a directory because a [VNodeType.DIRECTORY] stat says so and not because a listing worked, so a
 * symlink to a directory is unlinked the way `rm -r link` has always unlinked it: the link, and
 * nothing it points at.
 */
internal fun fsDeleteTree(ctx: ExecContext, op: String, vfs: Vfs, target: String, cancelled: AtomicBoolean): Boolean {
    var ok = true
    val pending = ArrayDeque<Node>()
    pending.addLast(Node(target, target, false))
    while (pending.isNotEmpty()) {
        if (cancelled.get()) return ok
        val node = pending.removeLast()
        if (node.closing) {
            if (!fsRmdir(ctx, op, node.path, vfs, node.path)) ok = false
            continue
        }
        val stat = try {
            vfs.stat(node.path)
        } catch (e: FsException) {
            ok = false
            ctx.errLine("$op: ${node.path}: ${e.errno.text}")
            continue
        }
        if (stat.type != VNodeType.DIRECTORY) {
            if (!fsDeleteFile(ctx, op, node.path, vfs, node.path)) ok = false
            continue
        }
        val entries = try {
            vfs.readDir(node.path)
        } catch (e: FsException) {
            ok = false
            ctx.errLine("$op: ${node.path}: ${e.errno.text}")
            continue
        }
        // Pushed first, so it is popped last: a directory goes after everything inside it.
        pending.addLast(Node(node.path, node.path, true))
        for (i in entries.indices.reversed()) {
            pending.addLast(Node(fsChild(node.path, entries[i].name), node.path, false))
        }
    }
    return ok
}

/** POSIX: case-sensitive comparison by code point, which is byte order in every `LC_ALL=C` sort. */
internal fun fsCompareC(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a.codePointAt(i)
        val cb = b.codePointAt(j)
        if (ca != cb) return ca.compareTo(cb)
        i += Character.charCount(ca)
        j += Character.charCount(cb)
    }
    return (a.length - i) - (b.length - j)
}

/** Columns a name occupies, which is what a multi-column listing has to line up on. */
internal fun fsWidth(text: String): Int = text.codePointCount(0, text.length)


/**
 * An archive entry that is absolute, or that walks out of the destination with `..`, is a path
 * traversal waiting to happen. Both `zip` and `unzip` refuse one before anything is written.
 */
internal fun fsEntryEscapes(name: String): Boolean = when {
    name.isEmpty() -> true
    name.startsWith("/") || name.startsWith("\\") -> true
    name.any { it == '\u0000' } -> true
    else -> name.split('/', '\\').any { it == ".." }
}
