package com.omp.terminal.android

import android.os.Build
import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext

/**
 * The kernel hostname is not readable by an app; the user-set device name from
 * `Settings.Global` is the closest public answer, and the model is the honest fallback when
 * nobody has set one.
 */
@CommandSpec(
    name = "hostname",
    synopsis = "",
    group = "android",
    notes = "Settings.Global device_name when the user has set one, else Build.MODEL; " +
        "the kernel hostname itself is not readable by an app",
)
object Hostname : Command {

    override fun run(ctx: ExecContext): Int {
        ctx.outLine(ctx.services.deviceName() ?: Build.MODEL)
        return ExecContext.EXIT_OK
    }
}
