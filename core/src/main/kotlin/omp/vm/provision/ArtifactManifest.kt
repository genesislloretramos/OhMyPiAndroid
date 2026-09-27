package omp.vm.provision

import java.util.Locale

/**
 * One thing this layer fetches, and everything that has to be true of it before it is unpacked.
 *
 * [sha256] is nullable on purpose, and the null is a fact rather than a gap in a table: the agent's
 * digest is published in the release's own `SHA256SUMS.txt` and is measured here, while the Debian
 * netboot image is a different download with a different publisher and no digest pinned in this
 * build. A [Provisioner] that treats null as "verified" would be lying, and so would one that
 * treated it as a failure — so it verifies the length, says in the report that no digest is pinned
 * for it, and gets on with it. The provenance is HTTPS from `deb.debian.org`; that is a weaker
 * promise than a digest and the report says so rather than letting the word "verified" cover it.
 */
data class Artifact(
    /** A short stable name; it is the file name of the partial download and the word in a report. */
    val name: String,
    val url: String,
    /** The exact byte count upstream serves, and the size a resumed download is checked against. */
    val sizeBytes: Long,
    /** Lower-case hex, or null when this build pins no digest for the artifact. */
    val sha256: String?,
)

/**
 * A measured cost the guest takes on itself, out of the Debian archive, after it is unpacked.
 *
 * It is deliberately **not** an [Artifact]: this layer does not fetch it, does not verify it and
 * cannot resume it, and a type that implied otherwise would put a download in the download list
 * that never happens. What it is for is arithmetic — the bytes the same mobile connection will
 * carry, and the room the same disk has to have — because those are the numbers a user is asked
 * about before the rootfs has been downloaded at all.
 *
 * Both figures are null where they were not measured, and the manifest says so rather than
 * substituting the nearest architecture's.
 */
data class GuestInstall(
    /** What a user calls it, for the sentence: `LAMP`. */
    val name: String,
    /** The package set, and the closure those names pull in. */
    val packages: List<String>,
    /** What `apt` fetches, to the byte, or null where unmeasured. */
    val downloadBytes: Long?,
    /** What it occupies once installed, in KiB as `dpkg` prints it, or null where unmeasured. */
    val installedKib: Long?,
) {

    /**
     * The one line about this install, naming the packages and what they cost, for [Boot].
     *
     * **It is one line in both directions, and that is the point.** A user reading a report has to
     * find the answer to "what is in there and what did it cost me" without it being spread over a
     * paragraph, and the same sentence is what a [GuestPackages] run ends with — so the number
     * shown before agreeing to the download and the number shown after it are the same words.
     *
     * @param installed whether the mark is in the guest's tree, which is the only thing that makes
     *   it true. Nothing in here asks the guest anything.
     */
    fun costLine(installed: Boolean): String {
        val names = packages.joinToString(", ") + " and their dependencies"
        val cost = if (downloadBytes != null && installedKib != null) {
            "${ArtifactManifest.humanBytes(downloadBytes)} over the same mobile connection and " +
                "${ArtifactManifest.humanBytes(installedKib * 1024L)} on the device, both measured"
        } else {
            "nothing has been measured for $name on this ABI, so the real first-run cost is " +
                "larger than any figure above"
        }
        return if (installed) {
            "$name is installed in the Debian: $names, for $cost. A package on the disk is not a " +
                "running service — nothing in this build starts the guest's Apache."
        } else {
            "the Debian is provisioned with $name from the Debian archive at first boot, not " +
                "bundled in this app: $names, installed by apt inside the guest once the rootfs " +
                "is unpacked, for $cost, and not started without you asking. Neither figure " +
                "includes the Debian's own unpacked size, which nothing has measured — see " +
                "ArtifactManifest.requiredBytes for why no factor is applied to it."
        }
    }
}

/**
 * What a device of one [Abi] needs before the real `omp` can answer, and what it cannot have.
 *
 * **The table is measured, and the tests pin every number in it.** A rootfs that grows upstream is
 * a visible test failure and a sentence to re-measure, which is the only kind of drift that should
 * reach a phone at all: a 60 MB rootfs silently becoming a 90 MB one is 30 MB of somebody's mobile
 * data that nobody agreed to.
 *
 * **What is not an [Artifact] is the LAMP closure**, and the reason is in [GuestInstall]: the
 * guest installs it for itself out of the Debian archive, so a number describing a transfer this
 * layer does not make belongs in the arithmetic, not in the download list. It is on this class
 * anyway, because a cost a user is not told about is a cost they pay for by accident.
 *
 * **Why Debian trixie netboot and not the Ubuntu minimal image.** Both were measured and both are
 * real Debian-family root filesystems. The netboot image wins on two counts and loses on none: it is
 * a **gzip'd tar**, and `java.util.zip` is the only decompressor this project is allowed to use
 * (there is no xz in the JDK and no new dependency is permitted), and it exists for the 32-bit ARM
 * port that Ubuntu's minimal image does not ship, which is the port with a Debian and no agent.
 * Ubuntu's `ubuntu-24.04-minimal-cloudimg-<arch>-root.tar.xz` is 81,638,020 bytes with sha256
 * `e3ebf31f…` on arm64 and 117,884,936 bytes with sha256 `094dc0af…` on amd64, lives under
 * `…/minimal/releases/noble/release/` and not `…/current/`, and is not used here. Those numbers are
 * recorded once, in this paragraph, and the code does not carry them.
 *
 * **The `i386` hole is upstream's and permanent.** Debian publishes no netboot image for i386 — the
 * URL is a 404, measured, not inferred — so a 32-bit x86 device can be given nothing at all by
 * this layer. [gap] is that sentence, and [Provisioner] refuses before a byte moves rather than
 * unpacking an i386 armhf rootfs onto an x86 phone.
 */
class ArtifactManifest(
    val abi: Abi,
    /** The Debian root filesystem, or null where Debian publishes no netboot image for this port. */
    val rootfs: Artifact?,
    /** The real `omp` binary, or null on the ABIs upstream has no build for. */
    val agent: Artifact?,
    /**
     * What the guest installs for itself out of the Debian archive, measured.
     *
     * **This is a cost, not a download this layer makes.** The requirement is a Debian with LAMP
     * in it, and the guest gets that from `apt` inside the Debian, over the same mobile
     * connection, after the rootfs has been unpacked — a second transfer this layer neither
     * performs nor can see. It is on the manifest anyway, because a number a user agrees to that
     * is not the number they pay is the one lie this table must not tell, and the two are 20%
     * apart.
     */
    val guest: GuestInstall,
) {

    /**
     * Everything a missing artifact means, in one sentence, or null when nothing is missing.
     *
     * An x86 device has two holes and the rootfs one is the fatal one, so the fatal one is what
     * this says; a 32-bit ARM device has one hole, the agent, and it is the sentence a user is
     * owed when they ask why the real binary is not what answered them.
     */
    val gap: String?
        get() = when {
            rootfs == null -> NO_ROOTFS
            agent == null ->
                "there is no real omp agent for ${abi.abiName}: ${abi.agentRefusal}"
            else -> null
        }

    /**
     * The bytes that cross the mobile connection on a first run, to the byte.
     *
     * This includes the guest's own `apt` transfer, because those bytes cross the same radio and
     * are paid for out of the same allowance. A total that stopped at the two artifacts this layer
     * downloads would be 57,211,704 bytes short on an arm64 phone, which is the kind of short that
     * is discovered on the last day of a billing cycle.
     */
    val totalBytes: Long
        get() = (rootfs?.sizeBytes ?: 0L) + (agent?.sizeBytes ?: 0L) + (guest.downloadBytes ?: 0L)

    /**
     * The extra room the guest's own packages take once installed, or null where it was not
     * measured. In KiB, which is the unit `apt` and `dpkg` both print, and not converted here
     * because a second spelling of one number is a second number to keep right.
     */
    val installedKib: Long?
        get() = guest.installedKib

    /**
     * The room a first run needs, and the number the space check is made against.
     *
     * **Every byte that crosses the connection, plus the measured installed footprint of the
     * guest's packages.** The rootfs's own unpacked size is deliberately *not* in it, because it
     * has not been measured and a number invented to fill a hole is the same defect this class
     * exists to avoid — so this is a floor, stated as a floor, and an archive that expands further
     * still fails with `No space left on device` part way through an unpack.
     *
     * ### Why no expansion factor is applied to the compressed image, written down once
     *
     * The obvious repair is to multiply [rootfs]'s measured size by a ratio, and there is no ratio
     * here that is a measurement. Every bound a gzip'd tar admits is one of two things:
     *
     * - **An upper bound, and it is absurd.** DEFLATE's worst case is a dynamic-Huffman block
     *   that spends about two bits on a 258-byte repeat against a distance of 1, which is 1032:1 —
     *   60,247,499,520 bytes for the arm64 image. Even the fixed-Huffman worst case of roughly
     *   159:1 is 9,282,318,240 bytes. No phone has that, so a check built on either refuses every
     *   device this feature could run on, which is a check that never passes and therefore a check
     *   nobody runs.
     * - **A lower bound, and a check needs an upper one.** A 512-byte header per tar member and the
     *   fact that a member is never smaller than its own name give a floor on the unpacked size.
     *   Knowing the room is at least 60 MB is no help to a phone that needs 1.3 GB.
     *
     * So the two terms above stay the only terms: what was measured, twice, in the only place a
     * number can be measured in this project — against a URL and a package index. The honest form
     * of the remaining gap is a sentence on the number a user reads, and it is in [describe].
     */
    val requiredBytes: Long
        get() = totalBytes + (guest.installedKib?.times(1024L) ?: 0L)

    /** True when this device can run the real agent once everything above has been unpacked. */
    val needsAgent: Boolean
        get() = agent != null

    /** True when both guest figures were measured for this ABI, so the sentence can quote them. */
    val guestIsMeasured: Boolean
        get() = guest.downloadBytes != null && guest.installedKib != null

    /**
     * The number a user is asked to agree to, in a sentence with real units on it.
     *
     * This is the line that appears before a first run's bytes cross a mobile connection, so it
     * carries the MiB a phone's settings screen will show, the exact byte count, **and the room
     * the same run needs on the device** — because "about 280 MB" that turns into half a gigabyte
     * of free space is the kind of rounding that turns into a complaint.
     */
    fun describe(): String {
        val target = abi.abiName
        val root = rootfs
        if (root == null) return "$target: $NO_ROOTFS"
        if (agent == null) {
            return "$target: ${humanBytes(root.sizeBytes)} for the Debian trixie rootfs, and no " +
                "agent on top of it — ${abi.agentRefusal}."
        }
        val lamp = guest
        val pieces = StringBuilder("${humanBytes(root.sizeBytes)} of Debian trixie rootfs and ")
        pieces.append("${humanBytes(agent.sizeBytes)} of the omp $AGENT_RELEASE agent binary")
        if (!guestIsMeasured) {
            return "$target: ${humanBytes(totalBytes)} over the network — $pieces. Nothing has been " +
                "measured for the ${lamp.name} packages on this ABI, so the real first-run cost is " +
                "larger than this and the room check is a floor rather than the total."
        }
        return "$target: ${humanBytes(totalBytes)} over the network — $pieces, and " +
            "${humanBytes(lamp.downloadBytes!!)} of ${lamp.name} that apt fetches inside the Debian " +
            "after the rootfs is unpacked. It needs ${humanBytes(requiredBytes)} of room on the " +
            "device, of which ${humanBytes(lamp.installedKib!! * 1024L)} is ${lamp.name} installed."
    }

    companion object {

        /** The release tag both Linux agent builds come from, and the directory they are under. */
        const val AGENT_RELEASE = "v18.3.4"

        /**
         * The Debian netboot point release the rootfs URLs name, spelled as upstream publishes it.
         *
         * Debian removes a superseded point release from the netboot tree, so this string is the
         * one thing in the table that goes stale on a schedule, and a stale one is a 404 on the
         * first byte rather than a wrong number. It is one constant for exactly that reason: the
         * fix is to re-measure the four sizes in this file and change this line.
         */
        const val DEBIAN_POINT_RELEASE = "debian-13.1.0"

        private const val DEBIAN_NETBOOT = "https://deb.debian.org/debian/netboot"
        private const val AGENT_DOWNLOADS = "https://github.com/can1357/oh-my-pi/releases/download"

        /** The sentence for a port Debian has no netboot image for; measured as a 404, not guessed. */
        const val NO_ROOTFS =
            "Debian publishes no netboot root filesystem for i386, so there is nothing this app " +
                "can download for this device and no real Debian is coming: the image is upstream's " +
                "gap, and the Kotlin agent in this build is what answers here."

        private const val ONE_MIB = 1024L * 1024L

        /**
         * The package set the guest installs for itself, and the two numbers it costs.
         *
         * Apache, PHP and MariaDB with their dependency closure, out of the Debian trixie archive
         * for this ABI — the closure, not the four named packages, because a number for the
         * packages alone is a number about nothing.
         */
        val LAMP_PACKAGES = listOf(
            "apache2-bin",
            "libapache2-mod-php8.4",
            "php8.4-cli",
            "mariadb-server",
        )

        /**
         * The table, for one ABI.
         *
         * Every number below was measured against the URL or the package index above it; none of
         * them is a guess and none of them is written twice. The two 32-bit ports are the
         * interesting rows: one gets a Debian and no agent, the other gets nothing at all, and
         * both say so in a sentence rather than in a failure three hundred megabytes later.
         */
        fun of(abi: Abi): ArtifactManifest = ArtifactManifest(
            abi = abi,
            rootfs = debianRootfs(abi),
            agent = ompAgent(abi),
            guest = guestInstall(abi),
        )

        /**
         * The LAMP closure for one ABI, or a null figure where it was not measured.
         *
         * **Only arm64 was measured**, from the Debian trixie arm64 package index, and the other
         * three rows carry null rather than a number borrowed from arm64. The sizes are
         * architecture-dependent, and an amd64 `.deb` set against an arm64 figure is not a
         * rounding difference — it is the difference between a phone that installs and a phone
         * that runs out of room part way through. A null makes [describe] say that the figure is
         * missing instead of printing a number it cannot stand behind.
         */
        private fun guestInstall(abi: Abi): GuestInstall = when (abi) {
            Abi.ARM64 -> GuestInstall(
                name = "LAMP",
                packages = LAMP_PACKAGES,
                downloadBytes = 57_211_704,
                installedKib = 385_689,
            )
            Abi.ARMEABI_V7A, Abi.X86, Abi.X86_64 ->
                GuestInstall("LAMP", LAMP_PACKAGES, null, null)
        }

        private fun debianRootfs(abi: Abi): Artifact? = when (abi) {
            Abi.ARM64 -> debian("arm64", 58_379_360)
            Abi.ARMEABI_V7A -> debian("armhf", 39_098_143)
            Abi.X86_64 -> debian("amd64", 55_459_886)
            // No image: deb.debian.org answers 404 for netboot/debian-<release>/i386/linux.
            Abi.X86 -> null
        }

        private fun debian(port: String, sizeBytes: Long) = Artifact(
            name = "debian-trixie-rootfs-$port",
            url = "$DEBIAN_NETBOOT/$DEBIAN_POINT_RELEASE/$port/linux",
            sizeBytes = sizeBytes,
            // No digest is pinned for the netboot image in this build. See [Artifact.sha256].
            sha256 = null,
        )

        private fun ompAgent(abi: Abi): Artifact? = when (abi) {
            Abi.ARM64 -> agent("omp-linux-arm64", 234_866_984, "bb058d49dde5bb84e6f1b96aa798d7393d3bce1a6927392694e49120ba2c2619")
            Abi.X86_64 -> agent("omp-linux-x64", 284_861_920, "e8d13ea8da6281f183108bc25ae94c4a1a5159a2072d188ea8c30bbfce4735b8")
            Abi.ARMEABI_V7A, Abi.X86 -> null
        }

        private fun agent(asset: String, sizeBytes: Long, sha256: String) = Artifact(
            name = asset,
            url = "$AGENT_DOWNLOADS/$AGENT_RELEASE/$asset",
            sizeBytes = sizeBytes,
            sha256 = sha256,
        )

        /**
         * A byte count as a person reads it: MiB to one decimal with the exact count in brackets,
         * and plain bytes below a mebibyte, where a decimal fraction would be a rounding error
         * pretending to be a measurement.
         */
        fun humanBytes(bytes: Long): String =
            if (bytes < ONE_MIB) "$bytes bytes"
            else String.format(Locale.ROOT, "%.1f MiB (%,d bytes)", bytes / ONE_MIB.toDouble(), bytes)
    }
}
