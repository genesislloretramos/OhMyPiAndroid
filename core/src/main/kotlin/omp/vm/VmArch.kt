package omp.vm

import omp.shell.PlatformServices

/**
 * The one answer to "what architecture is this?", derived from the host's own ABI.
 *
 * **Why a mapping exists.** Android names its ABIs for the instruction set a device runs
 * (`arm64-v8a`, `armeabi-v7a`, `x86_64`); Debian names its packages for the architecture the
 * distribution calls that same thing (`aarch64`, `armhf`, `amd64`, `i386`). Neither name is a
 * synonym for the other, and a namespace that printed one on the boot line and the other from
 * `uname -m` would be claiming two machines. So the translation lives here, in one place, and
 * everything that has an architecture reads it from here: the boot line, `/etc/apt/apt.conf`,
 * `dpkg --print-architecture`, `apt show`, and the `machine` field of `uname` and `/proc/uname`.
 *
 * The answer is therefore *the host's*, not a constant. A phone is aarch64 or x86_64, and a
 * namespace that reported `amd64` on a Pixel would be wrong twice over.
 *
 * [DEFAULT] is what a device that reports no ABI at all gets, and it is the same value the previous
 * hard-coded constant used, so a build with no properties is unchanged rather than suddenly
 * describing an architecture nothing claims.
 */
object VmArch {

    /** Used when the platform reports no `ro.product.cpu.abi`; matches a stock x86_64 emulator. */
    const val DEFAULT = "amd64"

    /**
     * The Debian architecture name for [services]' ABI, or [DEFAULT] when the platform reports
     * nothing, an empty string, or a name this has never heard of.
     */
    fun of(services: PlatformServices): String = from(VmHost.prop(services, "ro.product.cpu.abi", ""))

    /** The mapping itself, so a test can exercise it without a platform behind it. */
    fun from(abi: String): String = when (abi.trim().lowercase()) {
        "arm64-v8a", "aarch64", "arm64" -> "aarch64"
        "armeabi-v7a", "armeabi", "arm" -> "armhf"
        "x86_64", "amd64" -> "amd64"
        "x86", "i386", "i686" -> "i386"
        "" -> DEFAULT
        else -> DEFAULT
    }
}
