package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

/**
 * The kernel mount table is closed to untrusted apps by `app_neverallows.te`
 * (`proc_mounts` is in the neverallow list), so this reports the storage volumes the framework does
 * expose instead of printing a fabricated `/proc/mounts` dump.
 */
@CommandSpec(
    name = "mount",
    synopsis = "",
    group = "system",
    notes = "lists StorageManager volumes; the kernel mount table (/proc/mounts) is closed to apps, and this command takes no options",
)
object Mount : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        ctx.errLine("mount: /proc/mounts is not readable by an app; showing StorageManager volumes instead")
        ctx.outLine("  device                 state      flags  mount point")
        for (v in ctx.services.storageVolumes()) {
            val flagsText = buildList {
                if (v.primary) add("primary")
                if (v.emulated) add("emulated")
                if (v.removable) add("removable")
            }.joinToString(",")
            val device = if (v.emulated) "sdcardfs/$v.mountPoint" else "volume:$v.mountPoint"
            ctx.outLine("  $device  ${v.state.padEnd(10)}  ${flagsText.padEnd(6)}  ${v.mountPoint}")
        }
        return ExecContext.EXIT_OK
    }
}
