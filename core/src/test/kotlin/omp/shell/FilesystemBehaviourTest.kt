package omp.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * What the filesystem-facing commands do with real files, byte for byte. These expectations are
 * the contract a filesystem abstraction has to keep: every one of them is a string a user reads,
 * not an internal detail. Nothing here may depend on the build host, so `df` runs against stubbed
 * volumes and every other command runs inside a per-test directory.
 *
 * `ln -s` is asserted through what it leaves on disk as well as through a listing, because every
 * path the shell resolves is followed to its target first, so a symlink is only visible as a
 * directory entry.
 *
 * TAB completion is out of scope here: it lives in [LineEditor], is driven by keys rather than by
 * a command line, and `LineEditorTest` already pins the part of it observable that way.
 */
class FilesystemBehaviourTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: ShellHarness

    @Before
    fun setUp() {
        installTestCommands()
        h = ShellHarness(folder.root)
    }

    /**
     * A directory of its own for the test, with the shell sitting in it. The harness puts `$HOME`
     * beside the working directory, so a listing never has to account for it.
     */
    private fun inFolder(name: String): File {
        val base = folder.newFolder(name)
        val work = File(base, "work").apply { mkdirs() }
        h = ShellHarness(base)
        h.session.cwd = work.path
        h.session.oldPwd = work.path
        return work
    }

    /** [name] may name a path inside [dir]; the parents are created so a test can seed a tree. */
    private fun seed(dir: File, name: String, content: String): File =
        File(dir, name).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private fun run(line: String, stdin: String = ""): String {
        h.reset()
        h.run(line, ByteArrayInputStream(stdin.toByteArray()))
        return h.stdout()
    }

    // ---- ls ---------------------------------------------------------------------------

    @Test
    fun lsListsEveryEntryInNameOrder() {
        val work = inFolder("ls-plain")
        seed(work, "c.dat", "z")
        seed(work, "a.txt", "x")
        seed(work, "b.txt", "y")
        // -1 is one name per line. Without it the output is the column layout the harness's
        // 80-column screen asks for, every name padded to the widest name plus two spaces.
        assertEquals("a.txt\nb.txt\nc.dat\n", run("ls -1 --color=never"))
        assertEquals("a.txt  b.txt  c.dat\n", run("ls"))
    }

    @Test
    fun lsLongFormatLeadsWithTheTypeCharacterAndCarriesTheSize() {
        val work = inFolder("ls-long")
        seed(work, "note.txt", "hello\n")
        val fields = run("ls -l --color=never note.txt").trim().split(WHITESPACE)
        // perms size mtime name, and nothing else: an app cannot read a file's owner. The mtime is
        // two fields wide because it is written as a date and a time with a space between them.
        assertEquals(5, fields.size)
        assertEquals("-rw-------", fields[0])
        assertEquals("6", fields[1])
        assertTrue(fields[2], TIMESTAMP.matches(fields[2] + " " + fields[3]))
        assertEquals("note.txt", fields[4])
    }

    @Test
    fun lsLongFormatGivesADirectoryItsOwnTypeCharacter() {
        val work = inFolder("ls-long-dir")
        File(work, "sub").mkdirs()
        val fields = run("ls -ld --color=never sub").trim().split(WHITESPACE)
        assertEquals(5, fields.size)
        // A directory is searchable, so it keeps its x where a plain file has a dash.
        assertEquals("drwx------", fields[0])
        assertEquals("d", fields[0].substring(0, 1))
        assertEquals("sub", fields[4])
    }

    @Test
    fun lsPaintsADirectoryBlueWhenTheOutputIsATerminal() {
        val work = inFolder("ls-colour")
        File(work, "sub").mkdirs()
        seed(work, "a.txt", "x")
        // The harness's last pipeline stage is a tty, so this is the colouring a user sees; a
        // regular file is left alone, and --color=never turns the whole thing off.
        assertEquals("a.txt\n[1;34msub[0m\n", run("ls -1"))
        assertEquals("a.txt\nsub\n", run("ls -1 --color=never"))
    }

    @Test
    fun lsDashAAddsTheDotEntriesAndListsDotFilesEitherWay() {
        val work = inFolder("ls-all")
        seed(work, "a.txt", "x")
        seed(work, ".hidden", "h")
        // -a is what adds the two entries for the directory itself. A dot file is in the listing
        // with or without it, which is what this shell does; a glob is the thing that has to ask
        // for a leading dot explicitly (see `aGlobExpandsToTheMatchingNamesOnly`).
        assertEquals(".\n..\n.hidden\na.txt\n", run("ls -1a --color=never"))
        assertEquals(".hidden\na.txt\n", run("ls -1 --color=never"))
    }

    @Test
    fun lsRecursiveNamesEachDirectoryItDescendsInto() {
        val work = inFolder("ls-recursive")
        seed(work, "dir/a.txt", "a")
        seed(work, "dir/sub/b.txt", "b")
        // The root's own name is not printed: only the directories walked into, each followed by a
        // blank line and each listed before it is descended into.
        assertEquals(
            "dir\n\ndir:\na.txt\nsub\n\nsub:\nb.txt\n\n",
            run("ls -1R --color=never"),
        )
    }

    @Test
    fun lsOfAMissingOperandFailsWithTheErrnoName() {
        h.reset()
        val status = h.run("ls nosuch.txt")
        assertEquals(1, status)
        assertEquals("", h.stdout())
        assertEquals("ls: nosuch.txt: No such file or directory\n", h.stderr())
    }

    // ---- stat -------------------------------------------------------------------------

    @Test
    fun statLabelsForARegularFile() {
        val work = inFolder("stat-file")
        seed(work, "note.txt", "hello\n")
        val text = run("stat note.txt")
        val lines = text.lines()
        // The record ends with a blank line of its own, which `lines()` reports as a trailing ""
        // after the newline that ended the last field.
        assertEquals(
            listOf("  File", "  Size", "  Type", "Access", "Modify", "Change", "  Mode", "", ""),
            lines.map { it.substringBefore(':') },
        )
        assertTrue(text, text.endsWith("  Mode: -rw-------\n\n"))
        assertEquals("  File: " + File(work, "note.txt").canonicalPath, lines[0])
        assertEquals("  Size: 6", lines[1])
        assertEquals("  Type: regular file", lines[2])
        assertTrue(lines[3], TIMESTAMP_SECONDS.matches(lines[3].substringAfter(": ")))
        assertTrue(lines[4], TIMESTAMP_SECONDS.matches(lines[4].substringAfter(": ")))
        assertTrue(lines[5], TIMESTAMP_SECONDS.matches(lines[5].substringAfter(": ")))
    }

    @Test
    fun statLabelsForADirectory() {
        val work = inFolder("stat-dir")
        File(work, "sub").mkdirs()
        val text = run("stat sub")
        val lines = text.lines()
        assertEquals(
            listOf("  File", "  Size", "  Type", "Access", "Modify", "Change", "  Mode", "", ""),
            lines.map { it.substringBefore(':') },
        )
        assertTrue(text, text.endsWith("  Mode: drwx------\n\n"))
        assertEquals("  File: " + File(work, "sub").canonicalPath, lines[0])
        assertEquals("  Type: directory", lines[2])
    }

    // ---- du and df --------------------------------------------------------------------

    @Test
    fun duReportsTheTreeInKilobytesWithChildrenBeforeParents() {
        val work = inFolder("du-tree")
        seed(work, "tree/a.txt", "0123456789\n")
        seed(work, "tree/sub/b.txt", "abcdefghijklmnopqrst\n")
        // A directory counts as one 4096-byte block, so `sub` is 4117 bytes and `tree` is 8224.
        // Sizes are whole 1K blocks rounded up: 11 -> 1, 4117 -> 5, 8224 -> 9.
        assertEquals("9\ttree\n", run("du tree"))
        assertEquals("8.0K\ttree\n", run("du -h tree"))
        // -a is what prints an entry of its own, and a child is printed before its parent.
        assertEquals("1\ttree/a.txt\n1\ttree/sub/b.txt\n5\ttree/sub\n9\ttree\n", run("du -a tree"))
    }

    @Test
    fun duOfASingleFileReportsThatFile() {
        val work = inFolder("du-file")
        seed(work, "a.txt", "0123456789\n")
        assertEquals("1\ta.txt\n", run("du a.txt"))
        assertEquals("11B\ta.txt\n", run("du -h a.txt"))
    }

    @Test
    fun dfPrintsOneRowPerStubbedVolumeInColumnOrder() {
        inFolder("df-volumes")
        h.services.volumes += PlatformServices.StorageVolume(
            "/storage/emulated/0", "mounted", primary = true, removable = false, emulated = true,
            totalBytes = 100_000_000_000L, usableBytes = 25_000_000_000L,
        )
        // A volume mounted at `/` is what stops `df` from appending the build host's own root,
        // whose numbers would make this assertion depend on the machine it runs on.
        h.services.volumes += PlatformServices.StorageVolume(
            "/", "mounted", primary = false, removable = false, emulated = false,
            totalBytes = 10_000_000_000L, usableBytes = 4_000_000_000L,
        )
        val rows = run("df").trimEnd().lines()
        // The header is fixed, and the stubbed volumes follow it in the order they were declared.
        // This used to `return` on a host that has /data, which is this build machine, so on most
        // machines the assertion below never ran. `df` also reports a real `/data` when there is
        // one, so the stubbed rows are pinned by position and anything past them is checked to be
        // that host row and nothing else: the contract is one row per stubbed volume, in order,
        // with the columns where they belong, and that is asserted everywhere.
        assertEquals("Filesystem              Size       Used      Avail  Use% Mounted on", rows.first())
        assertEquals(
            "/storage/emulated/0 100000000000  75000000000  25000000000   75% /storage/emulated/0",
            rows[1],
        )
        assertEquals(
            "/                   10000000000  6000000000  4000000000   60% /",
            rows[2],
        )
        assertTrue(rows.drop(3).all { it.startsWith("/data ") })
    }

    @Test
    fun dfOfAMissingPathFailsWithTheErrnoName() {
        inFolder("df-missing")
        h.services.volumes += PlatformServices.StorageVolume(
            "/", "mounted", primary = false, removable = false, emulated = false,
            totalBytes = 10_000_000_000L, usableBytes = 4_000_000_000L,
        )
        h.reset()
        val status = h.run("df nosuch")
        assertEquals(1, status)
        assertEquals("", h.stdout())
        assertEquals("df: nosuch: No such file or directory\n", h.stderr())
    }

    // ---- find -------------------------------------------------------------------------

    @Test
    fun findMatchesNamesAcrossAThreeLevelTree() {
        val work = inFolder("find-name")
        seed(work, "tree/one.txt", "1")
        seed(work, "tree/two.dat", "2")
        seed(work, "tree/sub/three.txt", "3")
        seed(work, "tree/sub/deep/four.txt", "4")
        // Post-order, the way `find` prints: every entry after the ones inside it.
        assertEquals(
            "tree/one.txt\ntree/sub/deep/four.txt\ntree/sub/three.txt\n",
            run("find tree -name '*.txt'"),
        )
        assertEquals(
            "tree/one.txt\ntree/sub/deep/four.txt\ntree/sub/three.txt\ntree/two.dat\n",
            run("find tree -type f"),
        )
    }

    @Test
    fun findWithoutAStartPathSearchesTheWorkingDirectory() {
        val work = inFolder("find-cwd")
        seed(work, "a.txt", "a")
        seed(work, "sub/b.txt", "b")
        assertEquals("./a.txt\n./sub/b.txt\n", run("find . -type f"))
    }

    // ---- cp, mv, rm, mkdir, rmdir, touch, ln -------------------------------------------

    @Test
    fun cpCopiesTheBytesAndLeavesTheSourceInPlace() {
        val work = inFolder("cp-file")
        seed(work, "a.txt", "hello\n")
        h.reset()
        assertEquals(0, h.run("cp a.txt b.txt"))
        assertEquals("hello\n", run("cat b.txt"))
        assertEquals("hello\n", run("cat a.txt"))
        assertEquals("a.txt\nb.txt\n", run("ls -1 --color=never"))
    }

    @Test
    fun mvRenamesTheFileAndTakesTheOldNameWithIt() {
        val work = inFolder("mv-rename")
        seed(work, "a.txt", "hello\n")
        h.reset()
        assertEquals(0, h.run("mv a.txt renamed.txt"))
        h.reset()
        assertEquals(1, h.run("cat a.txt"))
        assertEquals("", h.stdout())
        assertEquals("cat: a.txt: No such file or directory\n", h.stderr())
        assertEquals("renamed.txt\n", run("ls -1 --color=never"))
        assertEquals("hello\n", run("cat renamed.txt"))
    }

    @Test
    fun rmRefusesADirectoryWithoutR() {
        val work = inFolder("rm-directory")
        seed(work, "dir/a.txt", "a")
        h.reset()
        val status = h.run("rm dir")
        assertEquals(1, status)
        assertEquals("", h.stdout())
        assertEquals("rm: dir: Is a directory\n", h.stderr())
        assertEquals("a.txt\n", run("ls -1 --color=never dir"))
    }

    @Test
    fun rmRemovesAWholeTreeWithR() {
        val work = inFolder("rm-tree")
        seed(work, "dir/a.txt", "a")
        seed(work, "dir/sub/b.txt", "b")
        h.reset()
        assertEquals(0, h.run("rm -r dir"))
        assertEquals("", h.stdout())
        assertEquals("", h.stderr())
        // A directory with nothing left in it lists as nothing at all, not as a blank line.
        assertEquals("", run("ls -1 --color=never"))
    }

    @Test
    fun mkdirPBuildsEveryLevelAndRmdirTakesOnlyEmptyOnes() {
        val work = inFolder("mkdir-rmdir")
        h.reset()
        assertEquals(0, h.run("mkdir -p a/b/c"))
        assertEquals("a/b/c\na/b\na\n", run("find a -type d"))
        // A second mkdir -p on the same path is the success it promises to be.
        h.reset()
        assertEquals(0, h.run("mkdir -p a/b/c"))
        assertEquals("", h.stderr())
        // Without -p an existing directory is an error.
        h.reset()
        assertEquals(1, h.run("mkdir a"))
        assertEquals("mkdir: a: File exists\n", h.stderr())
        // rmdir refuses a directory with anything in it.
        h.reset()
        assertEquals(1, h.run("rmdir a"))
        assertEquals("rmdir: a: Directory not empty\n", h.stderr())
        h.reset()
        assertEquals(0, h.run("rmdir a/b/c"))
        assertEquals("a/b\na\n", run("find a -type d"))
    }

    @Test
    fun touchCreatesAFileOfNoBytes() {
        val work = inFolder("touch-new")
        h.reset()
        assertEquals(0, h.run("touch new.txt"))
        assertEquals("", h.stdout())
        assertEquals("new.txt\n", run("ls -1 --color=never"))
        assertEquals("0 new.txt\n", run("wc -c new.txt"))
    }

    @Test
    fun lnDashSMakesASymlinkThatAListingShowsAsALink() {
        val work = inFolder("ln-symlink")
        seed(work, "a.txt", "hello\n")
        h.reset()
        assertEquals(0, h.run("ln -s a.txt link.txt"))
        // The link is a directory entry, so the listing is where its `l` and its target show up.
        // The size column is the length of the target string, which is the link's own size.
        val line = run("ls -l --color=never").lines().single { it.endsWith("link.txt -> a.txt") }
        val fields = line.split(WHITESPACE)
        assertEquals(7, fields.size)
        assertEquals("lrw-------", fields[0])
        assertEquals("5", fields[1])
        assertTrue(fields[2], TIMESTAMP.matches(fields[2] + " " + fields[3]))
        assertEquals(listOf("link.txt", "->", "a.txt"), fields.subList(4, 7))
        // `ln -s` makes a name and not a copy, and the target keeps its own bytes.
        val link = File(work, "link.txt")
        assertTrue("ln -s did not make a symlink", Files.isSymbolicLink(link.toPath()))
        assertEquals("a.txt", Files.readSymbolicLink(link.toPath()).toString())
        assertEquals("hello\n", run("cat link.txt"))
    }

    @Test
    fun readlinkDashFPrintsThePathItResolvesTo() {
        val work = inFolder("readlink-f")
        seed(work, "a.txt", "hello\n")
        h.reset()
        assertEquals(0, h.run("ln -s a.txt link.txt"))
        assertEquals(File(work, "a.txt").path + "\n", run("readlink -f link.txt"))
    }

    // ---- redirection and pipes --------------------------------------------------------

    @Test
    fun redirectionTruncatesThenAppendsAndPipesReadTheFileBack() {
        val work = inFolder("redirection")
        val f = File(work, "f")
        h.reset()
        assertEquals(0, h.run("echo hi > f"))
        assertEquals("hi\n", f.readText())
        assertEquals("hi\n", run("cat f"))
        h.reset()
        assertEquals(0, h.run("echo more >> f"))
        assertEquals("hi\nmore\n", f.readText())
        assertEquals("hi\nmore\n", run("cat f"))
        assertEquals("2\n", run("cat f | wc -l"))
    }

    @Test
    fun aFailingReadWritesNothingToStdout() {
        val work = inFolder("redirection-failure")
        seed(work, "f", "hi\n")
        h.reset()
        val status = h.run("cat f missing")
        assertEquals(1, status)
        // The readable operand is still read in full; the missing one only reaches stderr.
        assertEquals("hi\n", h.stdout())
        assertEquals("cat: missing: No such file or directory\n", h.stderr())
    }

    // ---- symbolic links ------------------------------------------------------------------

    /** A tree with one file and one subdirectory, for the link cases to act on. */
    private fun linked(name: String): File {
        val work = inFolder(name)
        seed(work, "target.txt", "target bytes\n")
        seed(work, "dir/inner.txt", "inner\n")
        return work
    }

    @Test
    fun rmUnlinksTheLinkAndLeavesTheTargetAlone() {
        val work = linked("rm-link")
        h.reset()
        assertEquals(0, h.run("ln -s target.txt link.txt"))
        assertEquals(0, h.run("rm link.txt"))
        assertEquals("", h.stdout())
        assertEquals("", h.stderr())
        // The name is gone and the file it named is untouched. This is the whole reason the path
        // resolver is lexical: a resolved `rm link` deleted the target, which loses a file.
        assertFalse(Files.exists(File(work, "link.txt").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("target bytes\n", File(work, "target.txt").readText())
    }

    @Test
    fun rmDashROnALinkToADirectoryTakesTheLinkAndLeavesTheTree() {
        val work = linked("rm-r-link-dir")
        h.reset()
        h.run("ln -s dir dlink")
        // The tree behind the link is not the link's to delete, with or without -r.
        assertEquals(0, h.run("rm -r dlink"))
        assertEquals("inner\n", File(work, "dir/inner.txt").readText())
        assertFalse(Files.exists(File(work, "dlink").toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun rmRemovesADanglingLinkBecauseItIsStillAName() {
        val work = linked("rm-dangling")
        h.reset()
        h.run("ln -s nowhere dangling")
        assertEquals(0, h.run("rm dangling"))
        assertEquals("", h.stderr())
        assertFalse(Files.exists(File(work, "dangling").toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun mvMovesTheLinkAndNotTheFileBehindIt() {
        val work = linked("mv-link")
        h.reset()
        h.run("ln -s target.txt link.txt")
        assertEquals(0, h.run("mv link.txt moved.txt"))
        assertTrue(Files.isSymbolicLink(File(work, "moved.txt").toPath()))
        assertEquals("target.txt", Files.readSymbolicLink(File(work, "moved.txt").toPath()).toString())
        assertEquals("target bytes\n", File(work, "target.txt").readText())
        // A directory destination takes the basename, and the link is still the thing that moves.
        assertEquals(0, h.run("mv moved.txt dir/"))
        assertTrue(Files.isSymbolicLink(File(work, "dir/moved.txt").toPath()))
        assertEquals("target bytes\n", File(work, "target.txt").readText())
    }

    @Test
    fun lsDashLShowsTheLinkWithItsOwnSizeAndItsTarget() {
        val work = linked("ls-l-link")
        h.reset()
        h.run("ln -s target.txt link.txt")
        val fields = run("ls -l --color=never link.txt").trim().split(WHITESPACE)
        assertEquals("lrw-------", fields[0])
        // The size is the length of the name the link points at, not the file's seven bytes.
        assertEquals("10", fields[1])
        assertEquals(listOf("link.txt", "->", "target.txt"), fields.subList(4, 7))
        assertTrue(Files.isSymbolicLink(File(work, "link.txt").toPath()))
    }

    @Test
    fun statReadlinkAndRealpathEachAnswerTheirOwnQuestion() {
        val work = linked("stat-readlink-link")
        h.reset()
        h.run("ln -s target.txt link.txt")
        val recorded = run("stat link.txt")
        assertTrue(recorded, recorded.contains("Type: symbolic link"))
        assertTrue(recorded, recorded.contains("  Link: target.txt"))
        // Without -f the target verbatim, with -f and in `realpath` the path it resolves to.
        assertEquals("target.txt\n", run("readlink link.txt"))
        assertEquals(File(work, "target.txt").path + "\n", run("readlink -f link.txt"))
        assertEquals(File(work, "target.txt").path + "\n", run("realpath link.txt"))
    }

    @Test
    fun readersFollowTheLinkAndTestAsksWhatItLeadsTo() {
        val work = linked("read-through-link")
        h.reset()
        h.run("ln -s target.txt link.txt")
        h.run("ln -s dir dlink")
        h.run("ln -s nowhere dangling")
        assertEquals("target bytes\n", run("cat link.txt"))
        assertEquals("target bytes\n", run("head -1 link.txt"))
        // `test` is about the object a path reaches, so a link to a directory is a directory.
        assertEquals(0, h.run("test -d dlink"))
        assertEquals(0, h.run("test -e link.txt"))
        assertEquals(1, h.run("test -e dangling"))
        // A link that leads nowhere is a clean diagnostic for anything that has to open it.
        h.reset()
        assertEquals(1, h.run("cat dangling"))
        assertEquals("", h.stdout())
        assertEquals("cat: dangling: No such file or directory\n", h.stderr())
    }

    @Test
    fun cdIntoALinkKeepsTheLogicalPathSoDotDotIsTheNameTheUserWrote() {
        val work = linked("cd-link")
        h.reset()
        h.run("ln -s dir dlink")
        assertEquals(0, h.run("cd dlink"))
        // $PWD is the name that was typed, not the directory behind it, so the prompt and `cd ..`
        // both mean what the user wrote.
        assertEquals(File(work, "dlink").path, run("pwd").trim())
        assertEquals(0, h.run("cd .."))
        assertEquals(work.path, run("pwd").trim())
        assertEquals("inner\n", run("cat dir/inner.txt"))
    }

    @Test
    fun rmdirRefusesALinkBecauseALinkIsNotADirectory() {
        val work = linked("rmdir-link")
        h.reset()
        h.run("ln -s dir dlink")
        assertEquals(1, h.run("rmdir dlink"))
        assertEquals("rmdir: dlink: Not a directory\n", h.stderr())
        // The target is untouched: rmdir removes the name, and this name is not a directory.
        assertEquals("inner\n", File(work, "dir/inner.txt").readText())
    }

    // ---- globbing ----------------------------------------------------------------------

    @Test
    fun aGlobExpandsToTheMatchingNamesOnly() {
        val work = inFolder("glob-list")
        seed(work, "a1b.txt", "1")
        seed(work, "a2b.txt", "2")
        seed(work, "ab.txt", "3")
        seed(work, "c.dat", "4")
        seed(work, ".hidden", "5")
        assertEquals("a1b.txt\na2b.txt\nab.txt\n", run("ls -1 --color=never *.txt"))
        assertEquals("a1b.txt a2b.txt ab.txt\n", run("echo *.txt"))
        // A leading dot has to be asked for: a pattern that does not start with one never matches a
        // hidden name, which is the one thing a glob and `ls` disagree about.
        assertEquals("a1b.txt a2b.txt ab.txt c.dat\n", run("echo *"))
    }

    // ---- text tools on real files ------------------------------------------------------

    @Test
    fun theTextToolsReadTheSeededFile() {
        val work = inFolder("text-tools")
        seed(work, "lines.txt", "one\ntwo\nthree\n")
        assertEquals("3 lines.txt\n", run("wc -l lines.txt"))
        assertEquals("one\ntwo\n", run("head -2 lines.txt"))
        assertEquals("three\n", run("tail -1 lines.txt"))
    }

    @Test
    fun sortOrdersAFileAndUniqCollapsesAdjacentRepeats() {
        val work = inFolder("text-sort-uniq")
        seed(work, "names.txt", "pear\napple\nfig\n")
        assertEquals("apple\nfig\npear\n", run("sort names.txt"))
        seed(work, "runs.txt", "a\na\nb\na\n")
        assertEquals("a\nb\na\n", run("uniq runs.txt"))
        // The count is padded to seven columns and separated by a tab, as the tool prints it.
        assertEquals("      2\ta\n      1\tb\n      1\ta\n", run("uniq -c runs.txt"))
    }

    @Test
    fun cutTakesTheSecondFieldOfACommaSeparatedFile() {
        val work = inFolder("text-cut")
        seed(work, "rows.csv", "id,name\n1,alice\n2,bob\n")
        assertEquals("name\nalice\nbob\n", run("cut -d, -f2 rows.csv"))
    }

    // ---- zip and unzip -----------------------------------------------------------------

    @Test
    fun zipThenUnzipGivesBackTheSameBytes() {
        val work = inFolder("zip-round-trip")
        seed(work, "tree/a.txt", "alpha\n")
        seed(work, "tree/sub/b.txt", "beta\n")
        // A directory is stored as an entry whose name ends in the separator and carries no bytes.
        assertEquals(
            "  adding: tree/\n  adding: tree/a.txt\n  adding: tree/sub/\n  adding: tree/sub/b.txt\n",
            run("zip -r bundle.zip tree"),
        )
        assertTrue(File(work, "bundle.zip").isFile)
        // Extracting reports the files only; the directory entries become directories silently.
        assertEquals(
            "  inflating: tree/a.txt\n  inflating: tree/sub/b.txt\n",
            run("unzip -d out bundle.zip"),
        )
        assertEquals("alpha\n", run("cat out/tree/a.txt"))
        assertEquals("beta\n", run("cat out/tree/sub/b.txt"))
    }

    // ---- truncate and md5sum -----------------------------------------------------------

    @Test
    fun truncateSetsTheSizeTheOtherToolsReport() {
        val work = inFolder("truncate-size")
        seed(work, "hello.txt", "hello\n")
        h.reset()
        assertEquals(0, h.run("truncate -s 32 payload.dat"))
        assertEquals("32 payload.dat\n", run("wc -c payload.dat"))
        assertEquals("  Size: 32", run("stat payload.dat").lines()[1])
        // -s 0 is coreutils' way of removing the file.
        h.reset()
        assertEquals(0, h.run("truncate -s 0 payload.dat"))
        assertEquals("hello.txt\n", run("ls -1 --color=never"))
    }

    @Test
    fun md5sumPrintsTheKnownDigestOfAFileAndOfStdin() {
        val work = inFolder("md5sum")
        // The digest below is what `printf 'hello\n' | md5sum` prints on the build machine, run
        // rather than invented: a test that repeats the implementation's own arithmetic proves
        // nothing about the digest.
        seed(work, "hello.txt", "hello\n")
        h.reset()
        assertEquals(0, h.run("md5sum hello.txt"))
        // `<hex>  <name>` with two spaces.
        assertEquals("b1946ac92492d2347c6235b4d2611184  hello.txt\n", h.stdout())
        assertEquals("", h.stderr())
        // The same digest, reached through a pipe, where the operand is `-`.
        assertEquals("b1946ac92492d2347c6235b4d2611184  -\n", run("cat hello.txt | md5sum"))
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val TIMESTAMP = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")
        val TIMESTAMP_SECONDS = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
    }
}
