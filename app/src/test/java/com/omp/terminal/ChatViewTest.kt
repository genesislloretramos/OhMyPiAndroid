package com.omp.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WebView's policy, on the JVM with no WebView.
 *
 * **A `WebView` is an Android object and a JVM test cannot build one**, which is the whole reason
 * [WebPolicy] is a value rather than a block of setters in an Activity. Every decision that decides
 * what the page may reach is readable from here.
 *
 * **What is not covered is that [ChatView] applies all of it.** That is one call site per field and
 * it needs a device; it is named as untested in the report rather than pretended over.
 */
class ChatViewTest {

    // ---- the WebView policy ---------------------------------------------------------------------

    @Test
    fun thePolicyHasNoJavaScriptBridge() {
        // A bridge is a door from page JavaScript into this app's own objects, and the page comes
        // out of a Debian. There is no code path that adds one, and this fails if someone does.
        assertFalse(WebPolicy.CHAT.javaScriptBridge)
    }

    @Test
    fun thePolicyHasNoFileOrContentAccess() {
        // `file://` reaches this app's private storage, which is where the model key is.
        assertFalse(WebPolicy.CHAT.fileAccess)
        assertFalse(WebPolicy.CHAT.contentAccess)
    }

    @Test
    fun thePolicyHasNoFileUrlEscalation() {
        // The documented way a page goes from one file: document to every other one.
        assertFalse(WebPolicy.CHAT.allowFileAccessFromFileUrls)
        assertFalse(WebPolicy.CHAT.allowUniversalAccessFromFileUrls)
    }

    @Test
    fun thePolicyRefusesMixedContent() {
        assertTrue(WebPolicy.CHAT.mixedContentNever)
        assertEquals(0, WebPolicy.MIXED_CONTENT_NEVER_ALLOW)
    }

    @Test
    fun thePolicyKeepsJavaScriptAndSaysSo() {
        // A chat is JavaScript, and that is the honest cost. The boundary this app draws is the
        // network one, and pretending otherwise would be a sentence this project does not write.
        assertTrue(WebPolicy.CHAT.javaScriptEnabled)
        assertTrue(WebPolicy.CHAT.domStorageEnabled)
    }

    @Test
    fun thePolicyHasNoSecondWindowAndNoGeolocation() {
        // `window.open` must not put a second document in front of a user who thinks there is one,
        // and a phone inside a Debian should not be broadcasting where it is.
        assertFalse(WebPolicy.CHAT.multipleWindows)
        assertFalse(WebPolicy.CHAT.javaScriptCanOpenWindows)
        assertFalse(WebPolicy.CHAT.geolocationEnabled)
    }

    @Test
    fun thePolicyNeedsAGestureToPlayAnything() {
        assertTrue(WebPolicy.CHAT.mediaPlaybackRequiresUserGesture)
    }

}
