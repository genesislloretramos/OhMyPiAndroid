package omp.vm.provision

/**
 * The four Android ABIs, and the three facts about each that everything else in this layer needs.
 *
 * **A device is not an architecture, it is a list of them, in order of preference.** Android
 * installs an app for the ABIs it ships and runs it under the first one the device has, which is
 * why a phone that is `x86_64` can still be executing arm64 code through a translation layer, and
 * why `os.arch` — a property the JVM makes about the CPU it is running on — can name a different
 * instruction set from the one this process is actually executing. Both are real answers to
 * different questions, and the question this layer has to answer is the second one: *which ABI's
 * ELF binaries can be loaded and executed here*, because a rootfs and a glibc binary are ELF
 * binaries.
 *
 * So [detect] takes Android's own ordered list, `Build.SUPPORTED_ABIS`, as the authority, and asks
 * `os.arch` only for a process that was handed no list at all. Picking `os.arch` first would be the more
 * obvious reading and the wrong one: a translation layer makes the CPU able to *run* foreign code
 * while the app's own native libraries — the ones the package manager extracted and the loader
 * picked for this process — are still the primary ABI's, and a download chosen from the wrong end
 * of that disagreement is 55 MB of the wrong Debian and an `Exec format error` at the end of it.
 * [detect]'s own KDoc says which one won, because a device that disagrees with itself is a thing a
 * bug report will otherwise arrive about.
 *
 * **This enum is not a summary of the CPU.** It is a summary of what can be downloaded and run,
 * and the third fact below is the sharpest edge of the whole feature: `omp` publishes two Linux
 * builds, arm64 and x64, and there is no 32-bit one. That is a permanent gap upstream, not
 * something this layer can route around, so it is a field on the enum rather than a check somebody
 * will forget to write — a 32-bit phone gets a real Debian and the Kotlin agent, and is told why.
 */
enum class Abi(
    /** The string Android itself uses: a directory name under `lib/` and a value in `SUPPORTED_ABIS`. */
    val abiName: String,
    /**
     * Debian's name for the same machine family, which is what an `apt` line and a `dpkg
     * --print-architecture` want. It is not the `uname -m` name and not the Android one, and
     * [omp.vm.VmArch] already answers the `uname` question for the namespace; this is the other
     * half.
     *
     * The 32-bit ARM port is Debian's `armhf` — its network-boot directory is `armhf` too, and
     * [ArtifactManifest] spells that URL the way upstream publishes it — while the architecture's
     * name is `arm`. Both are true and they are not the same string, which is exactly the kind of
     * thing that belongs in a comment rather than in a second mapping table.
     */
    val debianArch: String,
    /** Whether a build of the real `omp` agent exists for this ABI at all. */
    val hasAgent: Boolean,
) {
    ARM64("arm64-v8a", "arm64", true),
    ARMEABI_V7A("armeabi-v7a", "arm", false),
    X86("x86", "i386", false),
    X86_64("x86_64", "amd64", true),
    ;

    /**
     * Why the real agent cannot be here, in one line, or null when it can.
     *
     * The sentence is a field rather than a string built at the call site because this is the one
     * answer a 32-bit user has to be given more than once — at the first run, and again every time
     * a report says which agent is talking to them — and two wordings of "sorry, no binary" is how
     * one of them ends up implying the feature is still coming.
     */
    val agentRefusal: String?
        get() = if (hasAgent) null else NO_32_BIT_AGENT

    companion object {

        /** The reason 32-bit ABIs have no agent, in the words [agentRefusal] hands out. */
        const val NO_32_BIT_AGENT = "the agent publishes no 32-bit Linux build"

        /**
         * The ABI for one of Android's own names, or null for a name this enum has never heard of.
         *
         * This is the explicit entry point, and it is what a test uses: [detect] has to take two
         * facts a JVM does not have, and a test that could only reach the answer through those two
         * facts would be testing the argument order.
         */
        fun of(abiName: String): Abi? = entries.firstOrNull { it.abiName == abiName.trim() }

        /**
         * The ABI this process is running as.
         *
         * [supportedAbis] is `Build.SUPPORTED_ABIS.toList()` and it wins, because it is the list
         * Android ordered for *this* process; [osArch] is `System.getProperty("os.arch")` and it is
         * only asked when there is no list, which in this app means a test or a JVM rather than a
         * device. Null when neither source names an ABI in this enum — a `riscv64` phone, or a
         * build property that has been edited — and the answer to that is a sentence rather than a
         * guess, because downloading an arm64 Debian onto a device that cannot execute it is the
         * most expensive wrong answer available here.
         *
         * @param osArch `System.getProperty("os.arch")`.
         * @param supportedAbis `Build.SUPPORTED_ABIS`, in order.
         */
        fun detect(osArch: String?, supportedAbis: List<String>): Abi? {
            for (name in supportedAbis) {
                val known = of(name)
                if (known != null) return known
            }
            // The JVM's own names, which are neither Android's nor Debian's: `aarch64` for the
            // 64-bit ARM machine and `armv7l` for the 32-bit one are what a `os.arch` on a phone
            // actually says, and mapping them onto the enum by substring would be a guess.
            return when (val arch = osArch?.trim()?.lowercase()) {
                "aarch64", "arm64" -> ARM64
                "arm", "armv7l", "armv7" -> ARMEABI_V7A
                "x86", "i386", "i486", "i586", "i686" -> X86
                "amd64", "x86_64", "x64" -> X86_64
                else -> null
            }
        }
    }
}
