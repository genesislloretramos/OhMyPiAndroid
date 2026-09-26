package com.omp.terminal.android

import omp.shell.cmd.Cmds
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.io.File
import java.io.IOException

/**
 * A PNG of this app's own window, taken with `PixelCopy`.
 *
 * This is the whole of what a non-privileged app may capture. `MediaProjection` would ask the user
 * for a system-wide consent dialog and then hand back a stream of somebody else's screen, which
 * is not what a terminal command should do unasked, and no public API lets one process copy
 * another process's window. So the file is this window, and `help screencap` says so.
 */
@CommandSpec(
    name = "screencap",
    synopsis = "[-p] [FILE]",
    group = "android",
    notes = "PixelCopy of this app's own window, which is all a non-privileged app can capture; " +
        "the default file is \$HOME/screen-<epochMillis>.png and -p is a no-op because the output " +
        "is always a real PNG",
)
object Screencap : FileCommand() {

    override val flagSpec = "p"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        val target = operands.firstOrNull()
            ?: "${ctx.services.homeDir()}/screen-${ctx.services.wallClockMillis()}.png"
        val png = ctx.services.captureScreenPng()
            ?: return ctx.fail(
                "screencap: nothing to copy: this window has no drawn surface yet, and only this " +
                    "app's own window can be captured",
            )
        val path = Cmds.resolve(ctx, target) ?: return ExecContext.EXIT_GENERAL_ERROR
        val file = File(path)
        return try {
            file.writeBytes(png)
            ctx.outLine(file.absolutePath)
            ExecContext.EXIT_OK
        } catch (e: IOException) {
            ctx.fail("screencap: $target: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
