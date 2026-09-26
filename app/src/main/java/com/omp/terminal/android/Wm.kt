package com.omp.terminal.android

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

/**
 * Physical display geometry from the framework, and the override rows reading `none`.
 *
 * An app may not read the current size or density override — those live behind
 * `wm size`/`wm density` in system settings — so reporting a value there would be a guess about
 * the user's display. `none` is the truth: there is no override this app can see.
 */
@CommandSpec(
    name = "wm",
    synopsis = "size | density",
    group = "android",
    notes = "physical values come from the display; the override rows read 'none' because a " +
        "non-privileged app cannot read the current override, and inventing one would be worse",
)
object Wm : FileCommand() {

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        val services = ctx.services
        return when (operands.firstOrNull()) {
            "size" -> {
                ctx.outLine("Physical size: ${services.displayWidthPx()}x${services.displayHeightPx()}")
                ctx.outLine("Override size: none")
                ExecContext.EXIT_OK
            }
            "density" -> {
                ctx.outLine("Physical density: ${services.displayDensityDpi()}")
                ctx.outLine("Override density: none")
                ExecContext.EXIT_OK
            }
            null -> ctx.fail("wm: no sub-command; available: size, density")
            else -> ctx.fail("wm: '${operands[0]}' requires system privileges; available: size, density")
        }
    }
}
