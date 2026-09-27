package omp.vm.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.vm.pkg.Stanza
import omp.vm.rootfs.Rootfs

/**
 * `dpkg-query`, the read-only half of [Dpkg]: `-W`, `-L`, `-S`, `-s` and `--showformat`.
 *
 * It never writes, which is the whole difference from `dpkg`, and every value it prints is read out
 * of `/var/lib/dpkg` rather than out of the package index — so a package the user removed is gone
 * from here even though the index still knows the name.
 */
@CommandSpec(
    name = "dpkg-query",
    synopsis = "-W [PKG] | -L PKG | -S FILE | -s PKG | --showformat=FORMAT",
    group = "vm",
    notes = "read-only over /var/lib/dpkg; -W --showformat understands the Package, Version and Status fields",
)
object DpkgQuery : FileCommand() {

    override val flagSpec = "WLsS"
    override val longOptions = mapOf(
        "show" to false,
        "showformat" to true,
        "list" to false,
        "search" to false,
        "files" to false,
    )

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val db = databaseOf(ctx) ?: return ExecContext.EXIT_GENERAL_ERROR
        val showFormat = options["showformat"]
        if ('W' in flags) return wanted(ctx, db, operands, showFormat)
        if ('L' in flags || options.containsKey("files")) return files(ctx, db, operands)
        if ('S' in flags || options.containsKey("search")) return search(ctx, db, operands)
        if ('s' in flags || options.containsKey("show")) return show(ctx, db, operands.firstOrNull())
        return ctx.fail("dpkg-query: no operation selected. Try 'dpkg-query --help'.")
    }

    private fun wanted(ctx: ExecContext, db: omp.vm.pkg.DpkgDatabase, operands: List<String>, showFormat: String?): Int {
        val stanzas = if (operands.isEmpty()) {
            db.stanzas()
        } else {
            operands.mapNotNull { db.status(it) }
        }
        if (stanzas.isEmpty()) {
            ctx.errLine("dpkg-query: no packages found matching ${operands.joinToString(" ")}")
            return ExecContext.EXIT_NOT_FOUND
        }
        if (showFormat == null) {
            for (stanza in stanzas) {
                ctx.outLine("${stanza.get("Package")}\t${stanza.get("Version")}")
            }
            return ExecContext.EXIT_OK
        }
        for (stanza in stanzas) ctx.outLine(format(showFormat, stanza))
        return ExecContext.EXIT_OK
    }

    /**
     * The fields a script actually asks for, and nothing else: a showformat language is a rabbit
     * hole and a half-implemented one prints empty fields that look like real answers.
     *
     * The `${...}` wrapper is optional here, so `--showformat=Version` and `--showformat=${Version}`
     * are the same thing. That is deliberate: a shell may expand `${Version}` before the command
     * ever sees it, and a showformat that needs a dollar sign is a showformat that cannot be used
     * without quoting.
     */
    private fun format(showFormat: String, stanza: Stanza): String {
        val trimmed = showFormat.trim()
        val field = trimmed.removePrefix("${").removeSuffix("}")
        val value = when (field) {
            "Package" -> stanza.get("Package")
            "Version" -> stanza.get("Version")
            "Status" -> stanza.get("Status")
            "binary:Package", "Source" -> stanza.get("Package")
            "Architecture" -> stanza.get("Architecture")
            "Section" -> stanza.get("Section")
            else -> null
        }
        return value ?: ""
    }

    private fun files(ctx: ExecContext, db: omp.vm.pkg.DpkgDatabase, operands: List<String>): Int {
        val name = operands.firstOrNull() ?: return ctx.fail("dpkg-query: no package specified")
        if (db.status(name) == null) {
            ctx.errLine("dpkg-query: package '$name' is not installed and no information is available")
            return ExecContext.EXIT_NOT_FOUND
        }
        for (file in db.listOf(name)) ctx.outLine(file)
        return ExecContext.EXIT_OK
    }

    private fun search(ctx: ExecContext, db: omp.vm.pkg.DpkgDatabase, operands: List<String>): Int {
        var status = ExecContext.EXIT_OK
        for (op in operands) {
            val path = resolvePath(ctx, op) ?: return ExecContext.EXIT_GENERAL_ERROR
            val owner = db.ownerOf(path)
            if (owner == null) {
                ctx.errLine("dpkg-query: no path found matching pattern $op")
                status = ExecContext.EXIT_NOT_FOUND
            } else {
                ctx.outLine("$owner: $path")
            }
        }
        return status
    }

    private fun show(ctx: ExecContext, db: omp.vm.pkg.DpkgDatabase, name: String?): Int {
        val wanted = name ?: return ctx.fail("dpkg-query: no package specified")
        val stanza = db.status(wanted) ?: run {
            // dpkg-query exits 1 for a package it has no record of, not 127: the package is known to
            // the index, the *database* simply has nothing about it.
            ctx.errLine("dpkg-query: package '$wanted' is not installed and no information is available")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        for ((key, value) in stanza.fields) {
            for (line in value.split('\n')) ctx.outLine("$key: $line")
        }
        ctx.outLine("Description: ${stanza.get("Description") ?: ""} (${omp.vm.VmArch.of(ctx.services)})")
        return ExecContext.EXIT_OK
    }
}
