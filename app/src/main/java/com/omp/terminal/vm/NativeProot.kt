package com.omp.terminal.vm

import omp.vm.provision.ProotLauncher
import java.io.OutputStream

/**
 * The [ProotLauncher] that runs the proot this APK packages, with the three things it needs said
 * out loud before it starts.
 *
 * **This is a decorator and not a rewrite, and the reason is that [ProotProcessLauncher] is right.**
 * Its contract is that the process it builds is the process it was handed — the argument vector, the
 * environment, the working directory, one stream — and `ProotProcessLauncherTest` pins every part of
 * that. What a phone adds is not a better implementation of that contract; it is a set of facts
 * about where the binary is that the JVM half has no way to know. So they live here, in a class that
 * can be tested by reading the map it produces, and the launcher underneath keeps doing exactly what
 * its test says it does.
 *
 * **What gets added, and what deliberately does not.** [ProotHelper.env] adds `LD_LIBRARY_PATH` and
 * `PROOT_LOADER`, and says why in its own KDoc: the dynamic linker never searches the executable's
 * own directory, and this proot's `DT_RUNPATH` is Termux's, so there is nowhere else for
 * `libtalloc.so.2` to come from. Nothing is added to the *guest's* environment beyond that, and
 * `PROOT_NO_SECCOMP` is not touched — it is already in [omp.vm.provision.ProotCommand.env], and
 * setting it twice would be two sources of truth for one fact.
 *
 * **A refusal is a line and a status, never a throw.** [ProotHelper.refusal] is the case where this
 * build has no proot for this device, and the honest way to report that is the same way the launcher
 * reports everything else: something in the output stream the user is already reading, and a status
 * no guest command produces. An app that will not open because a device is an architecture nobody
 * packaged for is a worse outcome than a terminal that says so.
 *
 * **What is still unmeasured, and is the whole of what is left.** This class builds a process; it
 * does not run one, and there is no device in this repository. Whether the kernel will `execve` a
 * file out of `nativeLibraryDir` on any particular OEM build, whether `LD_LIBRARY_PATH` is cleared
 * before the linker reads it, whether the guest's rootfs has a glibc loader in it, and whether the
 * OEM's SELinux policy permits `ptrace` at all — none of it is answered here, and
 * [ProotProcessLauncher]'s KDoc lists the same questions from the other side.
 */
class NativeProot(
    /** Where the proot is, and what has to be arranged before it will start. */
    private val helper: ProotHelper,
    /** Where a refusal goes. The same stream the launcher's own output goes to. */
    private val out: OutputStream,
    /** The half that turns a vector, an environment and a directory into a status. */
    private val launcher: ProotLauncher = ProotProcessLauncher(out),
) : ProotLauncher {

    override fun run(argv: List<String>, env: Map<String, String>, cwd: String): Int {
        helper.refusal?.let {
            return refuse("proot: $it.")
        }
        val problems = helper.installLibraries()
        if (problems.isNotEmpty()) {
            return refuse("proot: " + problems.joinToString("; ") + ".")
        }
        return launcher.run(argv, helper.env(env), cwd)
    }

    /**
     * Says why and returns the launcher's own "it never started" status, so a caller can tell a
     * refusal from a guest that failed and from a guest that was stopped.
     */
    private fun refuse(reason: String): Int {
        out.write((reason + "\n").toByteArray())
        out.flush()
        return ProotProcessLauncher.NOT_STARTED
    }
}
