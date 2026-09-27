package omp.agent.store

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * The key store, judged by `java.io.File`.
 *
 * Every assertion here is about bytes on a real disk rather than about what a method returned: the
 * one property that matters is where the secret is, who can reach it, and who cannot — and none of
 * those is observable through a return value. The arrangement mirrors the phone: the app's private
 * `files/` next to the `Documents/omp` container, because the whole point is that a key in the
 * second would be readable by any app holding the all-files grant.
 */
class KeyStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var appFiles: File
    private lateinit var documents: File
    private lateinit var ompDir: File
    private lateinit var store: KeyStore
    private var clock: Long = 0

    /** Distinctive enough that a substring search for it in a message cannot pass by accident. */
    private val secret = "sk-omp-7Qf2Xc9Vb4Lw0ZrTk"

    @Before
    fun setUp() {
        appFiles = folder.newFolder("files")
        documents = folder.newFolder("Documents")
        ompDir = File(documents, "omp").apply { mkdirs() }
        store = KeyStore(appFiles) { clock }
        clock = 1_757_000_000_000L
    }

    // ---- the round trip -----------------------------------------------------------------

    @Test
    fun putThenGetIsTheSecretAndNothingElse() {
        store.put("anthropic", secret)
        assertEquals(secret, store.get("anthropic"))
        assertTrue(store.has("anthropic"))
        // Byte for byte: a key is opaque, and trimming or case-folding one is a class of bug that
        // only ever shows up as a 401 from a provider.
        val onDisk = File(appFiles, "${KeyStore.SUBDIR}/anthropic${KeyStore.EXT}")
        assertTrue(onDisk.path, onDisk.isFile)
        assertArrayEquals(secret.toByteArray(Charsets.UTF_8), onDisk.readBytes())
    }

    @Test
    fun getAndHasAnswerForAProviderThatHasNoKey() {
        assertNull(store.get("openai"))
        assertFalse(store.has("openai"))
        assertEquals(emptyList<String>(), store.providers())
    }

    @Test
    fun twoProvidersSitSideBySideAndForgetTakesExactlyOne() {
        store.put("anthropic", secret)
        store.put("openai", "sk-openai-000111222333")

        assertEquals(secret, store.get("anthropic"))
        assertEquals("sk-openai-000111222333", store.get("openai"))
        assertEquals(listOf("anthropic", "openai"), store.providers())

        assertTrue(store.forget("openai"))
        assertNull(store.get("openai"))
        assertFalse(store.has("openai"))
        // The one that was not named is untouched, which is the whole content of "exactly one".
        assertEquals(secret, store.get("anthropic"))
        assertTrue(File(appFiles, "${KeyStore.SUBDIR}/anthropic${KeyStore.EXT}").isFile)
        // And forgetting again is not a second removal of something that is already gone.
        assertFalse(store.forget("openai"))
        assertEquals(listOf("anthropic"), store.providers())
    }

    @Test
    fun providersComeBackSortedWhateverOrderTheyWentIn() {
        store.put("zulu", "z")
        store.put("alpha", "a")
        store.put("mike", "m")
        assertEquals(listOf("alpha", "mike", "zulu"), store.providers())
    }

    @Test
    fun aKeyIsStampedWithTheInjectedClock() {
        store.put("anthropic", secret)
        val file = File(appFiles, "${KeyStore.SUBDIR}/anthropic${KeyStore.EXT}")
        // Set after the write, so the stamp is the clock rather than the filesystem's idea of now.
        assertEquals(clock, file.lastModified())
    }

    @Test
    fun forgetAlsoRemovesAScratchFileAWriteLeftBehind() {
        store.put("anthropic", secret)
        val scratch = File(appFiles, "${KeyStore.SUBDIR}/anthropic${KeyStore.EXT}${KeyStore.SCRATCH_EXT}")
        scratch.writeText("half a key", Charsets.UTF_8)
        assertTrue(store.forget("anthropic"))
        assertFalse(scratch.path, scratch.exists())
        assertEquals(emptyList<String>(), store.providers())
    }

    // ---- where it is, and how narrow ------------------------------------------------------

    @Test
    fun theKeyFileIsCreatedSixHundred() {
        store.put("anthropic", secret)
        val dir = File(appFiles, KeyStore.SUBDIR)
        val file = File(dir, "anthropic${KeyStore.EXT}")
        assertEquals(
            "the key file must be 0600: ${posix(file)}",
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            posix(file),
        )
        // The directory holding it is 0700 for the same reason, and it is the thing that makes the
        // listing impossible: a file name is a fact about the key.
        assertEquals(
            "the key directory must be 0700: ${posix(dir)}",
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            posix(dir),
        )
    }

    @Test
    fun theKeyIsUnderAppFilesAndNotWhereTheAgentMayLook() {
        store.put("anthropic", secret)
        val where = File(store.pathOf("anthropic")).canonicalFile
        assertTrue(
            "$where is not under ${appFiles.canonicalFile}",
            where.path.startsWith(appFiles.canonicalFile.path + File.separator),
        )
        // The container the agent is allowed to see. A key here would be readable by every app with
        // the all-files grant, and readable by a USB cable.
        assertTrue(where.path, !where.path.startsWith(ompDir.canonicalFile.path))
        assertFalse(File(ompDir, "agent/anthropic${KeyStore.EXT}").exists())
        // Nothing at all is written loose next to the key: the private directory is one level down
        // and holds only this app's agent state.
        assertEquals(
            listOf("anthropic${KeyStore.EXT}"),
            File(appFiles, KeyStore.SUBDIR).list()!!.sorted(),
        )
    }

    // ---- the refusals --------------------------------------------------------------------

    @Test
    fun aSecretWithANewlineIsRefusedAndNothingIsWritten() {
        val pasted = "sk-first\nsk-second"
        val message = refusal { store.put("anthropic", pasted) }
        assertTrue(message, message.contains("anthropic"))
        assertTrue(message, message.contains("newline"))
        assertEquals(emptyList<String>(), store.providers())
        assertFalse(File(appFiles, KeyStore.SUBDIR).exists())
    }

    @Test
    fun aSecretWithAnyControlCharacterIsRefused() {
        val message = refusal { store.put("anthropic", "sk-ok\u0007-not") }
        assertTrue(message, message.contains("U+0007"))
        assertEquals(emptyList<String>(), store.providers())
    }

    @Test
    fun anEmptyOrBlankKeyIsRefused() {
        val empty = refusal { store.put("anthropic", "") }
        assertTrue(empty, empty.contains("empty line"))
        val blank = refusal { store.put("anthropic", "   ") }
        assertTrue(blank, blank.contains("whitespace"))
    }

    @Test
    fun anOverlongKeyIsRefusedWithTheLimitInTheMessage() {
        val huge = "k".repeat(KeyStore.MAX_SECRET_BYTES + 1)
        val message = refusal { store.put("anthropic", huge) }
        assertTrue(message, message.contains("${KeyStore.MAX_SECRET_BYTES}"))
        assertTrue(message, message.contains("anthropic"))
        assertEquals(emptyList<String>(), store.providers())
    }

    @Test
    fun aKeyExactlyAtTheLimitIsAccepted() {
        val exact = "k".repeat(KeyStore.MAX_SECRET_BYTES)
        store.put("anthropic", exact)
        assertEquals(exact, store.get("anthropic"))
    }

    @Test
    fun aProviderNameThatIsNotAPlainNameIsRefused() {
        // A name that would climb out of the private directory, and one that merely needs tidying.
        // Both are refused rather than sanitised into a different file, because the same string is
        // typed to `get` and a name that changes between the two is a key that reads as absent.
        val climb = refusal { store.put("../escape", secret) }
        assertTrue(climb, climb.contains("not a plain provider name"))
        assertTrue(climb, climb.contains("escape"))
        val spaced = refusal { store.put("open ai", secret) }
        assertTrue(spaced, spaced.contains("open-ai"))
        val blank = refusal { store.put("  ", secret) }
        assertTrue(blank, blank.contains("provider name"))
        assertEquals(emptyList<String>(), store.providers())
    }

    @Test
    fun aBadProviderNameIsRefusedOnTheWayOutToo() {
        // Otherwise `get("../escape")` and `has("../escape")` would be a second convention for the
        // same question, and would name a file the write path has no way of writing.
        assertTrue(refusal { store.get("../escape") }.contains("not a plain provider name"))
        assertTrue(refusal { store.has("open ai") }.contains("not a plain provider name"))
        assertTrue(refusal { store.pathOf("..") }.contains("not a name at all"))
    }

    // ---- what a diagnostic may say ---------------------------------------------------------

    @Test
    fun noDiagnosticAnywhereCarriesTheSecret() {
        store.put("anthropic", secret)
        val seen = StringBuilder()
        seen.appendLine(store.toString())
        seen.appendLine(store.pathOf("anthropic"))
        seen.appendLine(store.providers().toString())
        // Every refusal too: the messages are the ones most likely to end up in a bug report, and
        // an error that quotes the input is an error that leaks the credential into a paste.
        seen.appendLine(refusal { store.put("anthropic", secret + "\nmore") })
        seen.appendLine(refusal { store.put("anthropic", "") })
        seen.appendLine(refusal { store.put("anthropic", "k".repeat(KeyStore.MAX_SECRET_BYTES + 1)) })
        seen.appendLine(refusal { store.put("bad name", secret) })

        assertFalse(seen.toString(), seen.contains(secret))
        // Not a prefix, and not the length either: a truncated key is a credential that has leaked.
        val head = secret.take(6)
        assertFalse("a six-character prefix of the key is in a diagnostic", seen.contains(head))
        assertFalse(
            "the provider's file name carries the key",
            File(store.pathOf("anthropic")).name.contains(head),
        )
        // What it must say instead: which provider, and which file.
        assertTrue(seen.toString(), seen.contains("anthropic"))
        assertTrue(seen.toString(), seen.contains("anthropic${KeyStore.EXT}"))
    }

    // ---- the helpers ---------------------------------------------------------------------

    /** The message a refusal carries, or a failure saying none came. */
    private fun refusal(block: () -> Unit): String = try {
        block()
        fail("expected a refusal")
        throw AssertionError("unreachable")
    } catch (e: IllegalArgumentException) {
        assertNotNull(e.message)
        e.message ?: ""
    }

    /**
     * The POSIX mode of [file], or a failure that says the test cannot answer its own question.
     *
     * A `catch` that quietly returned an empty set here would let this suite pass on a filesystem
     * with no modes on it, which is the one machine where the 0600 claim in the KDoc is untested.
     */
    private fun posix(file: File): Set<PosixFilePermission> = try {
        Files.getPosixFilePermissions(file.toPath())
    } catch (e: UnsupportedOperationException) {
        fail(
            "the temporary filesystem holding ${file.path} reports no POSIX permissions, so the " +
                "0600/0700 claim this test exists to check cannot be verified here",
        )
        throw AssertionError("unreachable")
    }
}
