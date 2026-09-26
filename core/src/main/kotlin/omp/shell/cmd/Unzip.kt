package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

@CommandSpec(
    name = "unzip",
    synopsis = "[-l] [-d dir] archive.zip",
    group = "files",
    notes = "every entry name is checked before anything is written, and an escaping one is refused outright",
)
object Unzip : FileCommand() {

    override val flagSpec = "l"
    override val valueSpec = "d:"

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        if (operands.isEmpty()) return ctx.fail("unzip: missing archive")
        if (operands.size > 1) return ctx.fail("unzip: only one archive at a time")
        val archiveRaw = operands[0]
        val path = Cmds.resolve(ctx, archiveRaw) ?: return ExecContext.EXIT_GENERAL_ERROR
        val archive = File(path)
        if (!archive.isFile) {
            ctx.errLine("unzip: $archiveRaw: No such file or directory")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        val zip = try {
            ZipFile(archive)
        } catch (e: IOException) {
            return Errno.report(ctx, "unzip", archiveRaw, e)
        }
        zip.use {
            val entries = it.entries().toList()
            for (entry in entries) {
                if (fsEntryEscapes(entry.name)) {
                    ctx.errLine("unzip: ${entry.name}: refusing path traversal")
                    return ExecContext.EXIT_GENERAL_ERROR
                }
            }
            if ('l' in flags) return list(ctx, archiveRaw, entries)
            return extract(ctx, entries, it, options["d"])
        }
    }

    private fun list(ctx: ExecContext, archiveRaw: String, entries: List<ZipEntry>): Int {
        ctx.outLine("Archive:  $archiveRaw")
        ctx.outLine("  Length      Date    Time    Name")
        ctx.outLine("---------  ---------- -----   ----")
        var total = 0L
        var files = 0
        var folders = 0
        for (entry in entries) {
            if (entry.isDirectory) {
                folders++
                ctx.outLine("        0    ${stamp.format(Date(entry.time))}   ${entry.name}")
            } else {
                files++
                total += entry.size
                ctx.outLine(String.format(Locale.US, "%9d  %s   %s", entry.size, stamp.format(Date(entry.time)), entry.name))
            }
        }
        ctx.outLine("---------                     -------")
        val summary = StringBuilder()
        summary.append(String.format(Locale.US, "%9d                     ", total))
        val parts = ArrayList<String>()
        if (files > 0) parts.add("$files ${plural(files, "file", "files")}")
        if (folders > 0) parts.add("$folders ${plural(folders, "folder", "folders")}")
        summary.append(if (parts.isEmpty()) "0 files" else parts.joinToString(", "))
        ctx.outLine(summary.toString())
        return ExecContext.EXIT_OK
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many

    private fun extract(ctx: ExecContext, entries: List<ZipEntry>, zip: ZipFile, destRaw: String?): Int {
        val root: File = if (destRaw == null) {
            File(ctx.session.cwd)
        } else {
            val path = Cmds.resolve(ctx, destRaw) ?: return ExecContext.EXIT_GENERAL_ERROR
            File(path)
        }
        if (!root.isDirectory && !root.mkdirs()) {
            ctx.errLine("unzip: $destRaw: could not create the destination directory")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        var status = ExecContext.EXIT_OK
        for (entry in entries) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val out = File(root, entry.name)
            if (entry.isDirectory) {
                if (!out.isDirectory && !out.mkdirs()) {
                    ctx.errLine("unzip: ${entry.name}: could not create the directory")
                    status = ExecContext.EXIT_GENERAL_ERROR
                }
                continue
            }
            out.parentFile?.let { parent ->
                if (!parent.isDirectory && !parent.mkdirs()) {
                    ctx.errLine("unzip: ${entry.name}: could not create the directory")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    continue
                }
            }
            try {
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
                ctx.outLine("  inflating: ${entry.name}")
            } catch (e: IOException) {
                ctx.errLine("unzip: ${entry.name}: ${Errno.messageFor(e)}")
                status = ExecContext.EXIT_GENERAL_ERROR
            } catch (e: SecurityException) {
                ctx.errLine("unzip: ${entry.name}: Permission denied")
                status = ExecContext.EXIT_GENERAL_ERROR
            }
        }
        return status
    }
}
