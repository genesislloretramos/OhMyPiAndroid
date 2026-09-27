package omp.vm.cmd

import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.fs.PathException
import omp.shell.fs.PathResolver
import omp.vm.VmKernel
import omp.vm.VmVfs
import omp.vm.pkg.DpkgDatabase
import omp.vm.pkg.PackageOps
import omp.vm.service.ServiceManager

/**
 * What every VM command needs and none of them should write twice: a path resolved the way the
 * shell resolves one (tilde, the session's cwd, `..`, symlinks — all through the session's [Vfs]),
 * and the kernel behind it.
 *
 * The kernel is found through the [Vfs] the session was handed, not through a global, so two VMs in
 * one process are two VMs. A command run in the phone's session finds null and says so, which is
 * what `mount: not inside a VM namespace` means.
 */

/** @return the absolute path for [raw], or null after reporting why it could not be resolved. */
internal fun resolvePath(ctx: ExecContext, raw: String): String? = try {
    PathResolver.resolve(ctx.session, raw)
} catch (e: PathException) {
    ctx.errLine("${ctx.name}: $raw: ${e.message}")
    null
}

/** The kernel this command is running inside, or null when it is not running inside one. */
internal fun kernelOf(ctx: ExecContext): VmKernel? = (ctx.session.vfs as? VmVfs)?.owner

internal fun requireKernel(ctx: ExecContext): VmKernel? {
    if (kernelOf(ctx) == null) ctx.errLine("${ctx.name}: not inside a VM namespace")
    return kernelOf(ctx)
}

internal fun databaseOf(ctx: ExecContext): DpkgDatabase? = requireKernel(ctx)?.packages

internal fun unitsOf(ctx: ExecContext): ServiceManager? = requireKernel(ctx)?.units

internal fun opsOf(ctx: ExecContext): PackageOps? = requireKernel(ctx)?.packageOps()

/** `<cmd>: <path>: <reason>`, the shell's one way of saying a filesystem said no. */
internal fun reportFsError(ctx: ExecContext, path: String, e: Throwable): Int =
    ctx.fail("${ctx.name}: $path: ${Errno.messageFor(e)}")

/** `Failed to fetch`-free wording for a package the local index does not have. */
internal fun noSuchPackage(ctx: ExecContext, name: String): Int {
    ctx.errLine("E: Unable to locate package $name")
    return omp.vm.pkg.EXIT_NO_PACKAGE
}
