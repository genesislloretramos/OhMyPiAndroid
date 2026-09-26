package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.PathResolver
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@CommandSpec(
    name = "zip",
    synopsis = "[-r] archive.zip files...",
    group = "files",
    notes = "deflate only; an entry may not be absolute or contain '..', and entries already in the archive are kept",
)
object Zip : FileCommand() {

    override val flagSpec = "r"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        if (operands.size < 2) return ctx.fail("zip: missing files to add")
        val archiveRaw = operands.first()
        val archivePath = Cmds.resolve(ctx, archiveRaw) ?: return ExecContext.EXIT_GENERAL_ERROR
        val archive = File(archivePath)
        val recursive = 'r' in flags

        val entries = LinkedHashMap<String, File>()
        var status = ExecContext.EXIT_OK
        for (src in operands.drop(1)) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val shown = PathResolver.expandTilde(ctx.session, src)
            val path = Cmds.resolve(ctx, src) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val file = File(path)
            if (!file.exists()) {
                ctx.errLine("zip: $src: No such file or directory")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            if (file.isDirectory) {
                if (!recursive) {
                    ctx.errLine("zip: $src: Is a directory")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                }
                if (!collect(file, shown, entries, ctx)) status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val name = entryName(ctx, shown) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            entries[name] = file
        }
        if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
        if (entries.isEmpty()) {
            return if (status == ExecContext.EXIT_OK) ctx.fail("zip: nothing to add") else status
        }
        return write(ctx, archive, archiveRaw, entries, status)
    }

    /** @return false when a directory in the tree could not be read. */
    private fun collect(
        dir: File,
        display: String,
        out: MutableMap<String, File>,
        ctx: ExecContext,
    ): Boolean {
        val entries = Cmds.listDir(ctx, "zip", dir) ?: return false
        val name = entryName(ctx, display.trimEnd('/') + "/") ?: return false
        out[name] = dir
        for (entry in entries.sortedBy { it.name }) {
            if (ctx.cancelled.get()) return false
            val child = display.trimEnd('/') + "/" + entry.name
            if (entry.isDirectory) {
                if (!collect(entry, child, out, ctx)) return false
            } else {
                val entryName = entryName(ctx, child) ?: return false
                out[entryName] = entry
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
        archive: File,
        archiveRaw: String,
        entries: Map<String, File>,
        status: Int,
    ): Int {
        val parent = archive.parentFile ?: File(".")
        if (!parent.isDirectory) return Errno.report(ctx, "zip", archiveRaw, IOException("No such file or directory"))
        val temp = File(parent, archive.name + ".tmp")
        try {
            ZipOutputStream(FileOutputStream(temp)).use { out ->
                out.setLevel(Deflater.DEFAULT_COMPRESSION)
                if (archive.isFile) {
                    val copied = keepExisting(archive, out, entries.keys, ctx, archiveRaw)
                    if (!copied) {
                        temp.delete()
                        return ExecContext.EXIT_GENERAL_ERROR
                    }
                }
                for ((name, file) in entries) {
                    if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
                    out.putNextEntry(ZipEntry(name))
                    // A directory entry carries no bytes; its name is what ends in the separator.
                    if (!file.isDirectory) {
                        FileInputStream(file).use { input -> input.copyTo(out) }
                    }
                    out.closeEntry()
                    ctx.outLine("  adding: $name")
                }
            }
        } catch (e: IOException) {
            temp.delete()
            return Errno.report(ctx, "zip", archiveRaw, e)
        } catch (e: SecurityException) {
            temp.delete()
            return ctx.fail("zip: $archiveRaw: Permission denied")
        }
        return try {
            Files.move(temp.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
            status
        } catch (e: IOException) {
            // A rename across a mount point is not available; a plain copy of the archive is.
            try {
                Files.copy(temp.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
                temp.delete()
                status
            } catch (copyError: IOException) {
                temp.delete()
                Errno.report(ctx, "zip", archiveRaw, copyError)
            }
        }
    }

    /** Copies the entries the new run does not replace, so `zip` updates rather than truncates. */
    private fun keepExisting(
        archive: File,
        out: ZipOutputStream,
        replaced: kotlin.collections.Set<String>,
        ctx: ExecContext,
        archiveRaw: String,
    ): Boolean = try {
        ZipFile(archive).use { old ->
            val entries = old.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name in replaced) continue
                if (entry.isDirectory) {
                    out.putNextEntry(ZipEntry(entry.name))
                    out.closeEntry()
                    continue
                }
                out.putNextEntry(ZipEntry(entry.name))
                old.getInputStream(entry).use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        true
    } catch (e: IOException) {
        ctx.errLine("zip: $archiveRaw: ${Errno.messageFor(e)}")
        false
    }
}
