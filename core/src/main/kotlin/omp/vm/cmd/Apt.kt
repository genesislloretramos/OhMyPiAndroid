package omp.vm.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.vm.VmArch
import omp.vm.pkg.DpkgDatabase
import omp.vm.pkg.PackageIndex
import omp.vm.pkg.VmPackage
import omp.vm.rootfs.Rootfs

/**
 * `apt` over a local, in-process index.
 *
 * Every answer here comes out of the database on disk, so `apt list`, `dpkg -l` and the status file
 * cannot disagree. Two rules it never breaks: **nothing is fetched** and **nothing is printed as if
 * it had been**. `apt update` says the index is compiled into the app; `apt install` says where the
 * files come from; a package whose programs this userland does not have says how many and which,
 * rather than writing a file that would do nothing.
 */
@CommandSpec(
    name = "apt",
    synopsis = "update | install PKG... | remove PKG... | purge PKG... | list | show PKG | policy PKG | upgrade",
    group = "vm",
    notes = "a local in-process index; no bytes are downloaded and no Get:/Fetched line is ever printed",
)
object Apt : FileCommand() {
    override val longOptions = mapOf("installed" to false, "upgradable" to false)

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val kernel = requireKernel(ctx) ?: return ExecContext.EXIT_GENERAL_ERROR
        val db = kernel.packages
        val verb = operands.firstOrNull() ?: return usage(ctx)
        val names = operands.drop(1)
        // `apt list` with no operands is every package the index knows, not only the installed
        // ones: the whole point of the word "list" is that it can be a filter.
        if (verb == "list" && names.isEmpty() && !options.containsKey("installed")) return listAll(ctx, db)
        return when (verb) {
            "update" -> update(ctx)
            "upgrade" -> upgrade(ctx, db)
            "install" -> install(ctx, kernel, names)
            "remove" -> remove(ctx, kernel, names, purge = false)
            "purge" -> remove(ctx, kernel, names, purge = true)
            "list" -> listInstalled(ctx, db)
            "show" -> show(ctx, db, names)
            "policy" -> policy(ctx, db, names)
            else -> {
                ctx.errLine("apt: invalid operation '${ctx.args.firstOrNull() ?: verb}'")
                ExecContext.EXIT_USAGE
            }
        }
    }

    private fun update(ctx: ExecContext): Int {
        ctx.outLine("Reading package lists... ${PackageIndex.names().size} packages in the local index, 0 upgraded, 0 newly available.")
        ctx.outLine("The index is compiled into the app: nothing was downloaded and there is nothing to fetch.")
        return ExecContext.EXIT_OK
    }

    /** The truth about an upgrade, which is that the userland *is* the app's own code. */
    private fun upgrade(ctx: ExecContext, db: DpkgDatabase): Int {
        ctx.outLine("Reading package lists... Done")
        ctx.outLine("0 upgraded, 0 newly installed, 0 to remove and 0 not upgraded.")
        ctx.outLine("The userland is this app's own code: there is no newer version to upgrade into.")
        return ExecContext.EXIT_OK
    }

    private fun install(ctx: ExecContext, kernel: omp.vm.VmKernel, names: List<String>): Int {
        if (names.isEmpty()) return ctx.fail("apt: no packages specified")
        val ops = kernel.packageOps()
        for (name in names) {
            val pkg = PackageIndex.find(name) ?: return noSuchPackage(ctx, name)
            if (ops.install(pkg) { ctx.outLine(it) } == null) {
                ctx.outLine("${pkg.name} is already the newest version (${pkg.version}).")
            }
        }
        return ExecContext.EXIT_OK
    }

    private fun remove(ctx: ExecContext, kernel: omp.vm.VmKernel, names: List<String>, purge: Boolean): Int {
        if (names.isEmpty()) return ctx.fail("apt: no packages specified")
        val ops = kernel.packageOps()
        for (name in names) {
            val pkg = PackageIndex.find(name) ?: return noSuchPackage(ctx, name)
            ops.remove(pkg, purge) { ctx.outLine(it) }
        }
        return ExecContext.EXIT_OK
    }

    /** Bare `apt`, which is what real apt answers with: the operations, not a package listing. */
    private fun usage(ctx: ExecContext): Int {
        ctx.outLine("apt 2.4.13 (${VmArch.of(ctx.services)})")
        ctx.outLine("Usage: apt [options] command")
        ctx.outLine("       apt [options] [command [package ...]]")
        ctx.outLine("       apt [options] install|remove [pkg...]")
        ctx.outLine("Commands:")
        for (line in listOf("update - Retrieve new lists of packages", "upgrade - Perform an upgrade", "install - Install new packages", "remove - Remove packages", "purge - Remove packages and config files", "list - List packages", "show - Show package details", "policy - Show the source of a package")) {
            ctx.outLine("  $line")
        }
        ctx.outLine("The index is compiled into the app: nothing here is downloaded.")
        return ExecContext.EXIT_OK
    }

    /**
     * `apt list` with no operands: every package in the index, the installed ones marked, which is
     * what the command is for. `apt list --installed` is the subset, in [listInstalled].
     */
    private fun listAll(ctx: ExecContext, db: DpkgDatabase): Int {
        ctx.outLine("Listing... Done")
        val installed = db.stanzas().filter { it.installed() }.mapNotNull { it.get("Package") }.toSet()
        for (pkg in PackageIndex.all) {
            val arch = VmArch.of(ctx.services)
            val mark = if (pkg.name in installed) "[installed]" else "[installable]"
            ctx.outLine("${pkg.name}/${Rootfs.CODENAME},now ${pkg.version} $arch $mark")
        }
        return ExecContext.EXIT_OK
    }

    /** `apt list --installed`, in the shape apt uses: name/suite,now version arch [installed]. */
    private fun listInstalled(ctx: ExecContext, db: DpkgDatabase): Int {
        ctx.outLine("Listing... Done")
        for (stanza in db.stanzas().filter { it.installed() }) {
            val name = stanza.get("Package") ?: continue
            val version = stanza.get("Version") ?: "unknown"
            val arch = stanza.get("Architecture") ?: VmArch.of(ctx.services)
            val auto = if (PackageIndex.find(name)?.base == true) ",automatic" else ""
            ctx.outLine("$name/${Rootfs.CODENAME},now $version $arch [installed$auto]")
        }
        return ExecContext.EXIT_OK
    }

    private fun show(ctx: ExecContext, db: DpkgDatabase, names: List<String>): Int {
        val wanted = names.ifEmpty { PackageIndex.names() }
        for (name in wanted) {
            val pkg = PackageIndex.find(name) ?: continue
            ctx.outLine("Package: ${pkg.name}")
            ctx.outLine("Version: ${pkg.version}")
            ctx.outLine("Installed-Size: ${pkg.installedSizeKb}")
            ctx.outLine("Maintainer: ${pkg.maintainer}")
            ctx.outLine("Architecture: ${VmArch.of(ctx.services)}")
            if (pkg.depends.isNotEmpty()) ctx.outLine("Depends: ${pkg.depends.joinToString(", ")}")
            ctx.outLine("Section: ${pkg.section}")
            ctx.outLine("Priority: ${pkg.priority}")
            ctx.outLine("Description: ${pkg.description}")
            ctx.outLine("This package is part of the in-process index; no archive was fetched for it.")
            ctx.outLine()
        }
        return ExecContext.EXIT_OK
    }

    /** `apt-cache policy`, with the version table naming the local index instead of a URL. */
    private fun policy(ctx: ExecContext, db: DpkgDatabase, names: List<String>): Int {
        val wanted = names.ifEmpty { PackageIndex.names() }
        for (name in wanted) {
            val pkg: VmPackage = PackageIndex.find(name) ?: continue
            val installed = db.status(name)?.takeIf { it.installed() }?.get("Version")
            ctx.outLine("${pkg.name}:")
            ctx.outLine("  Installed: ${installed ?: "(none)"}")
            ctx.outLine("  Candidate: ${pkg.version}")
            ctx.outLine("  Version table:")
            ctx.outLine("     ${pkg.version} 500")
            ctx.outLine("        500 omp local index (in-process, no bytes fetched)")
        }
        return ExecContext.EXIT_OK
    }
}
