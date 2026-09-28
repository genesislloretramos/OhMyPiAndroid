package com.omp.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.omp.terminal.android.AndroidPlatformServices
import com.omp.terminal.web.AccessToken
import com.omp.terminal.web.AssetSource
import com.omp.terminal.web.ChatApi
import com.omp.terminal.web.LocalServer
import omp.vm.provision.ProvisionStatusHolder
import omp.vm.web.ChatServerStatusHolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The host. It owns the web server, and it owns nothing else.
 *
 * **The terminal is not this service and does not depend on it.** The Activity still starts its
 * shell, still draws its screen, and still runs `omp` on a real terminal with a real keypress at
 * an approval prompt. This service is the *other* door into the same agent: a browser, on this
 * phone, talking to a conversation folder in the user's own storage. Closing the Activity leaves it
 * running, which is the point of a foreground service; stopping it from the notification takes the
 * port back, which is the point of having a stop button.
 *
 * ### When the platform says no
 *
 * **This service is asked for, not promised.** The platform can refuse a foreground service — for
 * a type whose permission is missing from the manifest, or for an app that is not allowed to
 * start one at all — and [onCreate] treats that refusal as an answer rather than as a failure of
 * the app: the reason is recorded in [ChatServerStatusHolder], the service stops itself, and the
 * process carries on. `web` and `omp doctor` print that reason, so "the chat server is not
 * running" is followed by the sentence that says which of those two happened.
 *
 * **What that costs is the browser front end and nothing else**, because that is the only thing
 * behind this socket: the terminal, the shell thread, the guest and the conversations in
 * Documents/omp are all reachable with the server stopped, and the app opens either way. The
 * version of this that did not survive its own first launch — a `SecurityException` out of
 * [onCreate] with no catch — is the reason the guard is here and the reason
 * `MergedManifestTest` reads the merged manifest rather than this file, and that is the only file
 * on this bug that the device actually reads.
 *
 * ### What the notification says
 *
 * Two lines, and both of them are true. **What the service is for** — the agent, answering in a
 * browser on this phone — and **where the UI is**, which is the full URL including the token,
 * because a loopback URL nobody can open is not a URL. The token is in the notification on purpose
 * and it is a cost, not an oversight: the person who can read this notification is the person who
 * installed the app, and a server on loopback that refuses to identify itself is a server its
 * owner cannot open. The cost is written down on [com.omp.terminal.web.TokenGate], and it is the
 * same trade this app makes in `web` and nowhere else.
 *
 * ### The foreground service type, and why it is `specialUse`
 *
 * **`specialUse`, and not `dataSync`, because of the cap.** Android 14 allows a `dataSync`
 * foreground service roughly six hours in a rolling twenty-four and then stops it, with a
 * notification the user has to acknowledge. For a server that is meant to be up whenever the app
 * is open, that is a feature which is off for most of every day, and the requirement here is the
 * opposite of that. `specialUse` carries no such cap, and
 * [FOREGROUND_TYPE] is what both the manifest and [foregroundType] agree on —
 * `ChatServiceManifestTest` exists so the two cannot drift apart.
 *
 * **`specialUse` is a type a store review asks you to justify, and that cost is not paid here.**
 * The subtype string in the manifest is that justification, in the plainest terms available: a local
 * HTTP server on this device's loopback interface serving this app's own chat UI. This APK is
 * sideloaded and has never been submitted to a store, so nobody is asked to accept the claim — but
 * the question is a real one and pretending it does not exist would be the kind of sentence this
 * project does not write. `FOREGROUND_SERVICE_DATA_SYNC` is gone with the type that needed it.
 *
 * ### Why the port is a port and not a fixed one
 *
 * [LocalServer] is asked for [PREFERRED_PORT] and falls back to an ephemeral one if that is taken,
 * so two installs on one device — which happens with two profiles — do not collide, and the URL
 * that is printed is the URL that is actually listening. Whatever port it ends up on is released
 * by [LocalServer.stop] before [onDestroy] returns, so a stop and a start take the same one back
 * rather than failing on "address already in use".
 */
class ChatService : Service() {

    private var server: LocalServer? = null
    private var api: ChatApi? = null

    /** The bound URL, token included. Read by the watcher to re-post a notification with it. */
    private var url: String? = null

    /**
     * Whether the provisioning watcher is still wanted.
     *
     * `@Volatile` and a flag and not a handle on the [Thread], because the only thing that has to
     * happen on [onDestroy] is that the thread stops noticing, and interrupting a thread that is
     * inside `NotificationManager.notify` is a thing to do only if it has to be.
     */
    @Volatile
    private var watching = false

    /**
     * Whether the server has been brought up by this service instance.
     *
     * An [AtomicBoolean] and not a null check on [server] because the flag says what it means —
     * "this instance has done its one start" — and a nullable field has to be read and believed
     * from a method that is about something else.
     */
    private val started = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ChatServerStatusHolder.starting()
        val channel = notificationChannel()
        // The channel is made before the server is bound, not after: startForeground has to be the
        // first thing this service does, and a notification on a channel that does not exist yet is
        // a notification the system drops on the floor on some releases.
        val first = notification(channel, "starting the server…", null)
        // Everything the platform can refuse to this service is inside this one `try`, and nothing
        // inside it is allowed out. An uncaught exception in `Service.onCreate` takes the process
        // with it, and the symptom of that is an app that installs, launches and shows nothing,
        // with a stack trace nobody reading it owns — which is exactly what a missing
        // FOREGROUND_SERVICE_SPECIAL_USE did to a shipped build of this app. The terminal does not
        // need this service, so a refusal has to cost the browser front end and nothing else.
        //
        // The second catch is `IllegalStateException` and not `ForegroundServiceStartNotAllowedException`
        // by name, on purpose: that class only exists from API 31, and a catch clause naming a
        // class that is not on an older device's boot class path fails verification on exactly the
        // devices this app still supports. It is the class the platform throws when the app is not
        // allowed to start a foreground service at all, and the reason below carries the class's
        // own name so that nothing is lost by not naming the type here.
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                // The type is not decoration on Android 14: a foreground service that declares one
                // in its manifest and not in this call is refused, and so is one whose type has no
                // permission beside it. `ChatServiceManifestTest` is what keeps the first honest
                // against the merged manifest.
                startForeground(NOTIFICATION_ID, first, foregroundType(Build.VERSION.SDK_INT))
            } else {
                startForeground(NOTIFICATION_ID, first)
            }
        } catch (e: SecurityException) {
            refuse("SecurityException: ${e.message}")
        } catch (e: IllegalStateException) {
            refuse("ForegroundServiceStartNotAllowedException: ${e.message}")
        }
    }

    /**
     * The platform would not let this service run in the foreground, and that is now a state with
     * a reason rather than a crash.
     *
     * **Three things, in this order, and none of them is throwing.** The reason goes to
     * [ChatServerStatusHolder], which is where `web`, the Activity and `omp doctor` read it from;
     * it goes to the log, because a device this old is somebody's spare phone and the log is the
     * only copy; and the service stops itself, which is what the platform's own documentation says
     * to do after a refused `startForeground` — the service was started as a foreground service,
     * so the one way to be a well-behaved instance of that is to go away rather than sit there
     * without the notification it promised.
     *
     * **The cost is the browser front end and nothing else.** `web` and `omp doctor` say the
     * server is not running and print this sentence; the shell, the guest and the conversations
     * are untouched, because none of them is behind this socket.
     */
    private fun refuse(reason: String) {
        ChatServerStatusHolder.refused(reason)
        android.util.Log.w(TAG, "the chat server is not running: $reason")
        stopSelf()
    }

    /**
     * Starts the server, and answers an intent the system re-delivered with no action in it.
     *
     * `START_STICKY` because a phone under memory pressure kills this service for no reason a user
     * made, and a host that is "always in the background" is not one that needs re-launching by
     * hand. A stop the *user* asked for calls [stopSelf], and a stopped service is not restarted,
     * sticky or not.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // The Activity asks for this on every launch, including a launch where the service is
        // already up, and a rotation asks again a moment later. `started` is what makes those
        // extra asks no-ops rather than a second bind, a second notification and a second port:
        // one compareAndSet decides, and everything after it is the first ask's work. The flag is
        // cleared in onDestroy, because a Service instance is one lifetime and a fresh process
        // gets a fresh instance with a fresh, false flag.
        if (started.compareAndSet(false, true)) {
            if (startServer(notificationChannel()) == null) {
                started.set(false)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    /**
     * @return null and a sentence logged when the server could not be started at all. Failing
     *   quietly would leave a notification claiming a URL that nothing is listening on, which is
     *   the one thing this service must never do.
     */
    private fun startServer(channel: String): String? {
        val services = AndroidPlatformServices(applicationContext)
        val home = servicesDir()
        if (!home.isDirectory && !home.mkdirs()) {
            return "$home could not be made, so there is nowhere to keep the token"
        }
        val token = try {
            AccessToken(File(home, TOKEN_FILE)).load()
        } catch (e: IOException) {
            return "the token file could not be read or written: ${e.message}"
        }
        val assets = object : AssetSource {
            override fun read(name: String): ByteArray? = try {
                applicationContext.assets.open("web/$name").use { it.readBytes() }
            } catch (e: IOException) {
                null
            }
        }
        val built = ChatApi(services, assets, token, versionName())
        val routes = built.routes()
        val listener = try {
            LocalServer(routes, wantedPort = PREFERRED_PORT)
        } catch (e: IOException) {
            // The preferred port is taken — a second profile, or a stale one. Any free port is a
            // better answer than no server, as long as the URL printed is the one that is up.
            try {
                LocalServer(routes, wantedPort = 0)
            } catch (second: IOException) {
                return "no loopback port could be bound: ${second.message}"
            }
        }
        listener.start()
        server = listener
        api = built
        url = listener.url() + LOGIN_PATH + "?t=" + token
        // The URL is written down rather than recomputed by whoever asks. The port is a preference
        // and the OS may have given a different one, and a `web` command that printed the
        // preference would be printing something that might not be listening.
        try {
            urlFile(applicationContext.filesDir).writeText(url!! + "\n", Charsets.UTF_8)
        } catch (e: IOException) {
            android.util.Log.w(TAG, "the url could not be written down: ${e.message}")
        }
        android.util.Log.i(TAG, "chat server on $url")
        // A refusal, if there was one, is over: the server is up and the state says so, so a
        // device that failed once and then worked is not still reporting the first failure.
        ChatServerStatusHolder.bound(url!!)
        show(channel, null)
        watchProvisioning()
        return url
    }

    /**
     * Posts the notification, with whatever `omp provision` is doing in it.
     *
     * **[provision] is null whenever no run is going**, and the notification is then exactly the
     * two lines it has always been — a run that has finished leaves no trace here, because the
     * terminal already said in full how it ended and a persistent notification that keeps saying
     * "provisioning: FAILED" is a second, staler copy of a sentence the user has read.
     */
    private fun show(channel: String, provision: String?) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(channel, url!!, provision))
    }

    /**
     * Puts a run started in a terminal on this notification, and takes it off again when it ends.
     *
     * **A poll, once a second, because the run cannot call here.** `omp provision` runs inside the
     * shell's own foreground job and `:core` may not import `android.*`, so the only way the two
     * halves of this app meet about a download is a field both can read:
     * [omp.vm.provision.ProvisionStatusHolder]. This is the same shape as the agent's own Ctrl-C
     * watchdog, which asks the shell's flag once a second for the same reason — a thread parked in
     * somebody else's read cannot be pushed to.
     *
     * **The notification is only re-posted when the line changes.** A run reports every 64 KiB, and
     * a notification re-posted on every one of those would be several updates a second for minutes
     * on a channel the user cannot silence; once a second, and only on a change, is what a
     * notification is for.
     */
    private fun watchProvisioning() {
        val thread = Thread({
            val channel = notificationChannel()
            // The loop is handed its reader, its publisher and its ticker rather than closing over
            // them, so a JVM test can make any one of the three throw and watch the loop end
            // instead of the process. Nothing is allowed to escape that `run` — an uncaught
            // exception in any thread ends the app, and this one is not worth the terminal.
            val reason = ProvisionWatcher(
                watching = { watching },
                nextLine = { ProvisionStatusHolder.current.line() },
                publish = { show(channel, it) },
                sleep = { Thread.sleep(WATCH_MS) },
            ).run()
            if (reason != null) android.util.Log.w(TAG, reason)
        }, "omp-provision-watch")
        thread.isDaemon = true
        watching = true
        thread.start()
    }

    override fun onDestroy() {
        // The order matters and is the reverse of starting: the agent's turns are released first,
        // so a model that is waiting on a question this service owns is not left waiting after the
        // socket it would have been answered on is closed. The watcher goes with them — a thread
        // left posting notifications for a service that no longer exists is a leak with a badge.
        watching = false
        api?.close()
        server?.stop()
        api = null
        server = null
        started.set(false)
        // The URL goes with it: a file naming a port that is no longer bound is a claim this app
        // would not make, and `web` and `omp doctor` both read that file to decide whether the
        // service is running. One `forgetUrl`, which is the same function that names the file the
        // bind writes it — the two used to be spelled separately, the deleter with one `web` too
        // many, and the file outlived the server that had written it.
        forgetUrl(applicationContext.filesDir)
        // `stopped` and not `clear`: a service the platform refused stops itself in the same
        // breath, and erasing the reason on the way out would throw away the only record of it
        // that this process will ever have.
        ChatServerStatusHolder.stopped()
        super.onDestroy()
    }

    // ---- the notification ---------------------------------------------------------------------

    /**
     * The persistent notification. Two lines about the agent, and a progress line when a download
     * is going.
     *
     * `setOngoing` and no dismiss button, because a service that is running is not something a
     * swipe should end — [ACTION_STOP] is the way, and it is on the notification so that stopping
     * it takes one tap and no app.
     *
     * **[provision] replaces the title and the first line rather than being appended to them.** A
     * 334 MB download is the more urgent fact about this app for the minutes it lasts, and a
     * notification whose title still says "the agent is here" above a progress line is one a user
     * has to read twice. The URL stays in the expanded text throughout, because a run that ends has
     * to leave the notification exactly as it found it and a browser opened mid-download must
     * still be able to find the page.
     *
     * **No new foreground-service type is asked for.** The type is [FOREGROUND_TYPE], and it
     * covers a local server and the work this app does on its own behalf; a second type for the
     * same service would be a manifest line and a `startForeground` argument that have to agree
     * with each other on every future platform, for a notification that is a re-post of one this
     * service already shows.
     */
    private fun notification(channel: String, url: String, provision: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            pendingFlags(),
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ChatService::class.java).setAction(ACTION_STOP),
            pendingFlags(),
        )
        val big = buildString {
            append("The coding agent in this app, answering in a browser on this phone.\n")
            append("Conversations are plain folders under Documents/omp.\n\n")
            append("Open: ").append(url).append("\n\n")
            append(
                "The token is in that address on purpose: it is what stops every other app " +
                    "on this phone from reading your conversations. Anyone who has this " +
                    "notification can open it too.",
            )
            if (provision != null) {
                append("\n\n").append(provision)
                append(
                    "\n\nStarted with 'omp provision' in a terminal. Ctrl-C there stops it and " +
                        "keeps what has arrived, and nothing is downloaded without being agreed to.",
                )
            }
        }
        val title = getString(R.string.app_name) +
            if (provision == null) " — the agent is here" else " — provisioning"
        val text = provision ?: "Ask the agent in a browser on this phone. Open: $url"
        return Notification.Builder(this, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun notificationChannel(): String {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL,
            "omp chat server",
            NotificationManager.IMPORTANCE_LOW,
        )
        // Low importance, and no sound: this notification is never news, it is the price of a
        // foreground service, and a sound every time it updated would train a user to swipe it.
        channel.setShowBadge(false)
        channel.enableVibration(false)
        manager.createNotificationChannel(channel)
        return CHANNEL
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    /** Where the token and the URL are kept, inside the app's own private files. */
    private fun servicesDir(): File = webDir(applicationContext.filesDir)

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }

    companion object {
        private const val TAG = "omp"
        private const val CHANNEL = "omp.chat"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.omp.terminal.chat.STOP"
        private const val WEB_DIR = "web"
        private const val TOKEN_FILE = "token"
        private const val URL_FILE = "url"
        private const val LOGIN_PATH = "/login"

        /**
         * The three paths this service's private files live at, named in one place.
         *
         * **One function per file, and the writer and the deleter use the same one**, because the
         * bug this replaces was two spellings of the same path in one class: the bind wrote
         * `filesDir/web/url` and the teardown deleted `filesDir/web/web/url`, so the URL survived
         * the server and `omp web` went on naming a port that nothing was listening on. A path
         * spelled once cannot drift from itself, and a JVM test can check the spelling against
         * the convention the shell reads it by — `appFilesDir()/web/url`, which is what
         * `omp.vm.doctor.Doctor.URL_FILE` and `WebCommand` both use.
         *
         * They take the app's **files** directory and not the services directory, so the `web`
         * component is added here and nowhere else; `servicesDir()` is the same thing.
         */
        fun webDir(filesDir: File): File = File(filesDir, WEB_DIR)

        /** The published URL: `filesDir/web/url`, as `omp web` and `omp doctor` read it. */
        fun urlFile(filesDir: File): File = File(webDir(filesDir), URL_FILE)

        /** The token: `filesDir/web/token`, which is a different file and is never deleted. */
        fun tokenFile(filesDir: File): File = File(webDir(filesDir), TOKEN_FILE)

        /**
         * Takes the published URL back off the disk, and is called from [onDestroy].
         *
         * **It returns whether there was one**, which is a fact about the filesystem and not
         * about the server: a service that never bound a port has nothing to take back, and a
         * caller that wanted to know the difference can have it.
         */
        fun forgetUrl(filesDir: File): Boolean = urlFile(filesDir).delete()

        /**
         * How often the watcher looks at the provisioning status, in milliseconds.
         *
         * A second, and for the same reason the agent's own Ctrl-C watchdog is a second: it is
         * asked rather than pushed to, because the run that writes the status is inside somebody
         * else's read and nothing here can reach it. A provisioning run reports every 64 KiB, so
         * this is also what keeps the notification at one update a second instead of sixty.
         */
        private const val WATCH_MS = 1000L

        /** The path the printed URL points at: the one page that answers without a token. */
        const val LOGIN = LOGIN_PATH

        /** The address every socket in the app binds, named here so `web` prints the real one. */
        const val LOOPBACK = LocalServer.LOOPBACK

        /**
         * The word in `android:foregroundServiceType`.
         *
         * It is a constant rather than a string typed twice because the manifest cannot read a
         * Kotlin value, and two independent spellings of a foreground service type is a pair that
         * will eventually disagree — at which point Android 14 kills the app on launch with a stack
         * trace about a type. `ChatServiceManifestTest` compares this against the manifest and
         * against [foregroundType].
         */
        const val FOREGROUND_TYPE = "specialUse"

        /**
         * The permission Android 14 checks a `specialUse` start against, named here for the same
         * reason [FOREGROUND_TYPE] is: the manifest cannot read a Kotlin value, so a constant and
         * a test are the only way to hold the two spellings together.
         *
         * **It is a permission in its own right and not a spelling of `FOREGROUND_SERVICE`.** The
         * type-specific one is checked *in addition to* the general one when `targetSdk` is 34 or
         * above, and leaving it out is not a build error or a lint warning — it is a `SecurityException`
         * thrown out of [onCreate] on a device with Android 14 on it, which is how the last release
         * of this app installed, launched and showed nothing. `MergedManifestTest` reads the merged
         * manifest for it, because that is the file the device reads.
         */
        const val SPECIAL_USE_PERMISSION = "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"

        /**
         * The type bit passed to `startForeground`, for an API level.
         *
         * **A parameter and not [Build.VERSION.SDK_INT]**, so that
         * [ChatServiceManifestTest] can ask for 34 on a JVM and check the number the app really
         * hands the platform, rather than check that a comment mentions the right word. It is in
         * the companion so that asking does not mean constructing a [Service], which a JVM cannot
         * do.
         *
         * Zero is the pre-29 "types do not exist yet" value. `ServiceInfo`'s constants are
         * compile-time constants, so naming the API-34 one on an older device is an inlined integer
         * that platform ignores.
         */
        fun foregroundType(sdk: Int): Int =
            if (sdk >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

        /**
         * 8731, in the region IANA calls dynamic/private. It is a preference and not a promise: if
         * it is taken the server takes an ephemeral port instead and prints that one.
         */
        const val PREFERRED_PORT = 8731

        /** Starts the host. Safe to call on every Activity start; the service decides. */
        fun start(context: Context) {
            val intent = Intent(context, ChatService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * The URL this service last bound, token included, or null before the first start.
         *
         * **Read from the file and not from a field**, because a second Activity on its own does
         * not share a service instance's memory in a way it can rely on, and because the file is
         * also what `web` reads. One fact, written once, asked for by whoever needs it.
         */
        fun publishedUrl(context: Context): String? =
            urlFile(context.filesDir).readPublished()

        /**
         * This install's token, or null before the first start.
         *
         * **Not minted here.** A second mint is a second token, and whichever one a page happened
         * to be handed would be the one that worked while the other silently did not.
         */
        fun publishedToken(context: Context): String? =
            tokenFile(context.filesDir).readPublished()

        /**
         * Stops the host, the way the notification's Stop action does.
         *
         * **`stopService` and not `startService` with an action, which is what this used to be.**
         * The one caller is `omp web stop`, which runs on the shell thread and can run with the
         * Activity closed or the screen off — and from API 26 an app in the background may not
         * start a service at all, so the old spelling threw `IllegalStateException` out of a
         * command a user had every right to run. Branching to `startForegroundService` instead
         * would trade that for a worse fault: it *starts* a service, so a `web stop` against a
         * server that is not running would light up a notification and a foreground service for
         * a stop, and on API 31+ a background start is refused outright.
         *
         * **Asking the platform to stop is the third option, and it is the one the platform made
         * for this**: the running instance is told to go away and tears itself down through the
         * same [onDestroy] any other stop reaches, with no exemption needed because nothing is
         * being started. It is a no-op, and returns false, on a service that was not running —
         * which is the honest answer to `web stop` twice.
         */
        fun stop(context: Context): Boolean =
            context.stopService(
                Intent(context, ChatService::class.java).setAction(ACTION_STOP),
            )

        /**
         * What a published file says, or null when there is no file, it is empty, or it cannot be
         * read — a file this app wrote and cannot read is not a fact to hand out.
         */
        private fun File.readPublished(): String? = try {
            takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: IOException) {
            android.util.Log.w(TAG, "$name could not be read: ${e.message}")
            null
        }
    }
}
