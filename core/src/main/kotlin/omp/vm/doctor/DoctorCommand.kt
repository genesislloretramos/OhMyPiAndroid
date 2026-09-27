package omp.vm.doctor

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

/**
 * `omp doctor`: everything this device can be asked about, read from the live platform and the
 * live disk, in one screen.
 *
 * **It is read-only and it says so in its own help line**, because that is the promise a user
 * needs before pasting its output into a bug tracker: nothing here downloads, writes, starts or
 * stops anything, and the only socket is one 400 ms connect probe on loopback. See [Doctor].
 *
 * ### Why it is a [FileCommand] with no options
 *
 * A diagnostic that took flags would have a surface to get wrong — and there is nothing here to
 * select. The one thing a user might want to vary, which files to read, is decided by the state
 * on the device, and a flag that narrowed it would narrow the bug report too. So it is a plain
 * command, and a flag is an error rather than a silent no-op, like everywhere else in this shell.
 */
@CommandSpec(
    name = "doctor",
    synopsis = "",
    group = "system",
    notes = "read-only: starts no download, changes no file, and opens no socket beyond one 400 ms " +
        "connect probe on loopback. Every fact below comes from the live platform or the live disk; " +
        "anything it could not read says 'unreadable:' rather than staying quiet",
)
object DoctorCommand : FileCommand() {

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        if (operands.isNotEmpty()) {
            ctx.errLine("doctor: it takes no arguments and reads the whole device; '${operands.first()}' is not one")
            return ExecContext.EXIT_USAGE
        }
        // The session's own Vfs, which is the phone's filesystem and not a guess about it: a
        // doctor run inside the namespace would report the namespace, and every path it names is
        // a host path, so that is answered as "unreadable" rather than as a healthy-looking value.
        //
        // The guest's port is [omp.vm.provision.GuestWeb.RESERVED_PORT] and not a literal here, so
        // the number this report prints on `origin:` and on the `guest origin` section is the same
        // one [omp.vm.provision.GuestStart] checked and [com.omp.terminal.web.UiOrigins] builds the
        // guest's base URL from. Three spellings of one port would be three chances to lie about
        // which server is on the screen.
        val report = Doctor(
            ctx.services,
            ctx.session.vfs,
            guestPort = omp.vm.provision.GuestWeb.RESERVED_PORT,
        ).report()
        for (line in report.lines) ctx.outLine(line)
        return ExecContext.EXIT_OK
    }
}
