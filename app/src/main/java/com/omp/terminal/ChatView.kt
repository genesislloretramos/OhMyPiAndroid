package com.omp.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import com.omp.terminal.web.UiOrigin

/**
 * Every decision the chat WebView is allowed to make, as data.
 *
 * **This is a data class and not a block of setters, for one reason: it can be checked.** A
 * `WebView` is an Android object, so a JVM test cannot build one, and a `webSettings` line buried
 * in an Activity's `onCreate` is a line no test ever sees. Written as a value it is a value, and
 * [ChatViewTest] reads every field of it without a device. The one thing this cannot check is that
 * [ChatView] applies all of it, and that is said plainly in the report rather than papered over.
 *
 * ### What is off, and why each one matters for a page from inside a Debian
 *
 * - **No JavaScript bridge. [javaScriptBridge] is `false` and nothing calls
 *     `addJavascriptInterface`.** A bridge is a door from page JavaScript into this app's own
 *   objects — files, the key store, the conversation folder — and the page is served by a guest
 *   the user has root-equivalent access to. The reason it is not "added when something needs it"
 *   is that it would be: a bridge is the obvious next thing to reach for and there is no way to
 *   add one later that is not a bridge to a guest. It is written here so the next reader finds
 *   the decision and not just the absence of the call.
 * - **[fileAccess] and [contentAccess] are off.** `file://` and `content://` reach outside the
 *   origin entirely, and a page that can read a `file:` URL can read this app's private storage,
 *   which is where the model key is.
 * - **The two `*FromFileURLs` are off.** They are the documented way a page escalates from one
 *   `file:` document to every other one, and they are off for the same reason.
 * - **[mixedContentNever] is on.** An `https` page inside an `http` one can be swapped in under
 *   it; here there is no `https` page, and there must not be one.
 * - **[multipleWindows] and [javaScriptCanOpenWindows] are off,** so `window.open` cannot put a
 *   second document in front of a user who thinks there is one.
 * - **[geolocationEnabled] is off.** A phone in a Debian should not be broadcasting where it is
 *   because a guest page asked nicely.
 * - **JavaScript is on, because a chat is JavaScript,** and that is the honest cost: this WebView
 *   runs code. The boundary is the network one — loopback, one port — not the scripting one.
 */
data class WebPolicy(
    val javaScriptEnabled: Boolean,
    val domStorageEnabled: Boolean,
    /** `WebSettings.setAllowFileAccess` — the one that opens `file://`. */
    val fileAccess: Boolean,
    /** `WebSettings.setAllowContentAccess` — the one that opens `content://`. */
    val contentAccess: Boolean,
    val allowFileAccessFromFileUrls: Boolean,
    val allowUniversalAccessFromFileUrls: Boolean,
    /** `WebSettings.MIXED_CONTENT_NEVER_ALLOW`. True means never allow. */
    val mixedContentNever: Boolean,
    /** Whether a JavaScript bridge may exist. It may not, and the test says so. */
    val javaScriptBridge: Boolean,
    val geolocationEnabled: Boolean,
    val multipleWindows: Boolean,
    val javaScriptCanOpenWindows: Boolean,
    val mediaPlaybackRequiresUserGesture: Boolean,
    /** `WebSettings.LOAD_NO_CACHE` — a conversation is never a thing to re-read from a cache. */
    val loadNoCache: Boolean,
) {

    companion object {
        /**
         * The one policy this app ships.
         *
         * The two Android constants are written as numbers with their names beside them because a
         * test cannot load `android.webkit.WebSettings` and an inlined literal can be read by one:
         * `MIXED_CONTENT_NEVER_ALLOW` is 0 and `LOAD_NO_CACHE` is 2.
         */
        val CHAT: WebPolicy = WebPolicy(
            javaScriptEnabled = true,
            domStorageEnabled = true,
            fileAccess = false,
            contentAccess = false,
            allowFileAccessFromFileUrls = false,
            allowUniversalAccessFromFileUrls = false,
            mixedContentNever = true,
            javaScriptBridge = false,
            geolocationEnabled = false,
            multipleWindows = false,
            javaScriptCanOpenWindows = false,
            mediaPlaybackRequiresUserGesture = true,
            loadNoCache = true,
        )

        /** `WebSettings.MIXED_CONTENT_NEVER_ALLOW`, which is 0. */
        const val MIXED_CONTENT_NEVER_ALLOW = 0

        /** `WebSettings.LOAD_NO_CACHE`, which is 2. */
        const val LOAD_NO_CACHE = 2
    }
}

/**
 * The chat, rendered on this phone.
 *
 * **It is a view inside [MainActivity] and not a second Activity, for one reason: there is one
 * service, one back button and one lifecycle to reason about, and a second Activity would give the
 * app two of each and make "which one is in front" a question the terminal and the chat would have
 * to answer differently.** It lives in the layout the terminal already uses, next to the terminal,
 * and exactly one of the two is visible at a time.
 *
 * ### It is a security boundary, and the boundary is the network
 *
 * The page comes out of a Debian. **A WebView renders whatever HTML that page sends, so the guest
 * can put anything on this screen** — anything it can express in HTML, CSS and JavaScript, on the
 * display the user is holding. That is inherent in the decision to serve the UI from inside a
 * guest and it is not mitigated here, because it cannot be. What *is* bounded is what the page can
 * reach: [WebPolicy.CHAT] turns off the JavaScript bridge, `file://` and `content://`, and mixed
 * content, and [UiOrigin.permits] refuses every URL that is not this origin's own scheme, host and
 * port — for subresources as well as for navigations, because a page that may only *navigate* to
 * its own origin can still exfiltrate through an `<img>`. The guest is on the user's own phone and
 * the user chose to put it there, which is what makes this a defensible boundary and not merely a
 * tolerated one.
 *
 * ### The token gets in through the URL and nowhere else
 *
 * [UiOrigin.url] carries it, the page exchanges it for a cookie, and the WebView adds nothing: no
 * header rewriting, no injected JavaScript, no `evaluateJavascript`. The guest's server does not
 * have to know anything about this app's token for that to work, which is the point — the API side
 * of the guest is a question that has not been answered and this half does not pre-empt it.
 */
class ChatView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val policy = WebPolicy.CHAT
    private var origin: UiOrigin? = null

    @SuppressLint("SetJavaScriptEnabled")
    val web: WebView = WebView(context).apply {
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true

        with(settings) {
            javaScriptEnabled = policy.javaScriptEnabled
            domStorageEnabled = policy.domStorageEnabled
            allowFileAccess = policy.fileAccess
            allowContentAccess = policy.contentAccess
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = policy.allowFileAccessFromFileUrls
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = policy.allowUniversalAccessFromFileUrls
            mixedContentMode =
                if (policy.mixedContentNever) WebPolicy.MIXED_CONTENT_NEVER_ALLOW
                else WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setGeolocationEnabled(policy.geolocationEnabled)
            setSupportMultipleWindows(policy.multipleWindows)
            javaScriptCanOpenWindowsAutomatically = policy.javaScriptCanOpenWindows
            mediaPlaybackRequiresUserGesture = policy.mediaPlaybackRequiresUserGesture
            cacheMode = if (policy.loadNoCache) WebPolicy.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        }

        // Not `addJavascriptInterface` under any name. See [WebPolicy.javaScriptBridge].
        webViewClient = object : WebViewClient() {

            /**
             * A navigation, refused unless it stays on the origin.
             *
             * **Refused rather than ignored.** Returning false would let the WebView load it, so
             * every off-origin navigation returns true and nothing happens; the page is left where
             * it is, which is a visible non-event rather than a silent different screen.
             */
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = !permits(request.url.toString())

            /**
             * A subresource, refused on the same rule.
             *
             * This is the one that stops exfiltration, and it is why [UiOrigin.permits] is a
             * predicate about a URL and not about a navigation. An empty 200 is returned rather
             * than null because null means "I have no opinion, carry on".
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = if (permits(request.url.toString())) {
                null
            } else {
                Blocked
            }
        }

        // A guest page that asks to download something gets a sentence rather than a file. The
        // silent version of this is a user who taps something and sees nothing happen.
        setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(context, "omp: this app does not download from the chat", Toast.LENGTH_SHORT)
                .show()
        }
    }

    init {
        addView(web)
    }

    /**
     * The URL this view will refuse to leave, for the Activity's own status line and for a report.
     */
    fun label(): String = origin?.label() ?: "not loaded"

    /**
     * Loads [where], once.
     *
     * **The origin is a parameter and is set here and nowhere else**, so that adopting the other
     * arrangement the user has not chosen yet — the UI on Apache and the API on this app, or the
     * reverse — is a change to the value passed in and not a change to this class.
     */
    fun load(where: UiOrigin) {
        origin = where
        web.loadUrl(where.url())
    }

    /**
     * Whether [url] may be fetched or navigated to right now.
     *
     * With no origin loaded, nothing is permitted: a WebView that has not been told where it is
     * has no business being on the network at all.
     */
    fun permits(url: String): Boolean = origin?.permits(url) ?: false

    /** Whether Back can be consumed by the page, which the Activity asks before it acts itself. */
    fun goBack(): Boolean {
        if (!web.canGoBack()) return false
        web.goBack()
        return true
    }

    /** Paused with the Activity, timers included, so a backgrounded chat costs nothing. */
    fun onPause() {
        web.onPause()
        web.pauseTimers()
    }

    fun onResume() {
        web.resumeTimers()
        web.onResume()
    }

    /** An empty, successful, empty-bodied reply, for a request the policy refused. */
    private object Blocked : WebResourceResponse(
        "text/plain",
        "utf-8",
        200,
        "OK",
        emptyMap(),
        java.io.ByteArrayInputStream(ByteArray(0)),
    )
}
