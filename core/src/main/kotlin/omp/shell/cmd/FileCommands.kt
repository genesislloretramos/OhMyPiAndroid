package omp.shell.cmd

import omp.shell.exec.CommandTable
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
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
// in `omp.shell.cmd`.

/** `java.io` follows links; almost every question a file command asks needs the unfollowed answer. */
internal fun fsIsLink(file: File): Boolean = try {
    Files.isSymbolicLink(file.toPath())
} catch (e: Exception) {
    false
}

internal fun fsLinkTarget(file: File): String? = try {
    Files.readSymbolicLink(file.toPath()).toString()
} catch (e: Exception) {
    null
}

internal fun fsExists(file: File): Boolean = try {
    Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
} catch (e: Exception) {
    false
}

internal fun fsDeleteFile(ctx: ExecContext, op: String, raw: String, file: File): Boolean = try {
    Files.deleteIfExists(file.toPath())
    true
} catch (e: IOException) {
    ctx.errLine("$op: $raw: ${Errno.messageFor(e)}")
    false
} catch (e: SecurityException) {
    ctx.errLine("$op: $raw: Permission denied")
    false
}

/** Attributes are copied when the filesystem supports it, and the copy alone is not an error. */
internal fun fsCopyOne(from: Path, to: Path) {
    try {
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
    } catch (e: UnsupportedOperationException) {
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: IOException) {
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Recursive copy that keeps a symlink as a symlink. Diagnostics carry the caller's command name,
 * so `cp` and `mv` do not have to each re-implement the walk.
 */
internal fun fsCopyTree(
    ctx: ExecContext,
    op: String,
    from: File,
    to: File,
    cancelled: AtomicBoolean,
): Boolean {
    val root = from.toPath()
    val dest = to.toPath()
    var ok = true
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (cancelled.get()) return FileVisitResult.TERMINATE
            Files.createDirectories(dest.resolve(root.relativize(dir)))
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (cancelled.get()) return FileVisitResult.TERMINATE
            val out = dest.resolve(root.relativize(file))
            out.parent?.let { Files.createDirectories(it) }
            if (attrs.isSymbolicLink) {
                val link = Files.readSymbolicLink(file)
                Files.deleteIfExists(out)
                Files.createSymbolicLink(out, link)
            } else {
                fsCopyOne(file, out)
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            ok = false
            ctx.errLine("$op: ${file.toString()}: ${Errno.messageFor(exc)}")
            return if (cancelled.get()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
        }
    })
    return ok
}

/** Deletes a tree bottom-up; an unreadable entry is reported and the walk carries on. */
internal fun fsDeleteTree(ctx: ExecContext, op: String, target: File, cancelled: AtomicBoolean): Boolean {
    var ok = true
    try {
        Files.walkFileTree(target.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (cancelled.get()) return FileVisitResult.TERMINATE
                if (!fsDeleteFile(ctx, op, file.toString(), file.toFile())) ok = false
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) {
                    ok = false
                    ctx.errLine("$op: ${dir.toString()}: ${Errno.messageFor(exc)}")
                } else if (!fsDeleteFile(ctx, op, dir.toString(), dir.toFile())) {
                    ok = false
                }
                return if (cancelled.get()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                ok = false
                ctx.errLine("$op: ${file.toString()}: ${Errno.messageFor(exc)}")
                return if (cancelled.get()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }
        })
    } catch (e: IOException) {
        ctx.errLine("$op: ${target.path}: ${Errno.messageFor(e)}")
        ok = false
    } catch (e: SecurityException) {
        ctx.errLine("$op: ${target.path}: Permission denied")
        ok = false
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
    name.contains('\u0000') -> true
    else -> name.split('/', '\\').any { it == ".." }
}