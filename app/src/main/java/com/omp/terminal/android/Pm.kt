package com.omp.terminal.android

import android.os.Build
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage

/**
 * `pm list packages` and `pm path`, straight off [android.content.pm.PackageManager].
 *
 * API 30 introduced package visibility, and the manifest's `QUERY_ALL_PACKAGES` is what makes the
 * list long rather than nearly empty; without it every line here would be one of the few packages
 * the platform lets this app see, which is a policy result and not a bug in this command.
 */
@CommandSpec(
    name = "pm",
    synopsis = "list packages [-f] [-3] [-s] [-u] [-d] [-i] | path PKG",
    group = "android",
    notes = "reads PackageManager directly; -u cannot show data-only packages because " +
        "MATCH_UNINSTALLED_PACKAGES is not in the SDK, and -i needs API 30 for the installer column",
)
object Pm : FileCommand() {

    override val flagSpec = "fsu3di"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        return when (operands.firstOrNull()) {
            "list" -> {
                if (operands.getOrNull(1) != "packages") {
                    return ctx.fail("pm: list takes 'packages'; there is no other list target for an app")
                }
                listPackages(ctx, flags)
            }
            "path" -> {
                val pkg = operands.getOrNull(1)
                    ?: return ctx.fail("pm: path needs a package name")
                val paths = ctx.services.packagePaths(pkg)
                if (paths.isEmpty()) return ctx.fail("pm: package $pkg not found")
                for (path in paths) ctx.outLine(path)
                ExecContext.EXIT_OK
            }
            null -> ctx.fail("pm: no supported sub-command; available: list packages, path")
            else -> ctx.fail("pm: '${operands[0]}' is not supported; available: list packages, path")
        }
    }

    private fun listPackages(ctx: ExecContext, flags: String): Int {
        val thirdPartyOnly = flags.contains('3')
        val systemOnly = flags.contains('s')
        if (thirdPartyOnly && systemOnly) {
            ctx.errLine("pm: -3 and -s select opposite halves of the list; give one")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        val includeDisabled = flags.contains('d')
        val includeUninstalled = flags.contains('u')
        val services = ctx.services
        val every = services.installedPackages(true, includeDisabled, includeUninstalled)
        val notSystem = services.installedPackages(false, includeDisabled, includeUninstalled)
        val thirdParty = notSystem.toHashSet()
        val names: List<String> = when {
            thirdPartyOnly -> notSystem
            systemOnly -> every.filterNot { it in thirdParty }
            else -> every
        }

        if (includeUninstalled) {
            ctx.errLine(
                "pm: -u lists data-only packages, which need MATCH_UNINSTALLED_PACKAGES; that " +
                    "constant is not in the SDK, so this list holds installed packages only",
            )
        }
        if (flags.contains('i') && Build.VERSION.SDK_INT < 30) {
            ctx.errLine("pm: -i needs API 30 for PackageManager.getInstallSourceInfo; this device is API ${Build.VERSION.SDK_INT}")
        }
        val showFiles = flags.contains('f')
        val showInstaller = flags.contains('i')
        for (name in names.sorted()) {
            var line = if (showFiles) {
                val apk = services.packagePaths(name).firstOrNull() ?: "package:unknown"
                "$apk=$name"
            } else {
                name
            }
            if (showInstaller && Build.VERSION.SDK_INT >= 30) {
                line += "  installer=${services.installerOf(name) ?: "null"}"
            }
            ctx.outLine(line)
        }
        return ExecContext.EXIT_OK
    }
}
