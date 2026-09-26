package com.omp.terminal.android

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.view.Display
import android.view.WindowManager
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

/**
 * The three services a `dumpsys` an ordinary app can actually serve.
 *
 * Every other sub-command of the real tool is a binder call to a system service that refuses
 * untrusted apps, so this prints the reason instead of an empty section that would read like a
 * service with nothing to say.
 */
@CommandSpec(
    name = "dumpsys",
    synopsis = "battery | display | meminfo [PKG|self]",
    group = "android",
    notes = "battery, display and meminfo are the only sub-commands an app can serve; every " +
        "other service is system-only, and another process's memory is denied by SELinux",
)
object Dumpsys : FileCommand() {

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        return when (operands.firstOrNull()) {
            "battery" -> battery(ctx)
            "display" -> displayInfo(ctx)
            "meminfo" -> meminfo(ctx, operands.getOrNull(1))
            null -> ctx.fail("dumpsys: no sub-command; available: battery, meminfo, display")
            else -> ctx.fail(
                "dumpsys: '${operands[0]}' requires system privileges; available: battery, meminfo, display",
            )
        }
    }

    // ---- battery ----------------------------------------------------------------------

    /**
     * The state the real tool prints, in its order. `status` and `health` are the framework's
     * numeric codes there, so the names the seam carries are mapped back to them; a name with no
     * code (a device reporting something new) is printed as the name.
     */
    private fun battery(ctx: ExecContext): Int {
        val info = ctx.services.batteryInfo()
        val plugged = info.plugged.split(',')
        ctx.outLine("Current Battery Service state:")
        ctx.outLine("  AC powered: ${"ac" in plugged}")
        ctx.outLine("  USB powered: ${"usb" in plugged}")
        ctx.outLine("  Wireless powered: ${"wireless" in plugged}")
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.outLine("  Dock powered: ${"dock" in plugged}")
        }
        if (info.chargeCounterUah >= 0) {
            ctx.outLine("  Charge counter: ${info.chargeCounterUah} uAh")
        }
        if (info.currentMa != 0) {
            ctx.outLine("  Current now: ${info.currentMa} uA")
        }
        ctx.outLine("  status: ${STATUS_CODES[info.status] ?: info.status}")
        ctx.outLine("  health: ${HEALTH_CODES[info.health] ?: info.health}")
        // No battery means the framework reports no capacity; that is the only honest signal here.
        ctx.outLine("  present: ${info.level >= 0}")
        ctx.outLine("  level: ${info.level}")
        ctx.outLine("  scale: ${info.scale}")
        ctx.outLine("  voltage: ${info.voltageMv}")
        ctx.outLine("  temperature: ${info.temperatureTenthsC}")
        ctx.outLine("  technology: ${info.technology}")
        return ExecContext.EXIT_OK
    }

    // ---- display ----------------------------------------------------------------------

    private fun displayInfo(ctx: ExecContext): Int {
        val services = ctx.services
        val screen = displayObject()
        ctx.outLine("Display ${services.displayName()}${if (screen != null) " (id ${screen.displayId})" else ""}")
        ctx.outLine("  real size: ${services.displayWidthPx()}x${services.displayHeightPx()}")
        ctx.outLine("  refresh rate: ${formatHz(services.displayRefreshRateHz())} Hz")
        ctx.outLine("  density: ${services.displayDensityDpi()} dpi")
        if (screen == null) {
            ctx.outLine("  HDR: unknown (no display object yet; the shell has no window)")
            return ExecContext.EXIT_OK
        }
        val hdr = try {
            screen.hdrCapabilities
        } catch (e: Exception) {
            null
        }
        if (hdr == null) {
            ctx.outLine("  HDR: unknown (this API level does not report HDR capabilities)")
        } else {
            val types = hdr.supportedHdrTypes.map { hdrTypeName(it) }
            ctx.outLine("  HDR capable: ${types.isNotEmpty()}")
            if (types.isNotEmpty()) ctx.outLine("  HDR types: ${types.joinToString(", ")}")
        }
        return ExecContext.EXIT_OK
    }

    private fun displayObject(): Display? {
        val context = AndroidRuntime.appContext ?: return null
        if (Build.VERSION.SDK_INT >= 30) {
            val fromContext = context.display
            if (fromContext != null) return fromContext
        }
        @Suppress("DEPRECATION")
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        return manager?.defaultDisplay
    }

    private fun hdrTypeName(type: Int): String = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "dolby_vision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "hdr10"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "hdr10+"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "hlg"
        else -> "type_$type"
    }

    private fun formatHz(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()

    // ---- meminfo ----------------------------------------------------------------------

    private fun meminfo(ctx: ExecContext, operand: String?): Int {
        val services = ctx.services
        val ownPackage = services.processName().substringBefore(':')
        if (operand == null) return systemMemory(ctx)
        if (operand == "self" || operand == ownPackage) return processMemory(ctx)
        return ctx.fail(
            "dumpsys meminfo $operand: not permitted; an app can only read its own memory",
        )
    }

    private fun systemMemory(ctx: ExecContext): Int {
        val memory = ctx.services.systemMemory()
        val used = memory.totalBytes - memory.availableBytes
        ctx.outLine("Total RAM: ${memory.totalBytes}")
        ctx.outLine("Free RAM: ${memory.availableBytes}")
        ctx.outLine("Used RAM: $used")
        // MemoryInfo reports no figure for RAM the kernel has taken away, and neither does any
        // public API, so this row says so rather than printing a zero that would read as free.
        ctx.outLine("Lost RAM: not exposed to an app")
        return ExecContext.EXIT_OK
    }

    /**
     * The PSS breakdown of this process, from [Debug.getMemoryInfo]. `MemoryInfo` computes the
     * `summary.*` rows only when the framework had the detailed data to hand; where a row is
     * missing, the equivalent is derived from the always-filled columns, and where neither exists
     * the row says that instead of carrying a made-up number.
     */
    private fun processMemory(ctx: ExecContext): Int {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val summary = try {
            info.memoryStats
        } catch (e: Exception) {
            emptyMap()
        }
        fun stat(key: String, derived: Int? = null): Int? =
            summary["summary.$key"]?.trim()?.toIntOrNull() ?: derived

        ctx.outLine("Memory usage for ${ctx.services.processName()}:")
        row(ctx, "Java Heap", stat("java", info.dalvikPrivateDirty + info.dalvikPss))
        row(ctx, "Native Heap", stat("native", info.nativePrivateDirty + info.nativePss))
        row(ctx, "Code", stat("code", info.otherPrivateDirty + info.otherPss))
        row(ctx, "Stack", stat("stack"))
        row(ctx, "Graphics", stat("graphics"))
        row(ctx, "Private Dirty", info.totalPrivateDirty)
        row(ctx, "System", stat("system"))
        row(ctx, "TOTAL PSS", info.totalPss)
        return ExecContext.EXIT_OK
    }

    private fun row(ctx: ExecContext, label: String, kb: Int?) {
        val value = if (kb == null) "not reported for this process" else "$kb kB"
        ctx.outLine("  ${label.padEnd(14)} $value")
    }
}

private val STATUS_CODES = mapOf(
    "unknown" to BatteryManager.BATTERY_STATUS_UNKNOWN,
    "charging" to BatteryManager.BATTERY_STATUS_CHARGING,
    "discharging" to BatteryManager.BATTERY_STATUS_DISCHARGING,
    "not charging" to BatteryManager.BATTERY_STATUS_NOT_CHARGING,
    "full" to BatteryManager.BATTERY_STATUS_FULL,
)

private val HEALTH_CODES = mapOf(
    "good" to BatteryManager.BATTERY_HEALTH_GOOD,
    "overheat" to BatteryManager.BATTERY_HEALTH_OVERHEAT,
    "dead" to BatteryManager.BATTERY_HEALTH_DEAD,
    "over voltage" to BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE,
    "unspecified failure" to BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE,
    "cold" to BatteryManager.BATTERY_HEALTH_COLD,
)
