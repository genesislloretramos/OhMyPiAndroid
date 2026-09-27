package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.PathResolver
import omp.shell.fs.VNodeType
import omp.shell.fs.VStat
import omp.shell.fs.Vfs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@CommandSpec(
    name = "zip",
    synopsis = "[-r] archive.zip files...",
    group = "files",
    notes = "deflate only; an entry may not be absolute or contain '..', and entries already in the archive are kept",
)
object Zip : FileCommand() {

    override val flagSpec = "r"

    /** Where an entry's bytes come from, and whether the entry is a directory that carries none. */
    private class Source(val path: String, val isDir: Boolean)

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        if (operands.size < 2) return ctx.fail("zip: missing files to add")
        val archiveRaw = operands.first()
        val archivePath = Cmds.resolve(ctx, archiveRaw) ?: return ExecContext.EXIT_GENERAL_ERROR
        val vfs = ctx.session.vfs
        val recursive = 'r' in flags

        val entries = LinkedHashMap<String, Source>()
        var status = ExecContext.EXIT_OK
        for (src in operands.drop(1)) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val shown = PathResolver.expandTilde(ctx.session, src)
            val path = Cmds.resolve(ctx, src) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val stat = Cmds.statFollowedOrNull(vfs, path)
            if (stat == null) {
                ctx.errLine("zip: $src: No such file or directory")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            if (stat.type == VNodeType.DIRECTORY) {
                if (!recursive) {
                    ctx.errLine("zip: $src: Is a directory")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                }
                if (!collect(vfs, path, stat, shown, entries, ctx)) status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val name = entryName(ctx, shown) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            entries[name] = Source(path, false)
        }
        if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
        if (entries.isEmpty()) {
            return if (status == ExecContext.EXIT_OK) ctx.fail("zip: nothing to add") else status
        }
        return write(ctx, vfs, archivePath, archiveRaw, entries, status)
    }

    /**
     * The tree under [path], in listing order, with the names the user typed rather than the ones
     * the resolver produced. A link to a directory is descended into, which is what this has always
     * done; the bytes themselves are only read once the whole set of names is known.
     *
     * @return false when a directory in the tree could not be read.
     */
    private fun collect(
        vfs: Vfs,
        path: String,
        stat: VStat,
        display: String,
        out: MutableMap<String, Source>,
        ctx: ExecContext,
    ): Boolean {
        val entries = Cmds.listDir(ctx, "zip", vfs, path) ?: return false
        val name = entryName(ctx, display.trimEnd('/') + "/") ?: return false
        out[name] = Source(path, true)
        for (entry in entries.sortedBy { it.name }) {
            if (ctx.cancelled.get()) return false
            val child = display.trimEnd('/') + "/" + entry.name
            val childPath = fsChild(path, entry.name)
            val isDir = if (entry.stat.type == VNodeType.SYMLINK) fsIsDirFollowing(vfs, childPath, entry.stat)
            else entry.stat.type == VNodeType.DIRECTORY
            if (isDir) {
                if (!collect(vfs, childPath, entry.stat, child, out, ctx)) return false
            } else {
                val entryName = entryName(ctx, child) ?: return false
                out[entryName] = Source(childPath, false)
            }
        }
        return true
    }

    private fun entryName(ctx: ExecContext, display: String): String? {
        var name = display
        while (name.startsWith("./")) name = name.substring(2)
        if (fsEntryEscapes(name)) {
            ctx.errLine("zip: $display: refusing path traversal")
            return null
        }
        return name
    }

    private fun write(
        ctx: ExecContext,
        vfs: Vfs,
        archive: String,
        archiveRaw: String,
        entries: Map<String, Source>,
        status: Int,
    ): Int {
        val parent = archive.substringBeforeLast('/', "/")
        if (fsStatOrNull(vfs, parent)?.type != VNodeType.DIRECTORY) {
            return Errno.report(ctx, "zip", archiveRaw, FsException(FsErrno.NO_SUCH_FILE, archiveRaw))
        }
        // The archive is built in memory and written once: the seam is the only thing that may put
        // bytes where the user asked for them, and a half-written archive is worse than none.
        val buffer = ByteArrayOutputStream()
        try {
            ZipOutputStream(buffer).use { out ->
                out.setLevel(Deflater.DEFAULT_COMPRESSION)
                if (fsStatOrNull(vfs, archive)?.type == VNodeType.FILE) {
                    if (!keepExisting(vfs, archive, out, entries.keys, ctx, archiveRaw)) {
                        return ExecContext.EXIT_GENERAL_ERROR
                    }
                }
                for ((name, source) in entries) {
                    if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
                    out.putNextEntry(ZipEntry(name))
                    // A directory entry carries no bytes; its name is what ends in the separator.
                    if (!source.isDir) out.write(vfs.readBytes(source.path))
                    out.closeEntry()
                    ctx.outLine("  adding: $name")
                }
            }
        } catch (e: FsException) {
            return Errno.report(ctx, "zip", archiveRaw, e)
        } catch (e: SecurityException) {
            return ctx.fail("zip: $archiveRaw: Permission denied")
        } catch (e: IOException) {
            return Errno.report(ctx, "zip", archiveRaw, e)
        }
        return try {
            vfs.writeBytes(archive, buffer.toByteArray())
            status
        } catch (e: FsException) {
            Errno.report(ctx, "zip", archiveRaw, e)
        } catch (e: SecurityException) {
            ctx.fail("zip: $archiveRaw: Permission denied")
        }
    }

    /** Copies the entries the new run does not replace, so `zip` updates rather than truncates. */
    private fun keepExisting(
        vfs: Vfs,
        archive: String,
        out: ZipOutputStream,
        replaced: kotlin.collections.Set<String>,
        ctx: ExecContext,
        archiveRaw: String,
    ): Boolean = try {
        ZipInputStream(ByteArrayInputStream(vfs.readBytes(archive))).use { old ->
            var entry: ZipEntry? = old.nextEntry
            while (entry != null) {
                if (entry.name !in replaced) {
                    out.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) old.copyTo(out)
                    out.closeEntry()
                }
                entry = old.nextEntry
            }
        }
        true
    } catch (e: IOException) {
        ctx.errLine("zip: $archiveRaw: ${Errno.messageFor(e)}")
        false
    }
}
