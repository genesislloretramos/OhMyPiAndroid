package omp.vm.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.vm.VmKernel
import omp.vm.pkg.PackageIndex

/**
 * `apt-get`, the script-friendly half of [Apt]: the same verbs, one package per invocation, no
 * progress bars and no "Do you want to continue? [Y/n]" — because there is nothing to confirm and
 * nothing to download, and a prompt a script cannot answer is a prompt a script hangs on.
 */
@CommandSpec(
    name = "apt-get",
    synopsis = "update | install PKG... | remove PKG... | purge PKG... | upgrade",
    group = "vm",
    notes = "the scriptable face of apt over the same local index; nothing is fetched",
)
object AptGet : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val kernel = requireKernel(ctx) ?: return ExecContext.EXIT_GENERAL_ERROR
        val verb = operands.firstOrNull() ?: return ctx.fail("apt-get: no operation")
        val names = operands.drop(1)
        val ops = kernel.packageOps()
        when (verb) {
            "update" -> {
                ctx.outLine("Reading package lists... ${PackageIndex.names().size} packages in the local index.")
                ctx.outLine("The index is compiled into the app: no bytes were downloaded.")
            }
            "upgrade" -> {
                ctx.outLine("Reading package lists... Done")
                ctx.outLine("0 upgraded, 0 newly installed, 0 to remove and 0 not upgraded.")
                ctx.outLine("The userland is this app's own code: there is nothing to upgrade into.")
            }
            "install" -> {
                if (names.isEmpty()) return ctx.fail("apt-get: no packages specified")
                for (name in names) {
                    val pkg = PackageIndex.find(name) ?: return noSuchPackage(ctx, name)
                    if (install(ctx, kernel, pkg) == null) {
                        ctx.outLine("${pkg.name} is already the newest version (${pkg.version}).")
                    }
                }
            }
            "remove", "autoremove" -> {
                if (names.isEmpty()) return ctx.fail("apt-get: no packages specified")
                for (name in names) {
                    val pkg = PackageIndex.find(name) ?: return noSuchPackage(ctx, name)
                    ops.remove(pkg, purge = false) { ctx.outLine(it) }
                }
            }
            "purge" -> {
                if (names.isEmpty()) return ctx.fail("apt-get: no packages specified")
                for (name in names) {
                    val pkg = PackageIndex.find(name) ?: return noSuchPackage(ctx, name)
                    ops.remove(pkg, purge = true) { ctx.outLine(it) }
                }
            }
            else -> {
                ctx.errLine("E: Invalid operation ${ctx.args.firstOrNull() ?: verb}")
                return ExecContext.EXIT_USAGE
            }
        }
        return ExecContext.EXIT_OK
    }

    private fun install(ctx: ExecContext, kernel: VmKernel, pkg: omp.vm.pkg.VmPackage): omp.vm.pkg.PackageOps.Outcome? =
        kernel.packageOps().install(pkg) { ctx.outLine(it) }
}
