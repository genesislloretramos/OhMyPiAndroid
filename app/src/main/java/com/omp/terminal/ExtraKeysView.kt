package com.omp.terminal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

class ExtraKeysView : HorizontalScrollView {

    interface Listener {
        fun onModifierToggled(mod: Modifier, armed: Boolean)

        /** A key name (ESC, TAB, UP, DOWN, LEFT, RIGHT, HOME, END, PGUP, PGDN) or a literal character. */
        fun onKey(key: String)
    }

    var listener: Listener? = null

    private val modifierButtons = HashMap<Modifier, Button>(2)
    private var armed = Modifier.NONE
    private var armedTint = 0
    private var idleTint = 0

    constructor(context: Context, listener: Listener) : super(context) {
        this.listener = listener
        buildBar()
    }

    constructor(context: Context, attrs: AttributeSet? = null) : super(context, attrs) {
        buildBar()
    }

    private fun buildBar() {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        armedTint = themeColor(android.R.attr.colorAccent, 0xFF4CAF50.toInt())
        idleTint = themeColor(android.R.attr.textColorSecondary, 0xFFB0B0B0.toInt())
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        for (label in DEFAULT_KEYS) row.addView(createButton(label))
        addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        updateModifierTints()
    }

    private fun createButton(label: String): Button {
        val button = Button(context)
        button.setBackgroundResource(themeResource(android.R.attr.selectableItemBackground))
        button.isAllCaps = false
        button.gravity = Gravity.CENTER
        button.text = label
        button.contentDescription = keyName(label)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        val size = dp(KEY_SIZE_DP)
        button.minWidth = size
        button.minHeight = size
        button.minimumWidth = size
        button.minimumHeight = size
        button.setPadding(dp(8), 0, dp(8), 0)
        button.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            size,
        )
        button.setOnClickListener { onPressed(label) }
        when (label) {
            CTRL_LABEL -> modifierButtons[Modifier.CTRL] = button
            ALT_LABEL -> modifierButtons[Modifier.ALT] = button
        }
        return button
    }

    private fun onPressed(label: String) {
        when (label) {
            CTRL_LABEL -> toggle(Modifier.CTRL)
            ALT_LABEL -> toggle(Modifier.ALT)
            else -> emit(label)
        }
    }

    private fun toggle(mod: Modifier) {
        armed = if (armed == mod) Modifier.NONE else mod
        updateModifierTints()
        listener?.onModifierToggled(mod, armed == mod)
    }

    private fun emit(label: String) {
        val mod = armed
        listener?.onKey(keyName(label))
        if (mod != Modifier.NONE) {
            armed = Modifier.NONE
            updateModifierTints()
            listener?.onModifierToggled(mod, false)
        }
    }

    private fun updateModifierTints() {
        for ((mod, button) in modifierButtons) {
            val on = armed == mod
            button.setTextColor(ColorStateList.valueOf(if (on) armedTint else idleTint))
            button.setTypeface(if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
            // A tint alone is too easy to miss on a phone, so the armed key also gets a fill.
            button.backgroundTintList = ColorStateList.valueOf(
                if (on) armedTint and 0x40FFFFFF else Color.TRANSPARENT
            )
        }
    }

    private fun keyName(label: String): String = when (label) {
        "↑" -> "UP"
        "↓" -> "DOWN"
        "←" -> "LEFT"
        "→" -> "RIGHT"
        else -> label
    }

    private fun themeResource(attr: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) value.resourceId else 0
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val value = TypedValue()
        val resolved = context.theme.resolveAttribute(attr, value, true) &&
            value.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
            value.type <= TypedValue.TYPE_LAST_COLOR_INT
        return if (resolved) value.data else fallback
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_SIZE_DP = 44
        private const val CTRL_LABEL = "CTRL"
        private const val ALT_LABEL = "ALT"

        val DEFAULT_KEYS: List<String> = listOf(
            "ESC", "CTRL", "ALT", "TAB", "↑", "↓", "←", "→",
            "HOME", "END", "PGUP", "PGDN",
            "|", "-", "_", "/", "~", "^", "\$", "*", "?", "!", ":", ";", "'", "\"",
        )
    }
}
