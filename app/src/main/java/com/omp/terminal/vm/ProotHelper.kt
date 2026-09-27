package com.omp.terminal.vm

import omp.vm.provision.Abi
import java.io.File

/**
 * Where the proot this APK packages actually is, and the three things that have to be arranged
 * before the kernel will run it.
 *
 * **The helper is not looked up by name — the package manager already did that.** The four ABIs
 * this app packages live at `lib/<abi>/…` in the APK, and installing it copies the ones matching
 * the device into `applicationInfo.nativeLibraryDir`, which is already the device's own ABI
 * directory. There is no search to perform: [omp.shell.PlatformServices.nativeLibraryDir] *is* the
 * answer to "where is proot for this device", and the only thing left is to put a name on it.
 *
 * **The names are not the ones the packages upstream use, and each rename is load-bearing.**
 *
 * | upstream | here | why |
 * |---|---|---|
 * | `usr/bin/proot` | `libproot.so` | the package manager only extracts a `lib/<abi>/` entry whose name starts with `lib` and ends with `.so`; in a non-debuggable build that test is not an exception. A file called `proot` is silently never extracted, and `nativeLibraryDir` is populated at install time, so the symptom is a directory that exists and is empty. |
 * | `usr/libexec/proot/loader` | `libproot-loader.so` | the same rule, and this one is exec'd too — see below. |
 * | `usr/lib/libandroid-shmem.so` | `libandroid-shmem.so` | unchanged; it already satisfies the rule. |
 * | `usr/lib/libtalloc.so.2.4.3` | `libtalloc.so` | **renamed, and the name on disk is not the name the loader asks for.** proot's `DT_NEEDED` entry is `libtalloc.so.2`, and the linker looks up that exact string; `libtalloc.so.2` cannot be extracted, so the file is shipped under a name the extractor accepts and [installLibraries] puts it where the linker is looking, under the name the linker asks for. |
 *
 * **Why the loader is in the native library directory and not in app storage.** proot does not
 * ptrace-inject the loader: it substitutes the loader's path for the program's in the `execve` the
 * guest is making, so the **kernel** execs it — `src/execve/enter.c`: *"Execute the loader instead
 * of the program"*, then `set_sysarg_path(tracee, loader_path, SYSARG_1)`. Android 10 denies
 * `execute_no_trans` (the `execve(2)` permission) on `app_data_file` for any app with
 * `targetSdkVersion >= 29`, which this app has, so the loader is in the one directory Android
 * still lets this uid execute from. The loader is statically linked — zero `DT_NEEDED` entries,
 * checked on all four ABIs — so it needs nothing beside it.
 *
 * **Why the libraries are in app storage and the binaries are not.** The Android 10 change denies
 * `execute_no_trans` and nothing else: `allow untrusted_app_all app_data_file:file { r_file_perms
 * execute }` is still in `untrusted_app_all.te`, and `execute` is the permission
 * `mmap(PROT_EXEC)` needs. Loading a shared library out of the app's own storage is exactly the
 * thing that restriction deliberately left working. So the two shared libraries — which the
 * loader maps, and never executes — are copied to [libraryDir] and named what the linker asks
 * for, and only the two files the kernel has to exec stay in the read-only directory.
 *
 * **What [env] adds, and the one thing it cannot avoid doing to the guest.** Android's linker
 * resolves a main executable's `DT_NEEDED` in exactly one order: `LD_LIBRARY_PATH`, then the
 * referrer's `DT_RUNPATH`, then the namespace's search paths (`/system/lib64` and friends). It
 * never searches the executable's own directory, and this proot's `DT_RUNPATH` is Termux's
 * `/data/data/com.termux/files/usr/lib`, which does not exist for this app. `LD_LIBRARY_PATH` is
 * therefore the only channel — honoured because the kernel leaves `AT_SECURE` clear for an app
 * that is neither setuid nor file-capped, and not rejected by `is_accessible()` because
 * `nativeLibraryDir` is `apk_data_file` and app storage is a permitted path. `PROOT_LOADER` is
 * mandatory for the same reason and a stronger one: the Termux build sets
 * `PROOT_UNBUNDLE_LOADER=/data/data/com.termux/files/usr/libexec/proot`, so proot's fallback is a
 * path that does not exist for this app, and without the variable the guest's first `execve` is an
 * `ENOENT` — `src/execve/enter.c`, `getenv("PROOT_LOADER") ?: PROOT_UNBUNDLE_LOADER "/loader"`.
 *
 * The cost of `LD_LIBRARY_PATH` is that proot reads it out of its **own** environment and appends
 * it to the guest loader's `LD_LIBRARY_PATH` (`src/execve/ldso.c`, `rebuild_host_ldso_paths`). A
 * glibc loader will therefore search an Android directory for its own libraries before the ones it
 * normally would. Two files are in it and neither is a glibc library, so the search misses and
 * carries on, and there is no channel for this that does not have the same effect.
 *
 * `PROOT_TMP_DIR` is deliberately **not** set. It is in the binary's strings and it looks
 * required, but the only writer of a temporary file is `extract_loader`, and that function is
 * `#if !defined(PROOT_UNBUNDLE_LOADER)` — compiled out of exactly this build. Setting a variable
 * nothing reads would be a claim about a mechanism that is not there.
 *
 * **None of this has been run on a device.** The paths are names in a directory, the libraries are
 * copies, and the two arguments proot accepts are the ones its own source says it reads. Whether
 * the kernel will exec a file from `nativeLibraryDir` on this OEM's build, and whether `ptrace` is
 * permitted at all, are questions no JVM can answer.
 */
class ProotHelper(
    /**
     * `applicationInfo.nativeLibraryDir`, or null when the platform has not said. Null is a real
     * answer — an app that packages no `lib/<abi>/` entry for this device gets no such directory —
     * and it is not a reason to fall back to app storage, which is not an exec location.
     */
    execDirectory: String?,
    /**
     * The app's own storage, `context.filesDir`. Writable, and — the reason [libraryDir] is under
     * it rather than under [execDirectory] — mappable with `PROT_EXEC` even though it can never be
     * exec'd from.
     */
    targetDir: String,
    /**
     * This device's ABI, or null when the platform named none it knows. Checked rather than assumed
     * because a fifth entry in [Abi] would mean a fifth directory under `jniLibs/`, and a helper
     * that silently resolved to a path in an empty directory is a worse answer than a sentence.
     */
    private val abi: Abi?,
) {

    /** The exec directory, or null. */
    private val execDir: String? = execDirectory?.trimEnd('/')?.ifEmpty { null }

    /**
     * Why there is no proot to run here, in one sentence, or null when there is one.
     *
     * A sentence and not an exception, for the reason [ProotProcessLauncher] writes to a stream
     * instead of throwing: a phone with an architecture this build did not package for is an
     * ordinary thing to find out about, and the user has to be able to read what it was.
     */
    val refusal: String?
        get() = when {
            execDir == null ->
                "this device has not given the app a native library directory, so the proot this " +
                    "APK packages was never extracted onto it"
            abi == null ->
                "this device reports an architecture this app does not know, so the APK has no " +
                    "proot for it"
            else -> null
        }

    /** The proot the kernel execs, or null when [refusal] is not null. */
    val prootPath: String? get() = if (refusal == null) "$execDir/$PROOT" else null

    /** The loader proot execs in the guest's place, and which must therefore be exec-able too. */
    val loaderPath: String? get() = if (refusal == null) "$execDir/$LOADER" else null

    /** Where the shared libraries are put, under the names the dynamic linker looks up. */
    val libraryDir: String = "$targetDir/omp/$LIBRARIES"

    /**
     * The guest's environment plus the two variables proot needs before the guest exists.
     *
     * The guest's variables are untouched and stay first, because a caller reading this map wants
     * to see that [ProotCommand.env] came through whole; [ProotCommand] and its test are not moved
     * for a platform detail, and [ProotProcessLauncher] keeps its contract of running the process
     * it was handed with the environment it was handed.
     */
    fun env(guest: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>(guest.size + 2)
        out.putAll(guest)
        val loader = loaderPath ?: return out
        out[LD_LIBRARY_PATH] = libraryDir
        out[PROOT_LOADER] = loader
        return out
    }

    /**
     * Puts the shared libraries where [env] says they are, and returns what went wrong.
     *
     * Empty is success. Each entry is a sentence naming the file, because "the linker could not
     * find `libtalloc.so.2`" says less than "this file is not here and this is why".
     *
     * The copy is idempotent on length: [libraryDir] is app-private and only ever written here, so
     * a file already there with the source's length is the file this would have written. It is
     * forty-odd kilobytes and it happens at most twice a boot.
     */
    fun installLibraries(): List<String> {
        val exec = execDir ?: return listOf(NOTHING_TO_COPY)
        val dir = File(libraryDir)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            return listOf("could not create $libraryDir, which is where proot's shared libraries have to be")
        }
        val problems = ArrayList<String>()
        for ((shipped, soname) in LIBRARIES_SHIPPED) {
            val from = File(exec, shipped)
            val to = File(dir, soname)
            if (!from.isFile) {
                problems += "$shipped is not in $exec, so $soname cannot be put where the linker looks"
                continue
            }
            if (to.isFile && to.length() == from.length()) continue
            try {
                from.copyTo(to, overwrite = true)
            } catch (e: java.io.IOException) {
                problems += "could not put $soname in $libraryDir: ${e.message}"
            }
        }
        return problems
    }

    companion object {
        /**
         * proot itself. The `lib` prefix and the `.so` suffix are not a convention this project
         * invented: AOSP's extractor keeps a `lib/<abi>/` entry only if the name starts with `lib`
         * and ends with `.so`, and skips it silently otherwise.
         */
        const val PROOT = "libproot.so"

        /** The loader proot execs instead of the guest program. Statically linked; see the KDoc. */
        const val LOADER = "libproot-loader.so"

        /** Under [libraryDir], the file the linker needs under a name the extractor would refuse. */
        const val LIBRARIES = "proot-libs"

        /** Set by [env]; read by the dynamic linker before anything else. */
        const val LD_LIBRARY_PATH = "LD_LIBRARY_PATH"

        /** Read by proot itself, and mandatory for a build with `PROOT_UNBUNDLE_LOADER` set. */
        const val PROOT_LOADER = "PROOT_LOADER"

        /**
         * What [installLibraries] says when there is no exec directory to copy out of. The same
         * fact [refusal] reports, reached by a different question: *can* there be a proot here, as
         * against whether there is one.
         */
        const val NOTHING_TO_COPY =
            "there is no native library directory to copy proot's shared libraries out of"

        /**
         * The name the APK ships each library under, paired with the name the linker looks up.
         *
         * Equal for one of them and not the other, which is the whole of the difference between
         * this being a two-line table and this being impossible.
         */
        val LIBRARIES_SHIPPED: Map<String, String> = linkedMapOf(
            "libtalloc.so" to "libtalloc.so.2",
            "libandroid-shmem.so" to "libandroid-shmem.so",
        )
    }
}
