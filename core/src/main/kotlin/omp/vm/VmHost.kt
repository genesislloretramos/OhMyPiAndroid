package omp.vm

import omp.shell.PlatformServices
import java.util.UUID

/**
 * The host facts more than one VM backend needs, derived from [PlatformServices] in one place.
 *
 * Every function here answers with the least flattering true value, and the KDoc says which is
 * which: a generated `/proc` that quotes a number the app cannot read is worse than one that quotes
 * a number it can.
 */
internal object VmHost {

    /**
     * The core count. `PlatformServices` has no API for it, so this is one core unless a build
     * property says otherwise — and `ro.config.cpu_count` is exactly the property AOSP writes on
     * the devices that have one, so a stubbed or real property turns the lie into the truth.
     */
    fun cpuCount(services: PlatformServices): Int =
        services.buildProperties()["ro.config.cpu_count"]?.trim()?.toIntOrNull()?.coerceIn(1, 64) ?: 1

    /** The model string `uname -v` and `/proc/version` quote. */
    fun model(services: PlatformServices): String =
        services.buildProperties()["ro.product.model"]?.takeIf { it.isNotBlank() } ?: "unknown Android device"

    fun prop(services: PlatformServices, key: String, fallback: String): String =
        services.buildProperties()[key]?.takeIf { it.isNotBlank() }
            ?: services.systemPropertyOverrides()[key]?.takeIf { it.isNotBlank() }
            ?: fallback

    /**
     * A stable id for this app on this device, for `/proc/sys/kernel/random/uuid`.
     *
     * It is a name-based (MD5) UUID over the app's own identity, so it is the same on every read
     * and the same after a reboot — which is what a tool reading that node expects. It is not a
     * hardware id, it changes if the app is reinstalled, and it identifies the app rather than the
     * person: a real `/proc/sys/kernel/random/uuid` cannot be read by an app at all, so this
     * exists so the file is honest about being generated.
     */
    /**
     * `/etc/machine-id`: 32 hex characters, the shape systemd insists on, derived from the same
     * identity [uuid] hashes. It is not a machine id in the systemd sense — it changes if the app is
     * reinstalled and it says nothing about the person holding the phone — and it exists so a tool
     * that reads the file has a stable value instead of an empty one.
     */
    fun machineId(services: PlatformServices): String = uuid(services).replace("-", "").take(32)

    fun uuid(services: PlatformServices): String {
        val seed = buildString {
            append(services.processName()).append('/')
            append(services.appUid()).append('/')
            append(services.deviceName() ?: services.processName()).append('/')
            append(prop(services, "ro.build.fingerprint", services.processName()))
        }
        return UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()
    }
}
