<h1 align="center">omp terminal</h1>

<p align="center">A real terminal for your Android phone, written in Kotlin.</p>

<p align="center">
  <img src="docs/screenshot.png" alt="omp terminal running dumpsys battery" width="300">
</p>

---

## What it is

A terminal emulator for Android where you can explore your own device with the commands you
already know:

```
ls  cat  grep  find  sed  du  tree  zip  wc  sort  zipgrep …
df  ps  top  free  uname  getprop  pm  am  dumpsys  settings  wm  screencap  curl
```

It is not a wrapper around toybox. The shell — the lexer, parser, expander, line editor and the
terminal emulator itself — is written from scratch in Kotlin. **There is no root, no NDK, no
bundled binary and no `Runtime.exec`.** Every command is Kotlin code running inside your app's
own sandbox.

```
$ ls /proc | head -3
6263
acpi
asound

$ cat /proc/stat
cat: /proc/stat: Permission denied

$ cat /proc/meminfo | head -1
MemTotal:         2532432 kB
```

That asymmetry is the point of the app. Android closes most of `/proc` to ordinary apps, and this
shell tells you so instead of making something up.

## Build it

You need JDK 21 and an Android SDK with platform 35.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test             # 276 tests: the shell's whole behaviour, on the JVM, in a few seconds
```

## Use it

Type a command and press Enter. The bar at the bottom is always there, so every key is one tap
away even if your keyboard eats it.

- **CTRL**, **ALT** arm a modifier for the next key — that is how Ctrl-C and Ctrl-L work without a
  hardware keyboard.
- **ESC**, **TAB**, arrows, **HOME**/**END**, **PGUP**/**PGDN** and the punctuation block are on the
  bar.
- **Long-press and drag** to select text; it goes to the clipboard. Drag vertically to scroll back.
- **Pinch** to change the text size.
- **Back** stops a running command; press it twice to exit. `Ctrl-D` on an empty line also exits.
- **Ctrl-C** cancels whatever is running.

- There is a second shell inside this one — see **The VM**, below.

### Some commands are deliberately missing

Android does not let an app do these things, and this shell would rather say why than pretend:

| not there | why |
|---|---|
| `chmod`, `chown` | need privileges an app does not have |
| `mount -o`, `lsblk` | `/proc/mounts` and `/sys/block` are closed to apps |
| `dmesg`, `logcat` | `READ_LOGS` is a signature-level permission |
| `netstat` | `/proc/net` is excluded from untrusted apps |
| `su`, `sudo`, `setprop` | nothing to escalate to |
| `settings put` | needs the signature-level `WRITE_SECURE_SETTINGS` |
| `ps` shows one process | other apps' `/proc/<pid>` is closed; yours is all there is |

`help` lists all of them with the same one-line reasons.

## The VM

There is a second shell inside this one. It is an **in-process userspace VM** — Ubuntu Server
24.04.1 LTS, minimal, running on a directory in the app's own storage. No image to download, no
kernel to boot, no native code, no `Runtime.exec`, and no root: every command in it is the same
Kotlin the phone shell runs, with a different `Vfs` behind it.

```
$ vm
vm: booted, Ubuntu 24.04.1 LTS, 1 process(es) in the pid namespace
disk: /data/user/0/com.omp.terminal/files/rootfs (32881 bytes on disk)
packages: 7 installed
  unit omp-vmd.service: active (running)
  unit systemd-journald.service: active (exited)
  unit vm-hostbridge.service: active (exited)
/mnt/android: bound to /storage/emulated/0
note: root inside the VM is a name the VM kernel answers to, not a privilege escalation on the
phone; the app's real uid is 10123 and it is in /sys/omp/android/uid
```

`vm enter` drops you into it on this terminal — same screen, same keyboard, prompt says `ubuntu` —
and `exit`, `Ctrl-D` or **Back** brings you back.

```
$ vm enter
ubuntu:~$ cat /etc/os-release
PRETTY_NAME="Ubuntu 24.04.1 LTS"
ID=ubuntu
ubuntu:~$ exit
back on the phone; the VM is still booted and still on disk
```

You also get `apt`, `dpkg`, `systemctl` and `journalctl`, which the phone shell deliberately does
not have, `/mnt/android`, which is a real bind onto the phone's shared storage, and `/mnt/omp`,
which is the conversations folder — one bind, one directory, and a file written in there is a file
under *Internal storage ▸ Documents*.

`vm exec 'uname -a'` runs one line in there and returns its status. `vm mounts` and `vm services`
print the mount table and the unit list. `vm reset` throws the whole rootfs away — and refuses to
without `--force`, after printing the directory and its size.

### What it is not

It is not a hypervisor, not QEMU, not an emulator and not a container. There is one process. There
is no second uid to escalate to and no memory isolation: `root` inside the VM is a **name the VM
kernel answers to**, which is why the real one is one `cat` away in `/sys/omp/android/uid`.
`docs/vm.md` has the design and the limits in full.

## Conversations

`omp` is what the app opens on, and it is the same command inside the VM as outside it — it asks
the session's own filesystem where the container is rather than being told.

**A conversation is one plain folder, and that folder is the project.** `omp new photos` makes
`Internal storage ▸ Documents ▸ omp/photos`, enters it, and puts both of its names in the
environment: `OMP_WORKSPACE` for the path commands take, `OMP_WORKSPACE_REAL` for the one a file
manager, a cable or another app needs. A folder renamed in the file manager is listed under its new
name, because the directory owns its name and the app only keeps the old one as history.

```
$ omp
conversations in /storage/emulated/0/Documents/omp  (/mnt/omp in the VM):
  #  name    modified          where
  1  photos  2026-09-27 03:08  /storage/emulated/0/Documents/omp/photos

type a number to continue, or n for a new one: 1
omp: in photos
  OMP_WORKSPACE=/storage/emulated/0/Documents/omp/photos
  OMP_WORKSPACE_REAL=/storage/emulated/0/Documents/omp/photos
```

`omp ls` lists and changes nothing. `omp rm NAME` refuses without `--force`, printing the real path
and what is under it first, and says so again for a folder this app did not make. Ctrl-C at the
prompt cancels it: no half-created folder, no session moved.

The whole thing needs the all-files grant, because `Documents` does. Without `grant-storage` it
says exactly that and creates nothing — not a folder that looks like it worked. And on a pipe, in
a script or under `vm exec`, `omp` prints the list and the two options and returns, rather than
waiting forever for a keypress that is never going to arrive.

## Permissions

| permission | why |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | to browse shared storage. Not granted at install: run `grant-storage`, or tap the hint in the prompt. |
| `INTERNET` | `curl` |
| `QUERY_ALL_PACKAGES` | without it `pm list packages` returns almost nothing on Android 11+ |
| `KILL_BACKGROUND_PROCESSES` | `am force-stop` |

**This is not a Play Store app.** All-files access and package visibility are both restricted by
Play policy. It is a sideloaded developer tool; the release APK here is debug-signed.

## Notes from building it

A few things that only showed up on a real device, kept here so the next person does not lose an
afternoon to them:

- `/system/build.prop` is `0600 root:root` since Android 10 and **no app can read it**, so
  `getprop` takes its `ro.build.*` values from `android.os.Build` instead.
- `Attr.fg()` hands back 24-bit RGB, which a `Paint` reads as fully transparent. Every colour has
  to be re-packed opaque before it reaches a canvas.
- `Screen` is the terminal, not the tty, so the output pump has to do ONLCR itself or every line
  stair-steps to the right.
- `java.lang.ProcessHandle` does not exist on Android.

## Layout

```
core/   the shell. Plain JVM, no android.* imports, so it unit-tests in milliseconds.
app/    the Activity, the view that draws the screen, and the platform commands.
```

## License

Do what you want with it.
