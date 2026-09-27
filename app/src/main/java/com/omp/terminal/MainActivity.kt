package com.omp.terminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import com.omp.terminal.android.AndroidCommands
import com.omp.terminal.android.AndroidPlatformServices
import com.omp.terminal.vm.GuestRuntime
import com.omp.terminal.web.GuestOrigin
import com.omp.terminal.web.UiOrigins
import omp.shell.InputChannel
import omp.shell.Session
import omp.shell.SessionHost
import omp.shell.ShellSession
import omp.term.Screen
import java.io.OutputStream

/**
 * The single Activity. It owns the shell thread and the session; the terminal view only ever reads
 * a snapshot of the screen, so there is exactly one writer and one reader.
 */
class MainActivity : Activity(), ExtraKeysView.Listener, SessionHost {

    /**
     * The session the user is talking to: the phone's own until `vm enter` says otherwise.
     *
     * One deep, and that is the whole stack. The VM's REPL runs on this same thread over this same
     * [Screen] and [InputChannel], so while it is up the Back button and the repaint have to
     * follow *it* rather than the session underneath. The pushed session is what a
     * [SessionHost] call hands us; null means the phone's shell is in front, which is both the
     * start state and the state every pop returns to.
     *
     * A nullable field rather than `var active = shell.session`: [shell] is a `lateinit` assigned
     * in `onCreate`, and a property initializer that reads it would run before that and throw on
     * every launch. Volatile because the shell thread writes it and the main thread reads it.
     */
    @Volatile
    private var pushed: Session? = null

    private val active: Session get() = pushed ?: shell.session

    override fun sessionPushed(session: Session) {
        pushed = session
    }

    override fun sessionPopped(session: Session) {
        pushed = null
    }

    private lateinit var screen: Screen
    private lateinit var input: InputChannel
    private lateinit var shell: ShellSession
    private lateinit var terminal: TerminalView
    private lateinit var chat: ChatView
    private lateinit var extraKeys: ExtraKeysView
    private lateinit var imeInput: EditText
    private lateinit var services: AndroidPlatformServices

    private val ui = Handler(Looper.getMainLooper())
    private var shellThread: Thread? = null
    private var armed = Modifier.NONE
    private var backPressedAt = 0L

    /**
     * Whether the chat's origin has been decided for this Activity.
     *
     * **A plain `Boolean` on the main thread and not a nullable field, because "not decided" and
     * "decided to be nothing" are different answers** and the second one is reached by setting this
     * before the null check. The frame loop is the only writer besides [decideUiOrigin] itself, and
     * it is on the same thread as both.
     */
    private var originDecided = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        services = AndroidPlatformServices(applicationContext, this)
        AndroidCommands.register(omp.shell.exec.CommandTable.global)

        // The host is started here and is not tied to this Activity's life. The terminal below is
        // unchanged either way: closing the window leaves the server running, which is the point
        // of it being a foreground service, and stopping it from the notification takes the port
        // back without touching the shell. `start` is idempotent, so a rotation asks for nothing
        // that is not already running.
        ChatService.start(this)
        askForNotificationPermission()

        val cellHeight = services.prefInt(PREF_CELL_HEIGHT, 0)
        val scrollback = services.prefInt(PREF_SCROLLBACK, DEFAULT_SCROLLBACK)
        val bell = { flashStatus("bell") }
        screen = Screen(24, 80, scrollback, bell)

        setContentView(R.layout.activity_main)
        // After setContentView: the insets controller only exists once the decor view does.
        goImmersive()
        terminal = findViewById(R.id.terminalView)
        chat = findViewById(R.id.chatView)
        extraKeys = findViewById(R.id.extraKeys)
        imeInput = findViewById(R.id.imeInput)

        terminal.installScaling()
        terminal.attach(
            screen,
            if (cellHeight > 0) cellHeight else defaultCellHeightPx(),
            services.prefBoolean(PREF_COPY_ON_SELECT, true),
        )
        terminal.onCopy = { text ->
            copyToClipboard(text)
            flashStatus("copied ${text.length} chars")
        }
        extraKeys.listener = this

        screen.onClipboard = { copyToClipboard(it) }
        screen.onOutput = { bytes -> input.feed(bytes) }

        input = InputChannel()
        shell = ShellSession(services, screen, input)
        // The root session is also the session the host hands back when a nested one pops, so it is
        // what `active` starts as and what the double-tap-to-exit is measured against.
        shell.session.host = this
        services.titleListener = { title -> ui.post { window.setTitle(title) } }

        wireIme()

        // The guest, started here and not tied to this Activity's life. It is idempotent — a
        // rotation asks again and the second ask is a no-op, because the first run's Apache is
        // holding the port and a second would be a collision with itself — and it publishes one
        // report, which is what the frame loop below waits for before deciding anything.
        //
        // After the shell, not before: `decideUiOrigin` reads the session's own filesystem, and a
        // `lateinit` read before its assignment is a crash on every launch rather than a chat.
        GuestRuntime.start(services, guestLog)

        flashStatus("starting the Debian…")

        ui.postDelayed(poll, FRAME_MS)
        // Only after the terminal view has a size: the screen is resized to the real grid there,
        // and anything the shell wrote before that would be laid out for the wrong width.
        terminal.onFirstLayout = {
            shellThread = Thread({ shell.run() }, "omp-shell").apply {
                isDaemon = true
                start()
            }
        }
    }

    // ---- the chat ------------------------------------------------------------------------

    /**
     * Whether the origin has been decided yet, and whether it can be now.
     *
     * **The wait is on the guest's start, not on its being up.**
     * [com.omp.terminal.vm.GuestRuntime] resolves in a millisecond on a device with no Debian and in
     * seconds on one with a guest, and either way the answer afterwards is final: [UiOrigins.choose]
     * is a pure function of the disk, the token, the published URL and one boolean, so re-asking it
     * later with the same four would return the same thing. Waiting is what makes it happen
     * **once**, and once is the property worth having: a WebView that swapped origins under a user
     * mid-sentence would be a worse bug than either origin.
     */
    private fun originDue(): Boolean = !originDecided && GuestRuntime.resolved

    /**
     * Which origin serves the chat, decided once, from the disk and from one boolean.
     *
     * **The WebView is loaded in every case and shown only when the guest is what is serving.**
     * The origin is [UiOrigins.choose], which is [com.omp.terminal.web.UiOrigin.choose] over
     * `omp.vm.provision.ProvisionState` *and* over the fact that a request for this build's own chat
     * document came back from the guest's port; a page cannot reach this method, cannot cause it to
     * be called twice, and cannot affect what it returns. A device with no guest shows the terminal,
     * which is what a user who has provisioned nothing expects.
     *
     * **A guest that comes up while the user is in the terminal takes the screen, and Back gives it
     * straight back.** The shell is untouched either way — the same [omp.shell.Session] keeps
     * running and any command in it keeps running — so the switch costs a glance and not a session,
     * and [onBackPressed] gives the terminal back without finishing anything. The alternative,
     * refusing to switch while a job is in flight, would leave a user with a guest serving a page and
     * no way to reach it.
     *
     * Null happens too: before the service has bound a port there is no address to load, and the
     * terminal is shown with a toast saying so rather than an empty screen.
     */
    private fun decideUiOrigin() {
        originDecided = true
        val origin = UiOrigins.forDevice(this, services, shell.session.vfs)
        if (origin == null) {
            flashStatus("the chat server has not started yet")
            return
        }
        chat.load(origin)
        if (origin is GuestOrigin) {
            showChat()
            flashStatus("this chat is being served by ${origin.label()}")
        } else {
            showTerminal()
        }
    }

    /** The chat, and not the terminal. */
    private fun showChat() {
        chat.visibility = View.VISIBLE
        terminal.visibility = View.GONE
        extraKeys.visibility = View.GONE
    }

    /** The terminal, and not the chat. */
    private fun showTerminal() {
        chat.visibility = View.GONE
        terminal.visibility = View.VISIBLE
        extraKeys.visibility = View.VISIBLE
    }

    private val chatShowing: Boolean get() = chat.visibility == View.VISIBLE

    // ---- soft keyboard ------------------------------------------------------------------

    private fun wireIme() {
        imeInput.showSoftInputOnFocus = true
        imeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                if (text.isEmpty()) return
                s?.clear()
                val bytes = InputEncoder.encodeArmed(armed, text)
                    if (bytes.isNotEmpty()) input.feed(bytes)
                if (armed != Modifier.NONE) {
                    armed = Modifier.NONE
                    flashStatus("")
                }
            }
        })
        imeInput.requestFocus()
        showIme()
    }

    private fun showIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(imeInput, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val bytes = InputEncoder.encode(event)
            if (bytes.isNotEmpty()) {
                input.feed(bytes)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- extra keys ---------------------------------------------------------------------

    override fun onModifierToggled(mod: Modifier, armed: Boolean) {
        this.armed = if (armed) mod else Modifier.NONE
        if (armed) flashStatus("${mod.name.lowercase()} armed")
    }

    override fun onKey(key: String) {
        // A named key (TAB, an arrow) is only a modifier target when one is armed; otherwise its
        // own encoding wins, so "TAB" never arrives as the letter T.
        val bytes = if (armed == Modifier.NONE) {
            namedKeyBytes(key)
        } else {
            InputEncoder.encodeArmed(armed, key).takeIf { it.isNotEmpty() } ?: namedKeyBytes(key)
        }
        if (bytes != null) input.feed(bytes)
        armed = Modifier.NONE
    }

    private fun namedKeyBytes(key: String): ByteArray? = when (key) {
        "ESC" -> byteArrayOf(0x1B.toByte())
        "TAB" -> byteArrayOf(0x09.toByte())
        "UP" -> csi('A').toByteArray(Charsets.ISO_8859_1)
        "DOWN" -> csi('B').toByteArray(Charsets.ISO_8859_1)
        "RIGHT" -> csi('C').toByteArray(Charsets.ISO_8859_1)
        "LEFT" -> csi('D').toByteArray(Charsets.ISO_8859_1)
        "HOME" -> csi('H').toByteArray(Charsets.ISO_8859_1)
        "END" -> csi('F').toByteArray(Charsets.ISO_8859_1)
        "PGUP" -> csi("5~").toByteArray(Charsets.ISO_8859_1)
        "PGDN" -> csi("6~").toByteArray(Charsets.ISO_8859_1)
        else -> key.toByteArray(Charsets.UTF_8)
    }

    private fun csi(final: Char): String = 27.toChar() + "[" + final

    private fun csi(final: String): String = 27.toChar() + "[" + final

    // ---- clipboard ----------------------------------------------------------------------

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("omp terminal", text))
    }

    /** The view copies into `copyToClipboard`; this re-reads the same text for the status line. */
    override fun onResume() {
        super.onResume()
        goImmersive()
        // Whichever session is in front owns the screen — the Screen is shared, so this is the one
        // repaint that has to happen on the way back, whether the user was in the phone's shell or
        // in the VM.
        active.screen.markAllDirty()
        chat.onResume()
        if (!services.isExternalStorageManager()) flashStatus("all-files access not granted: run grant-storage")
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(poll)
        // The timers too, not only the renderer: a WebView that is off screen but not paused keeps
        // running JavaScript on a phone that is trying to sleep.
        chat.onPause()
    }

    // ---- back ----------------------------------------------------------------------------

    override fun onBackPressed() {
        // The chat gets Back before the terminal does, and only while it is on screen: a page with
        // history goes back one step, and a page without it gives the terminal back rather than
        // exiting the app from inside a WebView.
        if (chatShowing) {
            if (chat.goBack()) return
            showTerminal()
            return
        }
        // The session in front, not the phone's: a job running inside the VM is the job Back means.
        val job = active.foreground
        if (job != null) {
            job.cancel()
            flashStatus("interrupted")
            return
        }
        // One level of nesting, so one level of Back: `exit` in the VM ends its REPL, which pops it
        // and puts the phone shell back in front. It must not reach the app's own exit path, or
        // leaving the VM would close the terminal.
        if (active !== shell.session) {
            // The flag is enough: the REPL's line editor polls `exitRequested` while it waits for a
            // key and ends the loop itself. Feeding a Ctrl-D as well used to land in `deleteForward`
            // whenever the line was not empty, so Back ate a character and went nowhere.
            active.exitRequested = true
            flashStatus("leaving the vm")
            return
        }
        val now = System.currentTimeMillis()
        if (now - backPressedAt < 2000) {
            finish()
        } else {
            backPressedAt = now
            flashStatus("press back again to exit")
        }
    }

    // ---- chrome --------------------------------------------------------------------------

    private fun goImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    }

    /**
     * Transient feedback that does not disturb the screen. The shell owns every cell of the
     * terminal, so a status line written from here would land in the middle of the prompt; a toast
     * is the one place on a phone that can say something without corrupting the output.
     */
    private fun flashStatus(message: String) {
        if (message.isEmpty()) return
        ui.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun defaultCellHeightPx(): Int =
        (TerminalView.DEFAULT_CELL_DP * resources.displayMetrics.density).toInt()

    private val poll = object : Runnable {
        override fun run() {
            // Only the root session's `exit` means "close the app". A nested one that exits is a
            // user leaving the VM, and finishing here would drop them out of the terminal entirely.
            if (shell.session.exitRequested) {
                finish()
                return
            }
            // The one thing this loop asks that is not about the screen. A volatile read and one
            // comparison: the guest start is on its own thread and this is how the main thread
            // learns it finished. `originDue` is false after the first decision, so the rest of the
            // life of this Activity costs one boolean read per frame.
            if (originDue()) decideUiOrigin()
            if (screen.isDirty()) terminal.requestRedraw()
            persistCellHeight()
            ui.postDelayed(this, FRAME_MS)
        }
    }

    private var lastPersistedCellHeight = -1

    private fun persistCellHeight() {
        val h = terminal.cellHeight()
        if (h != lastPersistedCellHeight) {
            lastPersistedCellHeight = h
            services.putPrefInt(PREF_CELL_HEIGHT, h)
        }
    }

    /**
     * Where the guest's own output goes.
     *
     * **The app's log and not the screen, and not nothing.** Apache and proot both write
     * diagnostics, and the only copy a user ever gets of the interesting ones is the one `omp
     * doctor` quotes out of the record — so this stream is a debugging convenience and a null target
     * would be a way of losing a line that would have been the answer. It is a field because
     * [GuestRuntime] keeps the guest for the life of the process while this Activity is destroyed
     * and recreated by a rotation.
     */
    private val guestLog: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            Log.d("omp-guest", b.toInt().toChar().toString())
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            Log.d("omp-guest", String(b, off, len, Charsets.UTF_8))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(poll)
        terminal.destroy()
        input.close()
        shellThread?.interrupt()
    }

    /**
     * `POST_NOTIFICATIONS`, on API 33 and up.
     *
     * **Asked once and never again.** A foreground service is required to show a notification, and
     * a service nobody can see is a service nobody can stop, so without this grant the user is left
     * with something running that they have no way to reach. A refusal is not fatal and is not
     * re-asked: the service runs, the notification does not appear, and `web` in the terminal
     * still says where the URL is.
     */
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val wanted = android.Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(wanted) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(wanted), NOTIFICATION_PERMISSION)
        }
    }

    companion object {
        const val PREF_CELL_HEIGHT = "cell_height_px"
        const val PREF_SCROLLBACK = "scrollback_lines"
        const val PREF_COPY_ON_SELECT = "copy_on_select"
        const val PREF_THEME = "theme"
        const val DEFAULT_SCROLLBACK = 2000
        private const val FRAME_MS = 16L
        private const val NOTIFICATION_PERMISSION = 1

    }
}
