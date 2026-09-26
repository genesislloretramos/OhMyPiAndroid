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
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import com.omp.terminal.android.AndroidCommands
import com.omp.terminal.android.AndroidPlatformServices
import omp.shell.InputChannel
import omp.shell.ShellSession
import omp.term.Screen

/**
 * The single Activity. It owns the shell thread and the session; the terminal view only ever reads
 * a snapshot of the screen, so there is exactly one writer and one reader.
 */
class MainActivity : Activity(), ExtraKeysView.Listener {

    private lateinit var screen: Screen
    private lateinit var input: InputChannel
    private lateinit var shell: ShellSession
    private lateinit var terminal: TerminalView
    private lateinit var extraKeys: ExtraKeysView
    private lateinit var imeInput: EditText
    private lateinit var services: AndroidPlatformServices

    private val ui = Handler(Looper.getMainLooper())
    private var shellThread: Thread? = null
    private var armed = Modifier.NONE
    private var backPressedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        services = AndroidPlatformServices(applicationContext, this)
        AndroidCommands.register(omp.shell.exec.CommandTable)

        val cellHeight = services.prefInt(PREF_CELL_HEIGHT, 0)
        val scrollback = services.prefInt(PREF_SCROLLBACK, DEFAULT_SCROLLBACK)
        val bell = { flashStatus("bell") }
        screen = Screen(24, 80, scrollback, bell)

        setContentView(R.layout.activity_main)
        // After setContentView: the insets controller only exists once the decor view does.
        goImmersive()
        terminal = findViewById(R.id.terminalView)
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
        services.titleListener = { title -> ui.post { window.setTitle(title) } }

        wireIme()

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
        shell.session.screen.markAllDirty()
        if (!services.isExternalStorageManager()) flashStatus("all-files access not granted: run grant-storage")
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(poll)
    }

    // ---- back ----------------------------------------------------------------------------

    override fun onBackPressed() {
        val job = shell.session.foreground
        if (job != null) {
            job.cancel()
            flashStatus("interrupted")
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
            if (shell.session.exitRequested) {
                finish()
                return
            }
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

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(poll)
        terminal.destroy()
        input.close()
        shellThread?.interrupt()
    }

    companion object {
        const val PREF_CELL_HEIGHT = "cell_height_px"
        const val PREF_SCROLLBACK = "scrollback_lines"
        const val PREF_COPY_ON_SELECT = "copy_on_select"
        const val PREF_THEME = "theme"
        const val DEFAULT_SCROLLBACK = 2000
        private const val FRAME_MS = 16L
    }
}
