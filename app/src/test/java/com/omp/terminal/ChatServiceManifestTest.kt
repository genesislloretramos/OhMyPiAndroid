package com.omp.terminal

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The manifest and the service must agree about the foreground service type, and nothing else can
 * check it without a device.
 *
 * **This is the whole of the failure this file exists for.** Android 14 refuses to start a
 * foreground service whose `startForeground` type is not among the types its manifest declares, and
 * it does so by killing the app during launch with a stack trace about a type. There is no compile
 * error, no lint warning and no other JVM test that catches a mismatch, because the two live in an
 * XML file and a method that never see each other. A reader "tidying" the manifest back to
 * `dataSync` without touching [ChatService.foregroundType] would produce a build that passes
 * everything and an app that dies on open.
 *
 * The subtype is here for the same reason. `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` is mandatory for a
 * `specialUse` service on Android 14 and a service without one is refused the same way — and
 * "present" is a low bar: an empty value is present and still refused, and a phrase like "user
 * experience" is present and says nothing. So the value is checked for being a sentence about this
 * app, not merely for being there.
 *
 * **The manifest is parsed, not grepped.** An earlier version of this file read it as text and
 * matched the first `<service` in the file, which turned out to be the phrase "see the `<service>`
 * below" in a comment. A check that passes on a comment is not a check.
 */
class ChatServiceManifestTest {

    private val manifest: File = File("src/main/AndroidManifest.xml")

    private val document by lazy {
        assertTrue(
            "the manifest was not found at ${manifest.absolutePath}, so every test here would be " +
                "passing on nothing",
            manifest.isFile,
        )
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
    }

    // ---- the type, in the two places it has to agree --------------------------------------------

    @Test
    fun theDeclaredTypeIsTheOneTheServiceSaysItIs() {
        assertEquals(
            "the manifest declares a different android:foregroundServiceType from " +
                "ChatService.FOREGROUND_TYPE; Android 14 kills the app if the two disagree",
            ChatService.FOREGROUND_TYPE,
            attribute("android:foregroundServiceType"),
        )
    }

    @Test
    fun theTypePassedToStartForegroundIsTheSpecialUseBit() {
        assertEquals(
            "the bit handed to startForeground is not FOREGROUND_SERVICE_TYPE_SPECIAL_USE",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            ChatService.foregroundType(34),
        )
    }

    @Test
    fun theWordAndTheBitAreTheSameType() {
        // Each of the two above holds on its own; this is the one that says the word in the
        // manifest names the constant in the code. A build that replaced the bit with the dataSync
        // one would keep both of them green, because neither looks at the other — and dataSync is
        // capped at about six hours a day, which would stop this server for most of every day.
        val bit = ChatService.foregroundType(34)
        assertTrue(
            "ChatService.FOREGROUND_TYPE is '${ChatService.FOREGROUND_TYPE}' and the bit is $bit; " +
                "those are not the same type",
            (ChatService.FOREGROUND_TYPE == "specialUse") ==
                (bit == ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE),
        )
    }

    @Test
    fun theTypeIsSpecialUseAndNotDataSync() {
        assertFalse(
            "dataSync is capped at about six hours in a rolling day and then stopped, which is " +
                "the opposite of a server that is meant to be up whenever the app is open",
            attribute("android:foregroundServiceType") == "dataSync",
        )
    }

    // ---- the subtype, which is the justification a reviewer would ask for -------------------

    @Test
    fun theSpecialUseSubtypeIsPresent() {
        assertTrue(
            "a specialUse service with no PROPERTY_SPECIAL_USE_FGS_SUBTYPE is refused on " +
                "Android 14, exactly as one with the wrong type is",
            property("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE") != null,
        )
    }

    @Test
    fun theSpecialUseSubtypeSaysWhatTheServiceIsFor() {
        val value = property("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE")!!.trim()
        assertTrue("an empty subtype is present and still refused", value.isNotEmpty())
        assertTrue(
            "the subtype has to be a sentence about this service, and '$value' is not one",
            value.length >= MIN_SUBTYPE && value.contains(' '),
        )
        assertFalse(
            "the subtype has to name the work; 'user experience' is the phrase this project does " +
                "not use to justify anything",
            value.contains("user experience", ignoreCase = true),
        )
    }

    // ---- the permissions that type needs, and the one it does not -----------------------------

    @Test
    fun theDataSyncPermissionIsGoneWithTheDataSyncType() {
        assertTrue(
            "FOREGROUND_SERVICE is what every foreground service needs and the type still requires it",
            hasPermission("android.permission.FOREGROUND_SERVICE"),
        )
        assertFalse(
            "FOREGROUND_SERVICE_DATA_SYNC was only ever here for the dataSync type, and the type " +
                "is specialUse; leaving it asks for a capability nothing in this app uses",
            hasPermission("android.permission.FOREGROUND_SERVICE_DATA_SYNC"),
        )
    }

    // ---- and the service is still not a door anyone else can walk through ---------------------

    @Test
    fun theServiceIsNotExported() {
        // The only door into this server is a browser on this device, over loopback, with the
        // install's own token. An exported service would be a second door.
        assertEquals(".ChatService", attribute("android:name"))
        assertEquals("false", attribute("android:exported"))
    }

    // ---- the reader --------------------------------------------------------------------------

    private fun service(): Element {
        val nodes = document.getElementsByTagName("service")
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            if (element.getAttribute("android:name") == ".ChatService") return element
        }
        throw AssertionError("the manifest declares no <service android:name=\".ChatService\">")
    }

    private fun attribute(name: String): String? {
        val value = service().getAttribute(name)
        return if (value.isEmpty()) null else value
    }

    private fun property(name: String): String? {
        val nodes = service().getElementsByTagName("property")
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            if (element.getAttribute("android:name") == name) return element.getAttribute("android:value")
        }
        return null
    }

    private fun hasPermission(name: String): Boolean {
        val nodes = document.getElementsByTagName("uses-permission")
        for (i in 0 until nodes.length) {
            if ((nodes.item(i) as Element).getAttribute("android:name") == name) return true
        }
        return false
    }

    private companion object {
        /** Long enough that a bare word cannot pass as a justification. */
        const val MIN_SUBTYPE = 20
    }
}
