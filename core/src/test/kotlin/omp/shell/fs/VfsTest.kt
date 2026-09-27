package omp.shell.fs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * [RealVfs] over a real temp directory: what each method answers, and the errno each refusal has to
 * come back with. The errno is not a detail here — it is the text the user reads — so every
 * failure below is asserted by name rather than by message.
 */
class VfsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var vfs: RealVfs

    @Before
    fun setUp() {
        root = folder.root
        vfs = RealVfs()
    }

    private fun path(name: String): String = File(root, name).path

    private fun write(name: String, text: String): File =
        File(root, name).apply { parentFile?.mkdirs(); writeText(text) }

    private fun touch(name: String): File =
        File(root, name).apply { parentFile?.mkdirs(); createNewFile() }

    private fun dir(name: String): File = File(root, name).apply { mkdirs() }

    private fun link(name: String, target: String): File =
        File(root, name).apply { Files.createSymbolicLink(toPath(), Paths.get(target)) }

    private fun errnoOf(body: () -> Unit): FsErrno {
        try {
            body()
        } catch (e: FsException) {
            return e.errno
        }
        fail("expected an FsException")
        throw AssertionError("unreachable")
    }

    // ---- stat ------------------------------------------------------------------------

    @Test
    fun statTellsTheKindsApart() {
        val file = write("file.txt", "hello")
        val sub = dir("sub")
        val sym = link("link.txt", file.path)

        val f = vfs.stat(file.path)
        assertEquals(VNodeType.FILE, f.type)
        assertEquals(5L, f.size)
        assertTrue(f.readable)
        assertTrue(f.writable)
        assertFalse(f.executable)
        // 0x180 is the owner's read+write pair, 0644 with any ordinary umask; a plain file is not
        // executable for anyone unless something made it so.
        assertEquals(0x180, f.mode and 0x1C0)

        val d = vfs.stat(sub.path)
        assertEquals(VNodeType.DIRECTORY, d.type)
        assertTrue(d.executable)

        val s = vfs.stat(sym.path)
        assertEquals(0x180, f.mode and 0x1C0)
        // A link is measured as itself — the target string — which is the size `ls -l` shows.
        assertEquals(file.path.length.toLong(), s.size)

        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { vfs.stat(path("absent")) })
    }

    @Test
    fun statSeesADanglingLink() {
        val sym = link("dangling", path("never-created"))
        assertEquals(VNodeType.SYMLINK, vfs.stat(sym.path).type)
        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { vfs.stat(path("never-created")) })
    }

    // ---- readDir --------------------------------------------------------------------

    @Test
    fun readDirNamesEveryEntryWithItsOwnStat() {
        write("b.txt", "bb")
        write("sub/c.txt", "cccc")
        dir("sub")

        val entries = vfs.readDir(root.path)
        assertEquals(setOf("b.txt", "sub"), entries.map { it.name }.toSet())
        val byName = entries.associateBy { it.name }
        assertEquals(VNodeType.FILE, byName.getValue("b.txt").stat.type)
        assertEquals(2L, byName.getValue("b.txt").stat.size)
        assertEquals(VNodeType.DIRECTORY, byName.getValue("sub").stat.type)

        assertEquals(FsErrno.NOT_A_DIRECTORY, errnoOf { vfs.readDir(path("b.txt")) })
        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { vfs.readDir(path("absent")) })
    }

    // ---- streams and whole files ----------------------------------------------------

    @Test
    fun openWriteAndOpenReadRoundTrip() {
        val p = path("io.txt")
        vfs.openWrite(p, false).use { it.write("first".toByteArray()) }
        vfs.openWrite(p, true).use { it.write("-second".toByteArray()) }
        vfs.openRead(p).use { assertEquals("first-second", String(it.readBytes(), Charsets.UTF_8)) }

        // Without append the file is truncated, which is what `>` means and what `>>` must not do.
        vfs.openWrite(p, false).use { it.write("only".toByteArray()) }
        vfs.openRead(p).use { assertEquals("only", String(it.readBytes(), Charsets.UTF_8)) }

        assertEquals(FsErrno.IS_A_DIRECTORY, errnoOf { vfs.openRead(root.path) })
        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { vfs.openRead(path("absent")) })
    }

    @Test
    fun writeBytesCreatesAndTruncates() {
        val p = path("blob.bin")
        vfs.writeBytes(p, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), vfs.readBytes(p))
        vfs.writeBytes(p, byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), vfs.readBytes(p))
        assertEquals(FsErrno.IS_A_DIRECTORY, errnoOf { vfs.readBytes(root.path) })
        assertEquals(FsErrno.IS_A_DIRECTORY, errnoOf { vfs.writeBytes(root.path, byteArrayOf(0)) })
    }

    // ---- create, mkdir, delete -------------------------------------------------------

    @Test
    fun createFileIsExclusive() {
        val p = path("fresh")
        vfs.createFile(p)
        assertTrue(File(p).isFile)
        assertEquals(FsErrno.FILE_EXISTS, errnoOf { vfs.createFile(p) })
        dir("taken")
        assertEquals(FsErrno.FILE_EXISTS, errnoOf { vfs.createFile(path("taken")) })
        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { vfs.createFile(path("no/such/dir/f")) })
    }

    @Test
    fun mkdirIsOneLevelAndExclusive() {
        vfs.mkdir(path("one"))
        assertTrue(File(path("one")).isDirectory)
        // One level only: a second lands inside the first, a third would need a parent that is not
        // there, and `mkdir -p` is a different command.
        vfs.mkdir(path("one/two"))
        assertTrue(File(path("one/two")).isDirectory)
        assertEquals(FsErrno.FILE_EXISTS, errnoOf { vfs.mkdir(path("one")) })
        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { vfs.mkdir(path("absent/child")) })
        assertFalse(File(path("child")).exists())
    }

    @Test
    fun deleteUnlinksAFileAndRefusesADirectory() {
        val file = touch("gone.txt")
        dir("keep")
        assertEquals(FsErrno.IS_A_DIRECTORY, errnoOf { vfs.delete(path("keep")) })
        vfs.delete(file.path)
        assertFalse(file.exists())
        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { vfs.delete(file.path) })
    }

    @Test
    fun deleteUnlinksTheLinkNotItsTarget() {
        val file = write("target.txt", "payload")
        val sym = link("to-target", file.path)
        vfs.delete(sym.path)
        assertFalse(sym.exists())
        assertTrue(file.exists())
    }

    // ---- rename ----------------------------------------------------------------------

    @Test
    fun renameMovesAFileAndReplacesItsTarget() {
        write("from.txt", "first")
        vfs.rename(path("from.txt"), path("to.txt"))
        assertEquals("first", File(path("to.txt")).readText())
        assertFalse(File(path("from.txt")).exists())

        // `mv` is rename(2): the destination is replaced, not refused.
        write("other.txt", "about to be replaced")
        vfs.rename(path("to.txt"), path("other.txt"))
        assertEquals("first", File(path("other.txt")).readText())
        assertFalse(File(path("to.txt")).exists())

        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { vfs.rename(path("absent"), path("x")) })
    }

    // ---- links -----------------------------------------------------------------------

    @Test
    fun symlinkAndReadLinkRoundTrip() {
        val target = write("target.txt", "t")
        vfs.symlink(target.path, path("abs-link"))
        assertEquals(target.path, vfs.readLink(path("abs-link")))

        // A relative target is stored as typed: rewriting it to an absolute path would freeze an
        // answer that the kernel only gives at the moment of use.
        vfs.symlink("target.txt", path("rel-link"))
        assertEquals("target.txt", vfs.readLink(path("rel-link")))

        assertEquals(FsErrno.FILE_EXISTS, errnoOf { vfs.symlink(target.path, path("abs-link")) })
        assertEquals(FsErrno.FILE_EXISTS, errnoOf { vfs.symlink(target.path, path("rel-link")) })
        assertEquals(FsErrno.INVALID_ARGUMENT, errnoOf { vfs.readLink(target.path) })
        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { vfs.readLink(path("absent")) })
    }

    @Test
    fun symlinkRefusesToReplaceEvenADanglingOne() {
        val existing = link("dangling", path("never-created"))
        assertFalse(existing.exists())
        assertEquals(FsErrno.FILE_EXISTS, errnoOf { vfs.symlink(path("other"), existing.path) })
    }

    // ---- realpath --------------------------------------------------------------------

    @Test
    fun realpathFollowsAChainOfLinks() {
        val base = folder.newFolder("chain")
        val real = File(base, "real").apply { mkdirs() }
        Files.createSymbolicLink(File(base, "one").toPath(), real.toPath())
        Files.createSymbolicLink(File(base, "two").toPath(), Paths.get("one"))
        Files.createSymbolicLink(File(base, "three").toPath(), Paths.get("two"))

        assertEquals(real.canonicalPath, vfs.realpath(File(base, "three").path))
        // `..` is applied before the hop, not after: `up` is followed and only then does `..` climb
        // out of it, so this lands back in `real` and not in `base`.
        Files.createSymbolicLink(File(base, "up").toPath(), Paths.get(".."))
        assertEquals(real.canonicalPath, vfs.realpath(File(base, "up/chain/real").path))
    }

    @Test
    fun realpathFollowsADanglingLinkToItsMissingTarget() {
        val target = path("never-created")
        val sym = link("dangling", target)
        assertEquals(target, vfs.realpath(sym.path))
        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { vfs.stat(vfs.realpath(sym.path)) })
    }

    @Test
    fun realpathRefusesALoop() {
        val base = folder.newFolder("loop")
        Files.createSymbolicLink(File(base, "self").toPath(), Paths.get("self"))
        Files.createSymbolicLink(File(base, "ping").toPath(), Paths.get("pong"))
        Files.createSymbolicLink(File(base, "pong").toPath(), Paths.get("ping"))
        assertEquals(FsErrno.SYMLINK_LOOP, errnoOf { vfs.realpath(File(base, "self").path) })
        assertEquals(FsErrno.SYMLINK_LOOP, errnoOf { vfs.realpath(File(base, "ping").path) })
    }

    @Test
    fun aPlainPathComesBackUnchanged() {
        val file = write("plain.txt", "x")
        assertEquals(file.path, vfs.realpath(file.path))
    }

    // ---- root and binds --------------------------------------------------------------

    @Test
    fun rootConfinesEveryPathToOneDirectory() {
        val rooted = RealVfs(root.path)
        rooted.mkdir("/sub")
        rooted.writeBytes("/sub/file.txt", "inside".toByteArray())

        assertEquals("inside", File(root, "sub/file.txt").readText())
        assertEquals(listOf("sub"), rooted.readDir("/").map { it.name })
        assertEquals(VNodeType.FILE, rooted.stat("/sub/file.txt").type)
        // Nothing outside the root is reachable, however it is spelled.
        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { rooted.readBytes("/sub/../elsewhere") })

        // The rest of the seam agrees about the root, not just the byte-moving methods.
        rooted.setModified("/sub/file.txt", STAMP)
        assertEquals(STAMP, rooted.stat("/sub/file.txt").mtimeMillis)
        assertTrue(rooted.diskUsage("/sub").totalBytes > 0)
    }

    @Test
    fun aBindIsHonouredAndDoesNotLeak() {
        val bound = folder.newFolder("bound")
        val outer = folder.newFolder("outer")
        val jail = folder.newFolder("jail")
        File(bound, "inside.txt").writeText("from-bound")
        File(outer, "inside.txt").writeText("from-outer")

        val v = RealVfs(jail.path, listOf("/mnt" to outer.path, "/mnt/bind" to bound.path))
        // Longest match wins, so `/mnt/bind` is the bound directory and `/mnt` is the other one.
        assertEquals("from-bound", String(v.readBytes("/mnt/bind/inside.txt"), Charsets.UTF_8))
        assertEquals("from-outer", String(v.readBytes("/mnt/inside.txt"), Charsets.UTF_8))

        v.writeBytes("/mnt/bind/written.txt", "w".toByteArray())
        assertTrue(File(bound, "written.txt").isFile)
        assertFalse(File(outer, "written.txt").exists())

        // The jail is the root of everything that is not under a bind, and a bind does not widen it.
        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { v.readBytes("/inside.txt") })
        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { v.readBytes("/bind/inside.txt") })

        v.setModified("/mnt/bind/inside.txt", STAMP)
        assertEquals(STAMP, v.stat("/mnt/bind/inside.txt").mtimeMillis)
        // The space is the one the bound directory lives on, measured the same way with no bind and
        // no root in the way at all.
        assertEquals(RealVfs().diskUsage(bound.path).totalBytes, v.diskUsage("/mnt/bind").totalBytes)
    }

    // ---- space and stamps ------------------------------------------------------------

    @Test
    fun diskUsageAnswersForAPathThatIsNotThere() {
        val usage = vfs.diskUsage(path("no/such/place"))
        assertTrue(usage.totalBytes > 0)
        assertTrue(usage.freeBytes <= usage.totalBytes)
    }

    @Test
    fun setModifiedMovesTheStamp() {
        val file = write("stamped.txt", "x")
        vfs.setModified(file.path, STAMP)
        assertEquals(STAMP, vfs.stat(file.path).mtimeMillis)
        assertEquals(FsErrno.NO_SUCH_FILE, errnoOf { vfs.setModified(path("absent"), STAMP) })
    }

    private companion object {
        /** A whole second in 2001: no filesystem rounds this, so the assertion can be exact. */
        const val STAMP = 1_000_000_000_000L
    }
}
