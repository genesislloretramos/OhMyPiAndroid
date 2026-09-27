package omp.vm.provision

import omp.shell.PlatformServices
import omp.shell.fs.FsException
import omp.shell.fs.Vfs
/**
 * Where every byte of a provisioned device lives, and how to tell whether it is already there.
 *
 * **There are two directories here, they are not interchangeable, and only one of them is
 * written.** The distinction is the whole deployment problem this layer has:
 *
 * | | what is in it | who puts it there | what it needs |
 * |---|---|---|---|
 * | [targetDir] — the *payload* | the Debian rootfs and the `omp` binary, plus the download state and proot's own temporary directory | **this layer**, at first run | ordinary app-private storage: writable by the app's uid, and readable |
 * | [execDirectory] — the *executable* | proot, and the loader proot needs beside it | **the APK**, at install time, out of `jniLibs` | nothing: it is read-only to the app and is the one location Android still grants `exec` on |
 *
 * **Why the payload is only read and never exec'd.** proot is a ptrace-based path emulator. It
 * does not ask the kernel to `execve` a guest binary: it ptraces a child, injects a small loader,
 * and makes the guest's `/lib64/ld-linux-aarch64.so.1` resolve inside the emulated root instead of
 * at a real absolute path. So the rootfs and the agent only have to be **readable**, which
 * `context.filesDir` is on every device — the same directory `omp.agent.store.KeyStore` keeps the
 * model key in, for the same reason.
 *
 * **Why [execDirectory] is read-only and this layer never writes there.** Google's own words, from
 * the change that made exec from the app home directory illegal: *"While exec() no longer works
 * for files within the application home directory, it continues to be supported for files within
 * the read-only /data/app directory. In particular, it should be possible to package the binaries
 * into your application's native libs directory and enable `android:extractNativeLibs=true`, and
 * then call exec() on the /data/app artifacts."* Writes there return permission denied, and the
 * directory is populated at install time from the APK and never at runtime by anything.
 *
 * **What has to be true on a real device, and is not yet measured.** Four things, and this build
 * asserts none of them:
 *
 * 1. **That [execDirectory] exists on this device.** The package manager creates it when it
 *    extracts native libraries; an app that ships none may not have one, which is what a null
 *    [omp.shell.PlatformServices.nativeLibraryDir] means, and no amount of unpacking conjures it.
 * 2. **That proot and its loader are in it.** The load-bearing set is **two** artifacts per ABI,
 *    not one: `proot` and the loader that stands in for it. **This build packages both, for all
 *    four ABIs** — eighteen files and 1,130,556 bytes under `app/src/main/jniLibs/<abi>/`, built
 *    from Termux's packages and named `libproot.so` and `libproot-loader.so` because the package
 *    manager extracts a `lib/<abi>/` entry only under a name of that shape.
 *    `android:extractNativeLibs="true"` in the manifest is what puts them in
 *    [omp.shell.PlatformServices.nativeLibraryDir] at install time. proot is GPL — 2.0 or later —
 *    and `libtalloc` and `libandroid-shmem` ride along under their own licences; the versions and
 *    the source offer are in README.md, and nothing under `jniLibs` is a project-owned work.
 *
 *    **What shipping them does not make true.** That the kernel will `execve` a file out of that
 *    directory on a real device, which is the whole of what is left to measure. `:app` does
 *    implement [ProotLauncher] and the helper is in the package, and none of it has ever run:
 *    a helper in an APK is not a helper that has run.
 * 3. **That the space below is real.** The free-space check is a refusal *before* the download,
 *    not a promise; see [Provisioner]'s own note on it.
 * 4. **That the guest's Apache ever answers on [WEB_DOCROOT].** [WebRoot] writes three files into
 *    a directory and [GuestPackages] puts the programs on the disk; neither of them starts a
 *    server, and a proot guest has no init to start one for it. The port it would answer on is the
 *    app's decision and is not in this build either.
 *
 * The default is the ordinary arrangement: the payload in
 * [omp.shell.PlatformServices.appFilesDir], the exec directory whatever the platform says and
 * nothing when it says nothing. A caller that has measured something different passes a different
 * directory; that is the whole point of the two being parameters.
 *
 * The layout inside [targetDir] is one directory named after this app's feature, so that a future
 * `vm reset` can name exactly what it is throwing away:
 *
 * ```
 * <targetDir>/omp/rootfs/…                the Debian, unpacked, with .omp-provisioned in it when
 *                                          it is whole
 * <targetDir>/omp/rootfs/var/www/html/…   the app's three pages, which the guest's Apache serves
 * <targetDir>/omp/rootfs/.omp-guest-packages   the guest's own apt install, when it has finished
 * <targetDir>/omp/bin/omp                 the real agent, with its executable bit set
 * <workDir>/provision/state               what has been fetched, and how much of it
 * <workDir>/provision/NAME.part           a download in progress, resumable from its own length
 * ```
 *
 * **The three marks are in three different places on purpose.** The rootfs mark is inside the tree
 * it describes, so a deleted [workDir] cannot make a broken Debian look whole; the guest mark is
 * inside the Debian, so a `vm reset` that throws the rootfs away takes the claim about its LAMP
 * with it; and the resume record is in [workDir], because it is a record of what happened and not
 * the thing that makes resuming possible.
 */
class ProvisionPaths(
    /**
     * Where the payload is unpacked: the app's own files directory, which is writable and readable.
     * The **only** directory this layer writes to.
     */
    val targetDir: String,
    /**
     * The read-only directory Android still grants `exec` on, or null when the platform names
     * none. Nothing here is ever written to it: proot and its loader are packaged in the APK's
     * `jniLibs` and extracted at install.
     */
    val execDirectory: String?,
    /** Where partial downloads and the resume record live; app storage, because both are data. */
    val workDir: String,
) {
    val installDir: String = "$targetDir/omp"
    val rootfsDir: String = "$installDir/rootfs"
    val agentDir: String = "$installDir/bin"
    val agentBinary: String = "$agentDir/omp"
    val downloadDir: String = "$workDir/provision"

    /** The resume record. Lost or deleted it costs a re-stat, never a re-download. */
    val stateFile: String = "$downloadDir/state"

    /**
     * The mark that says the rootfs is whole.
     *
     * It lives *inside* the tree it describes, so it survives a deleted [stateFile] and it cannot
     * be left behind by a half-finished unpack the way a file in a sibling directory can: the
     * unpack writes it last, or a crash in the middle leaves a rootfs with no marker and a second
     * run unpacks it again rather than running a truncated Debian.
     */
    val rootfsMarker: String = "$rootfsDir/$ROOTFS_MARKER"

    /**
     * The directory inside the unpacked Debian that Apache serves the app's pages from.
     *
     * **`/var/www/html` is Debian's own packaged `DocumentRoot`**, from
     * `/etc/apache2/sites-enabled/000-default.conf`, and it is here rather than somewhere more
     * tasteful because it is the only path that works with no configuration at all: the chat page
     * fetches `/app.css`, `/app.js` and `/api/…` by absolute path, so a document root served at
     * any other URL would break every one of them. It does not exist in a netboot image until
     * something makes it, and [WebRoot] makes it. See that class for the paths that were
     * rejected and why.
     */
    val webRoot: String = "$rootfsDir$WEB_DOCROOT"

    /**
     * The mark that says the guest's own `apt` install finished.
     *
     * Inside the tree, for the reason [rootfsMarker] is: it cannot be left behind by a run that
     * stopped half way, and it does not care whether the state file survived. What it has to be
     * careful about is the other direction — a guest whose `apt` failed must not be able to find
     * it, or the packages are asked for on every boot and the user's data pays for it twice.
     */
    val guestMarker: String = "$rootfsDir/$GUEST_MARKER"

    /**
     * `dpkg`'s own record of what is installed, read off the host side of the payload.
     *
     * **The one file inside the Debian this build can read after an install and learn something
     * true from.** proot makes the rootfs an ordinary directory to everything outside it, so
     * `/var/lib/dpkg/status` is readable through the [omp.shell.fs.Vfs] with no guest process and
     * no network — and it is `dpkg` itself saying what it installed and how big, which is the only
     * way to report a *real* figure for a step whose only other number is an estimate. See
     * [omp.vm.provision.GuestPackages.measuredKib].
     */
    val dpkgStatus: String = "$rootfsDir/var/lib/dpkg/status"

    /**
     * The directory inside the unpacked Debian that the guest's own PHP goes in.
     *
     * **`/usr/local/share/omp`, and not under the document root.** A file under
     * `/var/www/html` is a file a request can fetch, and what is in this directory is the code that
     * opens the guest's database and starts the guest's agent. `webRoot` exists to be served and
     * this one exists to be `require`d, and a directory that is both is a directory where a
     * routing mistake is a source disclosure.
     *
     * It is joined onto [rootfsDir] and never written to directly, so there is exactly one spelling
     * of it in the project — the same reason [WEB_DOCROOT] is a constant.
     */
    val guestApiDir: String = "$rootfsDir$GUEST_API_DIR"

    /**
     * The Apache drop-in that makes the guest's Apache reach [guestApiDir], still in
     * `conf-available`.
     *
     * **`conf-available` and not `conf-enabled`, on purpose.** The link between the two is
     * `a2enconf`, and [omp.vm.provision.GuestPackages] runs it *after* the tree is written —
     * `a2enconf` on a file that is not there exits non-zero, and a step that fails for that reason
     * would send the next boot back through 57 MB of `apt`. A file in `conf-available` is inert
     * until somebody links it, which is the same property a `mods-available` module has.
     */
    val guestConf: String = "$GUEST_CONF_DIR/$GUEST_CONF_NAME.conf"

    /** The name `a2enconf` is given, and the file's name without its extension. */
    val guestConfName: String = GUEST_CONF_NAME

    fun partial(name: String): String = "$downloadDir/$name.part"

    /** What is on this device, read from the disk and from nothing else. */
    fun state(vfs: Vfs): ProvisionState = ProvisionState(
        rootfsInstalled = there(vfs, rootfsMarker),
        agentInstalled = there(vfs, agentBinary),
        guestInstalled = there(vfs, guestMarker),
    )

    /**
     * The resume record as it is on disk: artifact name to `<received> <status>`, in the order the
     * lines were written.
     *
     * **It lives here rather than in the [Provisioner] because two things read it and one of them
     * is not a downloader.** `omp doctor` reports what the last attempt said without starting one,
     * and a second parser for a format this file also renders would be a second thing to get
     * subtly wrong. A record file that is not there is an empty map and never an exception: a
     * device that has never provisioned anything is the ordinary case, and asking about it must
     * not fail.
     */
    fun records(vfs: Vfs): Map<String, String> {
        val text = try {
            String(vfs.readBytes(stateFile), Charsets.UTF_8)
        } catch (e: FsException) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split(' ', limit = 2)
            if (parts.size == 2) out[parts[0]] = parts[1]
        }
        return out
    }

    private fun there(vfs: Vfs, path: String): Boolean = try {
        vfs.stat(path)
        true
    } catch (e: FsException) {
        false
    }

    companion object {
        /** The file a finished unpack leaves inside the rootfs it unpacked. */
        const val ROOTFS_MARKER = ".omp-provisioned"

        /**
         * Where the guest's own PHP lives inside the Debian, as one constant.
         *
         * `/usr/local/share` is Debian's own directory for files a package or an administrator has
         * put there, and it is where this belongs: not in `/usr/lib`, which the packages own, and
         * not in the document root, which is served.
         */
        const val GUEST_API_DIR = "/usr/local/share/omp"

        /** Debian's own directory of configuration snippets that are not live until linked in. */
        const val GUEST_CONF_DIR = "/etc/apache2/conf-available"

        /** The drop-in's name without its extension, so the file and `a2enconf` agree. */
        const val GUEST_CONF_NAME = "omp-guest"

        /**
         * The document root inside the Debian, as one constant.
         *
         * It is joined onto [rootfsDir] and never written to directly, so there is exactly one
         * spelling of it in the project — the same reason [ROOTFS_MARKER] is a constant.
         */
        const val WEB_DOCROOT = "/var/www/html"

        /** The file a finished `apt` install leaves inside the rootfs it installed into. */
        const val GUEST_MARKER = ".omp-guest-packages"

        /**
         * The ordinary arrangement, built from what the platform will say.
         *
         * The payload goes to the app's own files directory because that is where the KeyStore
         * already keeps the model key and for the same reason: it is the one directory an app can
         * always write and always read. The exec directory is whatever
         * [omp.shell.PlatformServices.nativeLibraryDir] says and **nothing** when it says nothing
         * — there is deliberately no fallback to the payload, because the payload is not an
         * exec location on any current Android and a wrong answer here would be a directory this
         * layer appears to be able to use.
         */
        fun inAppStorage(services: PlatformServices): ProvisionPaths = ProvisionPaths(
            targetDir = services.appFilesDir(),
            execDirectory = services.nativeLibraryDir(),
            workDir = services.appFilesDir(),
        )
    }
}

/**
 * Whether the real Debian, the real agent and the guest's own LAMP are on this device.
 *
 * Three facts and no opinion, so that [Boot] can say which of the two agents is answering without
 * asking anybody's memory: the Kotlin agent that is in this build always is, and it is the answer
 * whenever the first two are false. The third is not about which agent answers — it is about what
 * the Debian inside is holding, and it is here because the cost of it is one of the numbers a
 * user is asked to agree to.
 */
data class ProvisionState(
    val rootfsInstalled: Boolean,
    val agentInstalled: Boolean,
    /**
     * True when the guest's own `apt` install finished, as recorded by [ProvisionPaths.guestMarker]
     * inside the rootfs.
     *
     * It defaults to false because a caller that has not looked has not installed it, and an
     * absent mark is exactly that: [GuestPackages] will run the sequence again rather than
     * assuming a machine did something nobody recorded.
     */
    val guestInstalled: Boolean = false,
) {
    /**
     * True when the real `omp` binary is here *and* there is a Debian to run it in.
     *
     * The agent alone is not a usable answer — a glibc binary with no userland to find its loader
     * in, and no proot to find the loader for it, is a download and nothing more — so a device with
     * the binary and no rootfs is reported as not having the real agent, which is what it is.
     */
    val realAgentInstalled: Boolean get() = rootfsInstalled && agentInstalled

    companion object {
        /** Nothing is installed, which is the state of every device before its first run. */
        val NONE = ProvisionState(false, false)
    }
}
