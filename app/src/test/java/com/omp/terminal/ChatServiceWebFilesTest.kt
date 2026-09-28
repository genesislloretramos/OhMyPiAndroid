package com.omp.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A stopped server leaves no address behind.
 *
 * ### What was wrong
 *
 * The bind wrote the published URL to `filesDir/web/url` and the teardown deleted
 * `filesDir/web/web/url` — one `web` too many in a second spelling of a path that was already
 * spelled a third time in `publishedUrl`. The delete found nothing, the file outlived the server,
 * and everything downstream read a port that nothing was listening on: `omp web` naming it, and
 * `omp doctor` reporting a port and a `listening: no` for an address the app had already
 * withdrawn. A stale address presented as a live one is the same kind of lie as printing the
 * preferred port instead of the bound one, and it is fixed the same way: the path is named once
 * and the writer and the deleter are the same function.
 *
 * ### Why the tests are about the path and not about a `Service`
 *
 * A JVM cannot construct a `Service`, so there is no way to run `onDestroy` here. What can be
 * checked — and is what actually broke — is that the file the bind writes and the file the
 * teardown takes back are one file, at the path the shell reads it by. So the assertions below are
 * written in the shell's own spelling, `appFilesDir()/web/url`, and the service's helper has to
 * agree with it.
 */
class ChatServiceWebFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val files: File by lazy { folder.newFolder("files") }

    @Test
    fun theUrlIsWrittenWhereTheShellLooksForIt() {
        // `omp web` and `omp doctor` both spell it this way, and neither of them can call into this
        // class: `WebCommand` builds `appFilesDir()/web/url` and `omp.vm.doctor.Doctor.URL_FILE` is
        // the `web/url` relative to the same directory.
        val written = File(files, "web/url")
        ChatService.urlFile(files).parentFile.mkdirs()
        ChatService.urlFile(files).writeText("http://127.0.0.1:8731/login?t=a-token\n")

        assertEquals(
            "the service publishes the url somewhere other than where the shell reads it",
            written.path,
            ChatService.urlFile(files).path,
        )
        assertNotEquals(
            "and not one directory deeper, which is where the teardown used to look",
            File(files, "web/web/url").path,
            ChatService.urlFile(files).path,
        )
    }

    @Test
    fun aStoppedServerLeavesNoUrlBehind() {
        val web = ChatService.webDir(files)
        web.mkdirs()
        ChatService.urlFile(files).writeText("http://127.0.0.1:8731/login?t=a-token\n")
        assertTrue("the fixture has to be there for this to mean anything", ChatService.urlFile(files).isFile)

        assertTrue("there was a url to take back", ChatService.forgetUrl(files))

        assertFalse(
            "a url file that outlives the server is an address `omp web` and the app's own origin " +
                "picker will both go on handing out, and nothing is listening on it",
            File(files, "web/url").isFile,
        )
    }

    @Test
    fun takingTheUrlBackIsIdempotentBecauseAStopCanArriveTwice() {
        assertFalse("there was nothing there", ChatService.forgetUrl(files))
        ChatService.urlFile(files).parentFile.mkdirs()
        ChatService.urlFile(files).writeText("http://127.0.0.1:8731/login?t=a-token\n")
        assertTrue(ChatService.forgetUrl(files))
        assertFalse("and nothing is left to report the second time", ChatService.forgetUrl(files))
    }

    @Test
    fun theTokenIsNotTheUrlAndIsNotThrownAwayWithIt() {
        // The token is minted on the first start and outlives every stop — it is the credential
        // that stops other apps on the phone reading the conversations, so a teardown that took it
        // with the url would hand out a new one and lock a browser out of a page already open.
        ChatService.tokenFile(files).parentFile.mkdirs()
        ChatService.urlFile(files).writeText("http://127.0.0.1:8731/login?t=a-token\n")
        ChatService.tokenFile(files).writeText("a-token")

        ChatService.forgetUrl(files)

        assertFalse("the url is gone", ChatService.urlFile(files).isFile)
        assertTrue("and the token is still on the disk it was minted on", ChatService.tokenFile(files).isFile)
    }
}
