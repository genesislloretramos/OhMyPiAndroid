package omp.vm.provision

/**
 * The one method that starts a process, and the reason this module does not implement it.
 *
 * **A glibc binary cannot exec on Android's bionic kernel.** There is no `Runtime.exec` in this
 * project and there is not going to be one: proot is a native library that ptrace-traces the
 * guest, and starting it is `ProcessBuilder` at best and `dlopen` at worst, both of which belong
 * to the platform and neither of which a JVM test can exercise. So the launch is an interface,
 * the arguments it is given are built and tested as data, and `:app` implements it against the
 * proot this build packages. A test that "verifies proot" would verify a mock, and no test in
 * this repository has run a native helper.
 *
 * The interface is one method because everything interesting about starting a process here is
 * decided before it starts: what the argument vector says, what the environment says, and which
 * directory it is in. What is left is a fork, and the fork is the implementation's half and not
 * this one's.
 */
fun interface ProotLauncher {
    /**
     * Runs [argv] with [env] and [cwd], and returns its exit status.
     *
     * @param argv the vector from [ProotCommand.argv], `argv[0]` included.
     * @param env the whole environment, from [ProotCommand.env] — not a delta on the Android one.
     * @param cwd the process's own working directory, which for proot is the phone's and not the
     *   guest's; the guest's is the `-w` in [argv].
     */
    fun run(argv: List<String>, env: Map<String, String>, cwd: String): Int
}

/**
 * The argument vector and the environment for one command inside the Debian, built as data.
 *
 * **What is verified here and what is not.** [argv] and [env] are pure functions of this class's
 * constructor arguments, and [ProotCommandTest] pins them exactly: the binds, the rootfs, the
 * work directory, the `PATH`, the `TERM`. None of that is verified — proot has never been run by
 * this code, on this machine or on any, because a test cannot run a native library it does not
 * have. The questions a real device has to answer, in the order they will bite:
 *
 * 1. **Does this proot build accept these flags?** `-r`, `-b`, `-w` and `-k` are its documented
 *    options and the argument vector is built from them, but a proot from a different source may
 *    spell one differently, and the first run is where that shows.
 * 2. **Is the architecture right?** The guest is the same architecture as the device, so no
 *    emulator is named — see [qemu], which is the one flag whose presence is a claim about a
 *    binary this app has not downloaded.
 * 3. **Can the guest's loader be found?** A glibc binary is started through
 *    `/lib/ld-linux-<arch>.so.1` *inside the rootfs*, so a netboot image without `libc6` unpacked
 *    successfully and still cannot run the agent, and nothing above this class can tell.
 * 4. **Will the kernel exec the proot this APK ships?** The bytes are in the package —
 *    `libproot.so` and the loader beside it, one set per ABI, out of `jniLibs` and into
 *    [ProvisionPaths.execDirectory] — and `:app` has the [ProotLauncher] that starts it. None of
 *    that has ever been exec'd: nothing in this repository has run a native helper, on a device
 *    or on this machine. See [ProvisionPaths] for the two directories and what each one is for.
 * 5. **Does the bind work on this kernel?** proot is ptrace-based and some Android 10+ kernels
 *    need `PROOT_NO_SECCOMP=1`, which is why it is in [env] rather than left to a flag a caller
 *    might forget.
 *
 * The bindings themselves are not decoration. [dataDir] is the app's own storage, bound at the
 * same path in and out, so the agent finds its transcripts and its key without a path translation
 * — the same one-path-in, one-path-out rule `/mnt/omp` follows in the namespace VM. [visibleDir] is
 * the conversations folder in shared storage, which is the one directory on the phone a user can
 * open in a file manager, and it is bound at `/mnt/omp` because that is where the in-process VM
 * puts it and a conversation should not be two places depending on which agent is answering.
 * [agentDir] is the target directory's `bin/`, bound at `/usr/local/bin`, which is what puts the
 * agent inside a Debian whose own `/usr/bin` belongs to the rootfs.
 *
 * **The one thing in this class that has to be executable is [prootPath], and it is handed in.**
 * It is the process the kernel execs — along with the loader proot needs beside it, the second of
 * the two artifacts an APK has to package for this to work at all. Everything else this class
 * names is *read*: proot ptraces a child, injects a loader, and makes the guest's dynamic loader
 * resolve inside the emulated root, so the Debian and the agent are data in app-private storage.
 * The path is a parameter and never a computed one, because the exec directory is read-only to
 * the app and nothing in this layer can put a file in it. [ProvisionPaths] names which directory
 * that is, and says what is shipped into it and what is still unmeasured about it.
 */
class ProotCommand(
    /**
     * The proot binary, or the loader that stands in for it: the one path here the kernel execs,
     * out of the read-only directory Android still grants exec on. Handed in by the caller because
     * the APK packaged it, not because this layer could.
     */
    private val prootPath: String,
    /** The unpacked Debian, named here so a report about it can point at it. */
    val rootfs: String,
    /** The device's own ABI. */
    private val host: Abi,
    /** The ABI of the rootfs, which is [host] on every device this app provisions. */
    private val guest: Abi,
    private val dataDir: String,
    private val visibleDir: String,
    private val agentDir: String,
    private val term: String = TERM,
    /** The guest's working directory; proot is given it as `-w` and the launcher is given it too. */
    val workDir: String = "/root",
    /**
     * A `qemu-<arch>-static` to run a foreign-architecture guest under, or null — which is the
     * case on every device this app provisions, and the reason is not a convenience: the rootfs
     * and the agent are downloaded for the device's own architecture, so proot translates nothing
     * and there is no emulator binary to name. The parameter exists because the argument is the
     * only way proot is told an architecture at all, and a caller that has a foreign rootfs has to
     * say so here rather than discover it as an `Exec format error`.
     */
    private val qemu: String? = null,
) {

    /**
     * The whole vector, `argv[0]` first, for one command inside the Debian.
     *
     * [command] is a path inside the guest, and the app's own arguments follow it verbatim. A
     * guest path is not a host path: `/usr/local/bin/omp` is [agentDir] on this phone, and the
     * only reason the name means anything to the kernel at all is the bind above.
     */
    fun argv(command: List<String>): List<String> {
        val argv = ArrayList<String>(24)
        argv += prootPath
        argv += "-r"
        argv += rootfs
        argv += "-b"
        argv += "$dataDir:$dataDir"
        argv += "-b"
        argv += "$visibleDir:$VISIBLE_MOUNT"
        argv += "-b"
        argv += "$agentDir:$AGENT_MOUNT"
        argv += "-w"
        argv += workDir
        argv += "-k"
        argv += "$dataDir/proot.pid"
        if (host != guest) {
            // No guess: proot's emulators are named `qemu-aarch64-static` and `qemu-x86_64-static`,
            // which is not an Android ABI name, and a name invented from one would be an
            // `Exec format error` four layers down. A foreign rootfs is a thing this app does not
            // have, so a caller that has one has to say which emulator it put on the device.
            argv += "-q"
            argv += qemu ?: throw IllegalArgumentException(
                "a $guest rootfs on a $host device needs a qemu binary named in ProotCommand; " +
                    "there is no way to derive that name from an ABI",
            )
        }
        argv += command
        return argv
    }

    /**
     * The guest's whole environment, and not the Android one with things removed.
     *
     * A delta is a bug waiting for the next phone: a `LD_LIBRARY_PATH` left over from the host, an
     * `ANDROID_ROOT` that means something to nothing, a `TERM` that was never set because the
     * process was started from a service rather than a terminal. So this is a complete map, and
     * the two entries that are not obvious are the two that matter: `TERM`, because a coding agent
     * that writes a line of colour escape sequences to a pipe it believes is a dumb terminal
     * produces output a user cannot read, and `PROOT_NO_SECCOMP`, because proot's seccomp filter
     * is refused by some Android 10+ kernels and the failure is an `EACCES` with no mention of
     * seccomp anywhere in it.
     */
    fun env(): Map<String, String> = linkedMapOf(
        "HOME" to workDir,
        "PATH" to omp.vm.VmSystem.PATH,
        "TERM" to term,
        "LANG" to "C.UTF-8",
        "TMPDIR" to "/tmp",
        "PROOT_NO_SECCOMP" to "1",
    )

    /** The argv for the agent itself, with [args] after it. */

    fun agentArgv(args: List<String>): List<String> = argv(listOf(AGENT) + args)

    /** The path of the real agent inside the Debian, which is the bind that puts it there. */
    fun agentPath(): String = AGENT

    companion object {
        /** The real `omp` binary, inside the Debian. */
        const val AGENT = "/usr/local/bin/omp"

        /** Where [agentDir] appears in the guest. */
        const val AGENT_MOUNT = "/usr/local/bin"

        /** Where the user's conversations folder appears in the guest, as in the namespace VM. */
        const val VISIBLE_MOUNT = "/mnt/omp"

        /** The terminal the app's own screen is. */
        const val TERM = "xterm-256color"
    }
}
