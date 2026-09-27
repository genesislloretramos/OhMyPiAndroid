package com.omp.terminal.web

import omp.vm.provision.Abi
import omp.vm.provision.ArtifactManifest
import omp.vm.provision.Phase
import omp.vm.provision.Progress
import omp.vm.provision.ProvisionOutcome
import omp.vm.provision.ProvisionStatus
import omp.vm.provision.ProvisionStatusHolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one fact a provisioning run publishes, and the line both surfaces show out of it.
 *
 * **The values, not the shape.** Every assertion here is about what a notification or a state route
 * would put in front of a person: the phase, two byte counts, and the fact that nothing at all is
 * shown between runs. A test that only checked the object had the right fields would pass against a
 * holder that published a percentage and a number nobody had measured.
 *
 * There is no `android.*` here and no socket, which is the point: the class is in `:core` so that
 * the shell which writes it and the app which reads it can be in two modules, and this file is the
 * proof that the reading half needs a JVM and nothing else.
 */
class ProvisionStatusTest {

    @After
    fun tearDown() {
        ProvisionStatusHolder.clear()
    }

    @Test
    fun aDeviceThatHasNeverRunThisHasNothingToShowAndNoOutcome() {
        ProvisionStatusHolder.clear()

        val idle = ProvisionStatusHolder.current
        assertFalse(idle.running)
        assertNull("there is no line for a notification between runs", idle.line())
        assertNull(idle.outcome)
        assertEquals(0L, idle.receivedBytes)
        assertEquals(0L, idle.totalBytes)
    }

    @Test
    fun aRunThatHasJustStartedIsRunningWithNoPhaseAndNoBytes() {
        ProvisionStatusHolder.begin(Abi.ARM64.abiName)

        val begun = ProvisionStatusHolder.current
        assertTrue(begun.running)
        assertEquals("arm64-v8a", begun.abi)
        assertNull(begun.phase)
        assertNull("no artifact is named yet", begun.artifact)
        // There is a line — a run has started and a user may be looking at a notification — but it
        // makes no claim about bytes, because none have moved.
        assertEquals("provisioning: starting", begun.line())
    }

    @Test
    fun theLineNamesThePhaseAndBothByteCountsInTheManifestsOwnSpelling() {
        ProvisionStatusHolder.begin(Abi.ARM64.abiName)
        ProvisionStatusHolder.progress(
            Abi.ARM64.abiName,
            Progress("omp-linux-arm64", Phase.DOWNLOADING, 12_582_912L, 234_866_984L),
        )

        val status = ProvisionStatusHolder.current
        assertEquals(Phase.DOWNLOADING, status.phase)
        assertEquals("omp-linux-arm64", status.artifact)
        assertEquals(12_582_912L, status.receivedBytes)
        assertEquals(234_866_984L, status.totalBytes)
        // The exact figures, spelled the way the terminal spells them, so a number read off the
        // notification and a number read off the screen are one number and not two roundings.
        assertEquals(
            "provisioning: downloading omp-linux-arm64 — 12.0 MiB (12,582,912 bytes) of " +
                "224.0 MiB (234,866,984 bytes)",
            status.line(),
        )
        // No percentage, ever: a percentage needs a denominator, and this is the only one measured.
        assertFalse(status.line()!!.contains('%'))
        assertTrue(status.line()!!.contains(ArtifactManifest.humanBytes(234_866_984L)))
    }

    @Test
    fun aPhaseThatIsNotADownloadIsNamedForWhatItIsDoingAndNotForWhatItIsCalled() {
        // `Phase.INSTALLED` is the step the downloader enters *before* the rename that installs the
        // binary, so the line says "installing": a notification that said "installed" a moment
        // before it was would be the one kind of progress line that is wrong.
        val spoken = mapOf(
            Phase.DOWNLOADING to "downloading",
            Phase.VERIFYING to "verifying",
            Phase.UNPACKING to "unpacking",
            Phase.INSTALLED to "installing",
        )
        for ((phase, word) in spoken) {
            ProvisionStatusHolder.begin(Abi.ARM64.abiName)
            ProvisionStatusHolder.progress(
                Abi.ARM64.abiName,
                Progress("omp-linux-arm64", phase, 0L, 234_866_984L),
            )

            val line = ProvisionStatusHolder.current.line()
            assertTrue("$phase printed as $line", line!!.startsWith("provisioning: $word omp-linux-arm64"))
        }
    }

    @Test
    fun finishingKeepsWhatTheRunWasDoingAndStopsItBeingARun() {
        ProvisionStatusHolder.begin(Abi.ARM64.abiName)
        ProvisionStatusHolder.progress(
            Abi.ARM64.abiName,
            Progress("omp-linux-arm64", Phase.DOWNLOADING, 12_582_912L, 234_866_984L),
        )

        ProvisionStatusHolder.finish(ProvisionOutcome.CANCELLED)

        val ended = ProvisionStatusHolder.current
        assertFalse("a finished run is not a running one", ended.running)
        assertNull("and has nothing for a notification to show", ended.line())
        // The outcome and the artifact are kept: a state route shows how the run went and what it
        // was doing when it stopped, and a bare word would be a worse answer than both.
        assertEquals(ProvisionOutcome.CANCELLED, ended.outcome)
        assertEquals("omp-linux-arm64", ended.artifact)
        assertEquals(12_582_912L, ended.receivedBytes)
        assertEquals(234_866_984L, ended.totalBytes)
    }

    @Test
    fun aRunThatWasRefusedBeforeItDownloadedAnythingIsStillAnOutcomeAndStillNoLine() {
        ProvisionStatusHolder.begin(Abi.ARMEABI_V7A.abiName)

        ProvisionStatusHolder.finish(ProvisionOutcome.REFUSED)

        val refused = ProvisionStatusHolder.current
        assertEquals(ProvisionOutcome.REFUSED, refused.outcome)
        assertEquals("armeabi-v7a", refused.abi)
        assertNull("a refusal that downloaded nothing has no bar", refused.line())
    }

    @Test
    fun aSecondRunReplacesTheFirstRatherThanQueueingBehindIt() {
        ProvisionStatusHolder.begin(Abi.ARM64.abiName)
        ProvisionStatusHolder.progress(
            Abi.ARM64.abiName,
            Progress("debian-trixie-rootfs-arm64", Phase.DOWNLOADING, 1L, 2L),
        )
        ProvisionStatusHolder.begin(Abi.ARM64.abiName)
        ProvisionStatusHolder.progress(
            Abi.ARM64.abiName,
            Progress("omp-linux-arm64", Phase.DOWNLOADING, 3L, 4L),
        )

        val current = ProvisionStatusHolder.current
        assertEquals("the run a surface is watching is the one that is current", "omp-linux-arm64", current.artifact)
        assertEquals(3L, current.receivedBytes)
        assertEquals(4L, current.totalBytes)
        assertNull("a run in progress has no outcome yet", current.outcome)
    }

    @Test
    fun aStatusBuiltByHandPrintsTheSameSentenceTheHolderWouldHavePrinted() {
        // The data and the sentence are separate on purpose: the state route publishes the fields
        // and the notification publishes this line, so a [ProvisionStatus] whose line did not agree
        // with the values it was built from would be a page and a notification telling one person
        // two different things about one download.
        val hand = ProvisionStatus(
            running = true,
            abi = "x86_64",
            phase = Phase.UNPACKING,
            artifact = "debian-trixie-rootfs-amd64",
            receivedBytes = 0L,
            totalBytes = 55_459_886L,
            outcome = null,
        )

        // The byte counts stay on the line whatever the phase is: an unpack of 55,459,886 bytes
        // is a fact a person watching wants as much as a download is.
        assertEquals(
            "provisioning: unpacking debian-trixie-rootfs-amd64 — 0 bytes of " +
                "52.9 MiB (55,459,886 bytes)",
            hand.line(),
        )
        assertNull(ProvisionStatus.IDLE.line())
    }
}
