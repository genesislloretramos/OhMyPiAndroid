package omp.shell

import omp.shell.exec.CommandSpec
import omp.shell.exec.CommandTable
import omp.shell.exec.ExecContext
import omp.shell.exec.Shell
import omp.term.Screen
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** Runs shell lines against a temp directory and captures both streams. */
class ShellHarness(val root: File) {

    val screen = Screen(24, 80)
    val services = StubPlatformServices(home = File(root, "home").path, initialDir = root.path)
    val session = Session(services, screen)
    val shell = Shell(session)
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    init {
        File(root, "home").mkdirs()
        session.cwd = root.path
        session.oldPwd = root.path
        shell.topStderr = err
    }

    fun run(line: String, stdin: InputStream = ByteArrayInputStream(ByteArray(0))): Int =
        shell.executeLine(line, stdin, out, err, true)

    fun stdout(): String = String(out.toByteArray(), Charsets.UTF_8)

    fun stderr(): String = String(err.toByteArray(), Charsets.UTF_8)

    fun reset() {
        out.reset()
        err.reset()
    }

    fun write(name: String, content: String): File = File(root, name).apply { writeText(content) }
}

@CommandSpec(name = "tty-probe", synopsis = "", group = "test")
object TtyProbe : omp.shell.exec.Command {
    override fun run(ctx: ExecContext): Int {
        ctx.outLine(if (ctx.isTty) "tty" else "pipe")
        return ExecContext.EXIT_OK
    }
}

fun installTestCommands() {
    if (CommandTable.lookup("tty-probe") == null) CommandTable.register(TtyProbe)
}

class TempDirRule {
    @get:Rule
    val folder = TemporaryFolder()
}
