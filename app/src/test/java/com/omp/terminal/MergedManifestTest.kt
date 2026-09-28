package com.omp.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The permission a `specialUse` service needs, checked in the manifest the **device** reads.
 *
 * ### Why this file exists at all, when `ChatServiceManifestTest` already reads a manifest
 *
 * Because that one reads `src/main/AndroidManifest.xml` and **the device never reads that file.**
 * AGP merges the app manifest with every library's into
 * `build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml` and it is
 * that file which is packaged into the APK and read by the package manager. Every check against
 * the source manifest is a check that the line is still typed somewhere it happens to be, and
 * none of them is a check that the line survived.
 *
 * **This is the exact shape of the bug that shipped.** `FOREGROUND_SERVICE` and
 * `POST_NOTIFICATIONS` were in the source manifest and `FOREGROUND_SERVICE_SPECIAL_USE` was not,
 * so a build was green, an APK was produced, an install succeeded, and Android 14 threw a
 * `SecurityException` out of `ChatService.onCreate` on a real phone — an app that installed,
 * launched, and showed nothing. Deleting the line below from `app/src/main/AndroidManifest.xml`
 * must turn this file red; if it does not, this file is not reading what it says it reads, which
 * is the failure mode it exists to rule out. That is also why
 * [theReadManifestIsNotTheSourceManifest] is here: a test that quietly fell back to the source
 * file would pass forever and be worth nothing.
 *
 * ### Why it does not read the APK
 *
 * The packaged manifest is binary XML, and reading it means running `aapt2` — a build tool, from a
 * unit test, on a machine that may not have one. The merged manifest is the last text form of the
 * same document, the one AGP hands to the packager, and it is what every Android build tool in
 * this project reads when it wants to know what is in the APK. The APK is verified with
 * `aapt2 dump xmltree app/build/outputs/apk/debug/app-debug.apk --file AndroidManifest.xml`; this
 * test is the one that runs on every build without a device or a toolchain.
 */
class MergedManifestTest {

    /**
     * Every merged manifest this build has produced for a debug variant.
     *
     * **Both layouts are searched, and both are checked**, because the directory name has changed
     * under AGP (`merged_manifest`/`processDebugMainManifest` became `merged_manifests`/
     * `processDebugManifest`) and a test that pinned one spelling would go quietly blind on an
     * upgrade instead of going red.
     */
    private val merged: List<File> by lazy {
        val root = File("build/intermediates")
        val found = ArrayList<File>()
        if (root.isDirectory) {
            root.walkTopDown()
                .filter { it.isFile && it.name == "AndroidManifest.xml" }
                .filter { it.invariantSeparatorsPath.contains("/merged_manifest") }
                .filter { it.invariantSeparatorsPath.contains("/debug/") }
                .forEach { found.add(it) }
        }
        assertTrue(
            "no merged debug manifest was found under ${root.absolutePath}, so every assertion " +
                "here would be passing on nothing. The merged manifest is written by " +
                "processDebugMainManifest, which :app:testDebugUnitTest depends on; run " +
                ":app:assembleDebug if this test is being run on its own.",
            found.isNotEmpty(),
        )
        found
    }

    private fun document(file: File) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(file)

    // ---- the permission, in the file the package manager reads ---------------------------------

    @Test
    fun theSpecialUsePermissionIsInTheMergedManifest() {
        for (file in merged) {
            assertTrue(
                "${file.absolutePath} declares no " +
                    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE. Android 14 refuses " +
                    "startForeground for a specialUse service without it, throws out of " +
                    "ChatService.onCreate, and kills the app on launch.",
                hasPermission(file, ChatService.SPECIAL_USE_PERMISSION),
            )
        }
    }

    @Test
    fun theMergedManifestIsNotTheSourceManifest() {
        // The check above is only worth anything if the file it read is not the one a person
        // edits. This says so out loud, so a future edit that swaps in `src/main/AndroidManifest.xml`
        // — the obvious "simplification" — is a red test rather than a test that passes forever.
        val source = File("src/main/AndroidManifest.xml").absoluteFile
        for (file in merged) {
            assertFalse(
                "${file.absolutePath} is the source manifest; reading the source is how this bug " +
                    "shipped, because the source is not what the device reads",
                file.absoluteFile == source,
            )
            assertTrue(
                "${file.absolutePath} is not under build/, so it is not a build output",
                file.absolutePath.contains("${File.separator}build${File.separator}"),
            )
        }
    }

    @Test
    fun thePermissionIsForTheTypeTheMergedManifestActuallyDeclares() {
        // The permission is only the half of the contract that is easy to forget: it has to be the
        // permission for the type the packaged manifest asks for. A build that moved the type back
        // to dataSync would keep a specialUse permission and fail differently, so the two are read
        // out of the same file here rather than out of a constant and an XML file.
        for (file in merged) {
            assertEquals(
                "${file.absolutePath} declares a foreground service type that the " +
                    "SPECIAL_USE permission is not for",
                ChatService.FOREGROUND_TYPE,
                service(file).getAttribute("android:foregroundServiceType"),
            )
        }
    }

    @Test
    fun theGeneralForegroundPermissionIsStillThereBesideIt() {
        // The type permission is *in addition to* this one, not instead of it. A reader who
        // tidied the pair down to the type-specific line would break every launch on every
        // version, and the comment beside each line in the manifest says so for them.
        for (file in merged) {
            assertTrue(
                "${file.absolutePath} has lost android.permission.FOREGROUND_SERVICE, which " +
                    "every foreground service needs whatever its type",
                hasPermission(file, "android.permission.FOREGROUND_SERVICE"),
            )
        }
    }

    // ---- the reader --------------------------------------------------------------------------

    private fun hasPermission(file: File, name: String): Boolean {
        val nodes = document(file).getElementsByTagName("uses-permission")
        for (i in 0 until nodes.length) {
            if ((nodes.item(i) as Element).getAttribute("android:name") == name) return true
        }
        return false
    }

    /**
     * The `<service>` for this app, in either spelling.
     *
     * **The merger rewrites `.ChatService` into `com.omp.terminal.ChatService`**, which is the
     * third thing this file has caught that a source-only check cannot: the two documents are not
     * even textually the same. Both names are accepted, and the search is over parsed elements
     * rather than text because the merged file carries this project's own manifest comments along
     * with it — one of which contains the phrase "see the `<service>` below".
     */
    private fun service(file: File): Element {
        val nodes = document(file).getElementsByTagName("service")
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            val name = element.getAttribute("android:name")
            if (name == ".ChatService" || name == "com.omp.terminal.ChatService") return element
        }
        throw AssertionError("${file.absolutePath} declares no <service> for ChatService")
    }
}
