package com.omp.terminal.android

import omp.shell.PlatformServices
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage

/**
 * The two `am` sub-commands an ordinary app can carry out: starting an activity, and stopping
 * another package's background processes. Everything else `am` does is a call into a system-only
 * binder, and the shell says so rather than pretending to have done it.
 */
@CommandSpec(
    name = "am",
    synopsis = "force-stop PKG | start [-a ACTION] [-d DATA] [-t MIME] [-c CAT] [-n COMPONENT]",
    group = "android",
    notes = "start builds an Intent and launches it; force-stop calls killBackgroundProcesses, " +
        "the public neighbour of the @hide forceStopPackage, so a stopped package may be restarted",
)
object Am : FileCommand() {

    override val valueSpec = "a:d:t:c:n:"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        return when (operands.firstOrNull()) {
            "force-stop" -> forceStop(ctx, operands.getOrNull(1))
            "start" -> start(ctx, options)
            else -> ctx.fail("am: no supported sub-command; use force-stop or start")
        }
    }

    private fun forceStop(ctx: ExecContext, pkg: String?): Int {
        if (pkg == null) {
            ctx.errLine("am: force-stop needs a package name")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        val error = ctx.services.forceStopPackage(pkg)
        if (error != null) return ctx.fail(error)
        ctx.outLine("Killed the background processes of $pkg.")
        ctx.outLine(
            "That is killBackgroundProcesses, all an ordinary app may call: ActivityManager" +
                ".forceStopPackage is @hide, so $pkg is free to be started again.",
        )
        return ExecContext.EXIT_OK
    }

    private fun start(ctx: ExecContext, options: Map<String, String>): Int {
        val spec = PlatformServices.IntentSpec(
            action = options["a"],
            data = options["d"],
            type = options["t"],
            category = options["c"],
            component = options["n"],
        )
        if (spec.action == null && spec.data == null && spec.type == null && spec.component == null) {
            ctx.errLine("am: start needs at least one of -a, -d, -t or -n")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        val error = ctx.services.startActivity(spec)
        if (error != null) return ctx.fail(error)
        ctx.outLine("Starting: Intent { ${describeIntent(spec)} }")
        return ExecContext.EXIT_OK
    }
}
