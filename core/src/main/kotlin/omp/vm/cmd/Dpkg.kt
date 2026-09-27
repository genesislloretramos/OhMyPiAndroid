package omp.vm.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.FsException
import omp.vm.VmArch
import omp.vm.pkg.DpkgDatabase
import omp.vm.pkg.PackageIndex
import omp.vm.pkg.Stanza
import omp.vm.rootfs.Rootfs

/**
 * `dpkg`, over the database on disk.
 *
 * `-L` and `-S` are the two that matter, and both are real: the file list is
 * `/var/lib/dpkg/info/<pkg>.list`, which is where dpkg keeps it, so `dpkg -L` names files that
 * exist and `dpkg -S` resolves a path back to the package that owns it. `dpkg -i` says it cannot
 * open an archive it does not have, because parsing a real `.deb` needs `ar` and a tar reader and
 * this userland has neither.
 */
@CommandSpec(
    name = "dpkg",
    synopsis = "-l | -s PKG | -L PKG | -S FILE | -i FILE | -r PKG | -P PKG | --print-architecture",
    group = "vm",
    notes = "over /var/lib/dpkg; -i cannot unpack a .deb and says so rather than pretending to",
)
object Dpkg : FileCommand() {
    override val flagSpec = "lsLiPrcS"
    override val longOptions = mapOf(
        "list" to false,
        "status" to false,
        "files" to false,
        "search" to false,
        "print-architecture" to false,
        "purge" to false,
        "remove" to false,
    )

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val db = databaseOf(ctx) ?: return ExecContext.EXIT_GENERAL_ERROR
        if (options.containsKey("print-architecture")) {
            ctx.outLine(VmArch.of(ctx.services))
            return ExecContext.EXIT_OK
        }
        // Long options arrive as an 'L' in flags plus their name in options.
        val wants = buildList {
            if ('l' in flags || options.containsKey("list")) add("list")
            if ('s' in flags || options.containsKey("status")) add("status")
            if ('L' in flags || options.containsKey("files")) add("files")
            if ('S' in flags || options.containsKey("search")) add("search")
            if ('i' in flags) add("install")
            if ('r' in flags || options.containsKey("remove")) add("remove")
            if ('P' in flags || options.containsKey("purge")) add("purge")
        }
        val mode = wants.firstOrNull() ?: return usage(ctx)
        return when (mode) {
            "list" -> list(ctx, db)
            "status" -> status(ctx, db, operands)
            "files" -> files(ctx, db, operands)
            "search" -> search(ctx, db, operands)
            "install" -> install(ctx, operands)
            "remove", "purge" -> remove(ctx, operands, purge = mode == "purge")
            else -> usage(ctx)
        }
    }

    private fun usage(ctx: ExecContext): Int {
        ctx.errLine("dpkg: no operation selected. Try 'dpkg --help'.")
        return ExecContext.EXIT_USAGE
    }

    /** `dpkg -l`, in the real three-column layout. */
    private fun list(ctx: ExecContext, db: DpkgDatabase): Int {
        ctx.outLine("Desired=Unknown/Install/Remove/Purge/Hold")
        ctx.outLine("| Status=Not/Inst/Conf-files/Unpacked/halF-conf/Half-inst/trig-aWait/Trig-pend")
        ctx.outLine("||/ Name           Version      Architecture Description")
        ctx.outLine("+++-==============-============-============-=================================")
        for (stanza in db.stanzas()) {
            val name = stanza.get("Package") ?: continue
            val marker = when {
                stanza.installed() -> "ii "
                stanza.status().startsWith("deinstall") -> "rc "
                stanza.status().startsWith("purge") -> "un "
                else -> "in "
            }
            // dpkg's own column: three characters of state, a space, then the name.
            ctx.outLine(
                "$marker ${name.padEnd(15)} ${(stanza.get("Version") ?: "unknown").padEnd(13)} " +
                    "${(stanza.get("Architecture") ?: omp.vm.VmArch.DEFAULT).padEnd(13)} ${stanza.get("Description") ?: ""}"
            )
        }
        return ExecContext.EXIT_OK
    }

    private fun status(ctx: ExecContext, db: DpkgDatabase, operands: List<String>): Int {
        val name = operands.firstOrNull() ?: return ctx.fail("dpkg-query: no package specified")
        val stanza = db.status(name) ?: return notFound(ctx, name)
        for ((key, value) in stanza.fields) {
            for (line in value.split('\n')) ctx.outLine("$key: $line")
        }
        return ExecContext.EXIT_OK
    }

    /** `dpkg -L`: the file list, from `/var/lib/dpkg/info/<pkg>.list`. */
    private fun files(ctx: ExecContext, db: DpkgDatabase, operands: List<String>): Int {
        val name = operands.firstOrNull() ?: return ctx.fail("dpkg-query: no package specified")
        if (db.status(name) == null) return notFound(ctx, name)
        for (file in db.listOf(name)) ctx.outLine(file)
        return ExecContext.EXIT_OK
    }

    /** `dpkg -S`: the owner of a path, resolved through the same file lists. */
    private fun search(ctx: ExecContext, db: DpkgDatabase, operands: List<String>): Int {
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

    /**
     * `dpkg -i`. The honest answer: this userland cannot unpack a `.deb`, so it says what a dpkg
     * says when the file is not there at all, and it says what it cannot do when the file is.
     */
    private fun install(ctx: ExecContext, operands: List<String>): Int {
        val file = operands.firstOrNull() ?: return ctx.fail("dpkg: no archive specified")
        val path = resolvePath(ctx, file) ?: return ExecContext.EXIT_GENERAL_ERROR
        try {
            ctx.session.vfs.readBytes(path)
        } catch (e: FsException) {
            ctx.errLine("dpkg: cannot open archive '$file': ${omp.shell.exec.Errno.messageFor(e)}")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        ctx.errLine("dpkg: cannot unpack '$file': the omp userland installs from its own in-process index,")
        ctx.errLine("dpkg: and a .deb archive is not something it can read. Try 'apt install <package>'.")
        return ExecContext.EXIT_GENERAL_ERROR
    }

    private fun remove(ctx: ExecContext, operands: List<String>, purge: Boolean): Int {
        val kernel = requireKernel(ctx) ?: return ExecContext.EXIT_GENERAL_ERROR
        val name = operands.firstOrNull() ?: return ctx.fail("dpkg: no package specified")
        val pkg = PackageIndex.find(name) ?: return notFound(ctx, name)
        val stanza: Stanza = kernel.packages.status(name) ?: return notFound(ctx, name)
        if (!stanza.installed()) {
            ctx.errLine("dpkg: package $name is not installed, so not removed")
            ctx.errLine("dpkg: package $name has no installation status")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        kernel.packageOps().remove(pkg, purge) { ctx.outLine(it) }
        return ExecContext.EXIT_OK
    }

    private fun notFound(ctx: ExecContext, name: String): Int {
        ctx.errLine("dpkg-query: package '$name' is not installed and no information is available")
        return ExecContext.EXIT_NOT_FOUND
    }
}
