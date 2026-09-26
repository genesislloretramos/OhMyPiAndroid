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
./gradlew :core:test             # 87 tests, all on the JVM, about a second
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
