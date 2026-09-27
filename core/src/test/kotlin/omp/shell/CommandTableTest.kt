package omp.shell

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.CommandTable
import omp.shell.exec.ExecContext
import omp.shell.exec.Shell
import omp.term.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@CommandSpec(name = "ns-probe", synopsis = "", group = "test")
private object NsProbe : Command {
    override fun run(ctx: ExecContext): Int {
        ctx.outLine("probe")
        return ExecContext.EXIT_OK
    }
}

@CommandSpec(name = "ns-only-in-copy", synopsis = "", group = "test")
private object CopyOnly : Command {
    override fun run(ctx: ExecContext): Int = ExecContext.EXIT_OK
}

/**
 * A second [CommandTable] is a second set of names, not a second set of commands: the builtins are
 * the same stateless objects, and a name registered into one table is invisible to the other. This
 * is what lets a namespace answer `systemctl` without the phone's session growing one.
 */
class CommandTableTest {

    @get:Rule
    val folder = TemporaryFolder()

    private data class Run(val status: Int, val out: String, val err: String)

    private fun run(table: CommandTable, line: String): Run {
        val services = StubPlatformServices(home = folder.root.path, initialDir = folder.root.path)
        val session = Session(services, Screen(24, 80), table = table)
        val shell = Shell(session, table)
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val status = shell.executeLine(line, ByteArrayInputStream(ByteArray(0)), out, err, true)
        return Run(status, String(out.toByteArray(), Charsets.UTF_8), String(err.toByteArray(), Charsets.UTF_8))
    }

    @Test
    fun aSecondTableDispatchesItsOwnNamesAndTheGlobalOneDoesNot() {
        val table = CommandTable().registerDefaults().register(NsProbe)

        val inNamespace = run(table, "ns-probe")
        assertEquals(0, inNamespace.status)
        assertEquals("probe\n", inNamespace.out)

        // The global table never heard of it: a namespace's command must not leak into the phone's.
        assertNull(CommandTable.global.lookup("ns-probe"))
        assertNull(CommandTable.lookup("ns-probe"))
        val inGlobal = run(CommandTable.global, "ns-probe")
        assertEquals(ExecContext.EXIT_NOT_FOUND, inGlobal.status)
        assertEquals("sh: ns-probe: command not found\n", inGlobal.err)
        assertTrue(CommandTable.global.lookup("ls") != null)
    }

    @Test
    fun aTableBuiltFromDefaultsHasTheBuiltins() {
        val table = CommandTable().registerDefaults()
        assertEquals(0, run(table, "ls").status)
        assertTrue(table.names().containsAll(listOf("ls", "cat", "echo", "help", "df")))
        // Only the tables that registered the probe list a "test" group.
        assertTrue(table.byGroup().none { it.first == "test" })
        assertEquals(0, run(table, "help ls").status)
    }

    @Test
    fun aCopyKeepsTheNamesAndIsIndependent() {
        val table = CommandTable().registerDefaults().register(NsProbe)
        val copy = table.copy()
        assertEquals("probe\n", run(copy, "ns-probe").out)

        copy.register(CopyOnly)
        assertEquals(0, run(copy, "ns-only-in-copy").status)
        assertEquals(ExecContext.EXIT_NOT_FOUND, run(table, "ns-only-in-copy").status)
        assertNull(CommandTable.global.lookup("ns-only-in-copy"))
    }
}
