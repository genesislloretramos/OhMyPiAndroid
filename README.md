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
./gradlew :core:test             # 575 tests: the shell's whole behaviour, on the JVM, in about half a minute
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

### The agent

Inside a conversation, `omp` is not the launcher any more — it is the coding agent. The words that
mean something there are `omp` itself, `run`, `update`, `key` and `help`, plus `--yes` on `omp`.

```
$ omp new fotos
omp: created and in fotos
  OMP_WORKSPACE=/storage/emulated/0/Documents/omp/fotos
  OMP_WORKSPACE_REAL=/storage/emulated/0/Documents/omp/fotos

$ omp update && omp
omp agent 0.1.0
  folder:     /storage/emulated/0/Documents/omp/fotos
  provider:   openai
  endpoint:   https://api.openai.com/v1/chat/completions
  model:      gpt-4o-mini
  key:        /data/user/0/com.omp.terminal/files/agent/openai.key (43 bytes, never printed)
  transcript: …/fotos/.omp/transcript.jsonl — 0 entries, 0 of 8388608 bytes
  tools:      read_file, list_dir, search, write_file, edit_file — inside this conversation only
  writes:     every write_file and edit_file is put to the user first;
              'omp --yes' approves them all, and the transcript records it
  There is no run tool: the shell in this VM can 'vm reset' and throw away the namespace
  this conversation is in, and the model picks its own command lines. Use the file
  tools above; the user can run a command themselves.
  state:      written by this build
  this command does not download anything and cannot: the agent is Kotlin inside the app you are
  already running, so a newer agent is a newer build of this app
omp agent 0.1.0, in this conversation's folder:
  /storage/emulated/0/Documents/omp/fotos
omp[fotos] > what is in here?
```

**The folder is the conversation.** Every question and every answer is appended to
`.omp/transcript.jsonl` inside it, so the next `omp` in the same folder continues the exchange —
including one the user interrupted half way through, which is recorded as what had arrived.
`Ctrl-C` stops an answer, closes the stream and writes it down; `Ctrl-C` on an empty prompt leaves.

**A Ctrl-C on a stalled answer is a quarter of a second plus a second, and that is arithmetic
rather than a hope.** Nothing can interrupt a read already parked inside a socket, so the transport
sets a 250 ms read timeout as a *checkpoint* rather than a limit and looks at the clock and a cancel
flag every time it fires; the watchdog asks the shell's flag once a second and calls `close()`. So
the flag is issued within a second of the keystroke and acted on within a quarter of a second of
that. Five minutes of silence is the actual limit, and passing it ends the answer with the host
named and roughly how long it was quiet.

**The key is typed, never echoed and never shown.** `omp key` asks for it with the terminal's echo
off and puts it through `KeyStore`, in the app's private `files/agent/<provider>.key` — deliberately
outside the VM namespace and outside `Documents/omp`, so no path the agent can reach leads to it.
`omp key --show` names the provider, the file and a byte count; there is no code path from it to
the secret.

```
$ omp key                     # prompted, echo off
$ omp key --show              # provider, file, byte count
$ omp key --list              # every provider that has a key
$ omp key --forget openai     # exactly one
```

**The agent has five file tools, and they reach one folder.** `read_file`, `write_file`,
`edit_file`, `list_dir` and `search`, declared to the model with the description and the parameters
that are the model's only documentation of them. Everything they do goes through one class,
`omp.agent.tools.Sandbox`, and every path it is asked about is checked there and nowhere else: one
decision, one place, and no way to reach the filesystem from the agent without an answer from it.

**The boundary is this conversation's folder, and it is the strict answer.** The user permitted
`Documents/omp`; the agent was then given *one* conversation inside it, and the container above is
refused — **even for a read**, because listing somebody else's conversations is a capability it
was not given. Names are compared exactly: nothing folds case and nothing normalises Unicode, so
`Photos` and `photos` are not the same directory to a model that was not given either spelling.
A symlink is followed and its *target* is what is checked, the way the kernel does it. **A file on
the phone is not a sandbox boundary, and the app does not claim one**: this stops the accident — a
model that is confidently wrong about where it may write, a `..` that walked out of the folder —
and it is not a wall against a determined caller, which in this app would be Kotlin inside the app
the user already installed.

**The conversation's own `.omp` folder is inside the folder and is still not the model's.** The
transcript and the state file are refused — **for a write and for a read** — because a model that
can write `state.json` has the *next* request sent to a `base_url` of its own choosing, and a
model that can read the transcript is reading this conversation back in its own words. `list_dir`
still lists it as an entry, because it is a real folder the user can see in a file manager; asking
to go *into* it is what is answered, by the same class and in the same words.

**Every write is put to the user, one keypress at a time.** Before any `write_file` or `edit_file`
the agent prints the path, the same path as a file manager shows it, the size, and for an edit the
old and the new text. `y` goes ahead; anything else cancels that call and the model is told the user
declined. A Ctrl-C cancels the call and gives the terminal back. **`omp --yes` approves every call
in that turn without asking, and the transcript records `auto` rather than `y`** — so a folder read
afterwards can tell a write nobody looked at from one a person did.

An `edit_file` whose old or new text carries a control character — a carriage return, a bell, an
escape sequence — is refused **before the question is asked**, because that question is the one
place a human answers with a single keystroke and it would be drawn in the model's own words. The
way out is `write_file`, whose prompt is made of the path and the size and shows no text at all.
For the same reason, a tool result on the screen spells those characters out rather than sending
them: a file the user wrote can hold a carriage return, and it must not be able to rewrite the
line above it.

**There is no `run` tool, and that is a decision.** A shell inside this VM is a shell that can
`vm reset` and destroy the namespace the conversation is in, and the model picks its own command
lines — and a line of shell is not something an approval prompt on a phone can honestly summarise.
So the file tools are here and the shell is not; a user who wants a command run can run it in the
terminal they are already sitting at. A model that asks for one anyway gets a tool result naming
the five and saying why, and the loop carries on. So does a model that names a tool this build does
not have, that sends arguments which are not a JSON object, or that asks for a path outside the
folder: every refusal is a result the model can read and recover from, never an exception that ends
a conversation. One question gets at most ten tool rounds, and hitting that is said out loud and
written to the transcript rather than being silent.

`omp update` deliberately prints no progress bar, no version check and nothing that implies a
download, because nothing in this app can download anything. It reports the version, the provider,
the endpoint, the model, the key's file, and the transcript's size against its cap.

`omp ls` lists and changes nothing. `omp rm NAME` refuses without `--force`, printing the real path
and what is under it first, and says so again for a folder this app did not make. Ctrl-C at the
prompt cancels it: no half-created folder, no session moved.

Outside a conversation, `omp update` says in three lines that there is nothing to report yet and how
to make something to report, and opens no folder.

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

## The native helper

Running a real Debian means running a real `proot`: a glibc binary cannot `exec` on Android's
bionic kernel, and since Android 10 the only place Android lets an app's own uid `exec` a file is
the directory the package manager extracts the APK's `lib/<abi>/` entries into. So this APK ships
**eighteen files, 1,130,556 bytes**, under `app/src/main/jniLibs/` — five names for each of the two
64-bit ABIs and four for each of the two 32-bit ones — committed like source. No build step
fetches a binary, because a build that fetches a binary is a build nobody can reproduce.

**This is the whole of the native code in the app.** The shell, the namespace VM, the agent, the
loopback server and the chat are Kotlin; `proot` and the three libraries it links are the only
third-party binaries, they are here because a real Linux userland cannot be reimplemented, and
they are **GPL** — not a vendored fork of this project, but a package from Termux's repository,
reproduced byte-for-byte. The licences, the versions, the index checksums and the source offer are
below, and nothing under `jniLibs` is a project-owned work.

| shipped as | what it is | why that name |
|---|---|---|
| `libproot.so` | `proot` 5.1.107.95 | the kernel execs it |
| `libproot-loader.so` | proot's ptrace-time loader, statically linked | the kernel execs it too: proot substitutes the loader's path for the guest program's in the `execve` (`src/execve/enter.c`) |
| `libproot-loader32.so` | the 32-bit loader, 64-bit ABIs only | as above, for a 32-bit guest; this app never has one |
| `libtalloc.so` | `libtalloc` 2.4.3 (`libtalloc.so.2.4.3`) | see below |
| `libandroid-shmem.so` | `libandroid-shmem` 0.7 | as upstream |

**Every name is `lib*.so` and that is not a style choice.** AOSP's extractor keeps a `lib/<abi>/`
entry only if its name starts with `lib` and ends with `.so` — in a non-debuggable build, with no
exception — and silently skips anything else. `proot` would never be extracted, and the directory
is only ever populated at install time, so nothing would put it back. `libtalloc.so.2` is the
name in proot's `DT_NEEDED` and the name no package manager will extract, which is why it ships as
`libtalloc.so` and is copied at first run to `filesDir/omp/proot-libs/libtalloc.so.2`, which is
what `LD_LIBRARY_PATH` then names. The copy is safe where the original is not because Android 10
denied `execve` from app storage and deliberately left `mmap(PROT_EXEC)` working.

**Nothing here has ever been run, anywhere, and proot itself has never been executed in this
environment at all** — no device, no emulator, no ARM Android. Not in a test either: a helper
present in an APK is not a helper that has run. The bytes are verified against the
Termux `.deb` payloads and the layout is verified, and whether the kernel will `exec` any of it,
whether the linker resolves what `LD_LIBRARY_PATH` says, and whether the OEM's SELinux policy
permits `ptrace` are all open. `omp doctor`'s closing line says so on the device, and
`docs/vm.md` says what a green report still cannot tell you.

## Layout

```
core/   the shell, the agent, the namespace VM, the provisioning layer, the guest start, the
        diagnostic and the guest API. Plain JVM, no android.* imports, so all of it runs under
        :core:test.
app/    the Activity, the view that draws the screen, the platform commands, the chat host and the
        WebView.
        app/.../web/ is the HTTP server, the chat API, the token gate and the origin chooser, and
        has no android.* in it either, which is what lets :app:testDebugUnitTest drive it over a
        real socket with no emulator.
        app/.../vm/ is the same: the ProotHelper, the launcher and the guest boot composition are
        plain JVM, so the names, the environment and the argument vector can be asserted without a
        phone.
        app/src/main/assets/web/ is the chat itself — index.html, app.css, app.js, three files and
        no build step. The app serves them and `omp provision` writes the same bytes into the
        guest's document root, so there is one copy of the UI in the project.
        app/src/main/jniLibs/ is the native helper above, per ABI, as committed bytes.
```

## License

Do what you want with it.

### The native helper is GPL, and here is the whole of it

This app's own code is the "do what you want with it" above. The eighteen native files in `jniLibs`
are not, and the difference is not a formality: `proot` is GPL, which means a redistributor of the
binary in a public repository has to account for the source of *that* binary, not just of this app.

**`proot` 5.1.107.95 — GNU General Public License, version 2 or (at your option) any later
version.** The project states it in SPDX form in its own README, at
<https://github.com/proot-me/proot/blob/master/README.rst> (`SPDX-License-Identifier:
GPL-2.0-or-later`), and the licence file that names is
<https://github.com/proot-me/proot/blob/master/COPYING>, which is the GPL version 2 text. The
exact version vendored says the same in every source header, `src/cli/proot.c` among them:
*"modify it under the terms of the GNU General Public License as published by the Free Software
Foundation; either version 2 of the License, or (at your option) any later version."* That
`COPYING` also carries the copyright: *"The copyright holder for PRoot and CARE is
STMicroelectronics"*, with Cédric VINCENT as original author and maintainer, and the headers
carrying `Copyright (C) 2015 STMicroelectronics`. The project page, <https://proot-me.github.io/>,
describes what proot does and names no licence, which is why the repository and the licence file
are cited rather than the page.

**`libtalloc` 2.4.3 — GNU Lesser General Public License, version 3 or (at your option) any later
version.** Established from the library's own source, downloaded and checksummed from
<https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz>: `talloc.c` carries the header *"This
library is free software; you can redistribute it and/or modify it under the terms of the GNU
Lesser General Public License as published by the Free Software Foundation; either version 3 of
the License, or (at your option) any later version"*, and `LICENSE` in the same tarball is the
LGPL-3.0 text, *"GNU LESSER GENERAL PUBLIC LICENSE, Version 3, 29 June 2007"*. Termux's package
recipe declares `TERMUX_PKG_LICENSE="GPL-3.0"` for this package and the `.deb` ships a
`copyright` symlink pointing at Termux's own `LICENSES/GPL-3.0.txt`; that is the packager's
metadata, not the library's licence, and the upstream source is what the licence attaches to. The
one judgement in this section, and the one worth a second opinion if someone disagrees.

**`libandroid-shmem` 0.7 — BSD 3-Clause.** From the project's own licence file at
<https://github.com/termux/libandroid-shmem/blob/v0.7/LICENSE>, and the same text ships in the
`.deb` as `share/doc/libandroid-shmem/copyright`: *"Copyright (c) 2013, Sergii Pylypenko /
Copyright (c) 2017, Fredrik Fornwall / All rights reserved."* Its binary-redistribution clause —
*"Redistributions in binary form must reproduce the above copyright notice, this list of
conditions and the following disclaimer in the documentation or other materials provided with the
distribution"* — is why that notice is reproduced here and not left in a file inside the package.

### Where they came from

The [Termux repository](https://packages.termux.dev/apt/termux-main/), which is where a prebuilt
bionic-linked proot exists for all four Android ABIs. `proot` 5.1.107.95 declares
`Depends: libandroid-shmem, libtalloc`; both dependencies are in the APK as well and are accounted
for above. These are the `SHA256` values from the repository index at
`https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-<abi>/Packages.gz`, and every
byte in `jniLibs` is byte-identical to the payload of the `.deb` these identify:

| ABI | package | version | `.deb` SHA256 |
|---|---|---|---|
| `aarch64` | proot | 5.1.107.95 | `0a1b3d0f6ef76436c5ed924cd8e8f5a6b7186e99e1650eb2d9bc734e218a74cb` |
| `arm` | proot | 5.1.107.95 | `111a29219568b0e3c72f6bd5383f1bebb86f2e5f38ee260b95c4254c9267049d` |
| `x86_64` | proot | 5.1.107.95 | `f63ce9bd0d38715eae0163a3772f3395913587444c7ce7232091c6d359afe3c3` |
| `i686` | proot | 5.1.107.95 | `c0d44ecaba83300280d1d487a1e9c69c509750db1d7961b6f79d27c916d68970` |
| `aarch64` | libtalloc | 2.4.3 | `ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da` |
| `arm` | libtalloc | 2.4.3 | `cd56f87007e487c8025fac2df2a27b2bc58102344040a527eaa6fa7527d18f9b` |
| `x86_64` | libtalloc | 2.4.3 | `7ca2eaae2e53b28228a01301bc410b62845403d6317c25b8e0a7f40681de0628` |
| `i686` | libtalloc | 2.4.3 | `7b79f8b5e41d597940551ef9bd5a2fef7978f519300af8fc5c498d34a93f575a` |
| `aarch64` | libandroid-shmem | 0.7 | `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6` |
| `arm` | libandroid-shmem | 0.7 | `5832fd11dca9be2a288dd8fbc2b2799b289c812c7a8764f1f8234c425aa64ce5` |
| `x86_64` | libandroid-shmem | 0.7 | `ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797` |
| `i686` | libandroid-shmem | 0.7 | `e9ccecee1aeed7dd70ac93bf44a6ba1bf6d4cb9559aabeb086acdc89accb4ba4` |

### The source, and what GPL-2.0 §3 actually obliges a redistributor to do

GPL-2.0 §3 governs distributing this in object or executable form, and offers three ways to comply.
Quote, from the licence text:

> 3. You may copy and distribute the Program (or a work based on it, under Section 2) in object
> code or executable form under the terms of Sections 1 and 2 above provided that you also do one of
> the following:
>
> a) Accompany it with the complete corresponding machine-readable source code […]
> b) Accompany it with a written offer, valid for at least three years, to give any third party […]
> c) Accompany it with the information you received as to the offer to distribute corresponding
> source code. […]
>
> If distribution of executable or object code is made by offering access to copy from a designated
> place, then offering equivalent access to copy the source code from the same place counts as
> distribution of the source code, even though third parties are not compelled to copy the source
> along with the object code.

Plus §1: give every recipient *"a copy of this License along with the Program"*, and keep the
copyright notices intact. This repository does **both** by pointing at the exact source rather than
writing a letter: this project's public repository is the "designated place", and the complete
corresponding source of the exact binary in `jniLibs` is these two published, checksummed
artifacts — the upstream tree the binary was built from, and the recipe that contains every patch
and flag Termux compiled it with. A built binary with no stated source tree is not a
corresponding source; a source tree plus the build script that produced these exact bytes is.

| what | where | SHA256 |
|---|---|---|
| `proot` 5.1.107.95 source, as Termux builds it | <https://github.com/termux/proot/archive/v5.1.107.95.zip> | `dbb50381c2f0b5c342bdf3d3467d80c21d2a4677d9dadd14159fa3b32f11b319` |
| the recipe that builds the binary in this APK | <https://github.com/termux/termux-packages/blob/master/packages/proot/build.sh> | — |
| `libtalloc` 2.4.3 source | <https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz> | `dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd` |
| `libandroid-shmem` 0.7 source | <https://github.com/termux/libandroid-shmem/archive/refs/tags/v0.7.tar.gz> | `1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867` |

All four were downloaded and had their SHA256 checked against the recipe before any of it was
copied, and the payloads in `jniLibs` were compared byte-for-byte against the `.deb`s those index
checksums identify. The build recipe is the part that makes this a source offer rather than a
source *pointer*: `PROOT_UNBUNDLE_LOADER`, `-DVERSION`, `-DARG_MAX` and
`-C src PROOT_WITH_LIBANDROID_SHMEM=true` are all in it, and all four are visible in the binary that
is in this repository — the `PROOT_UNBUNDLE_LOADER` path and the `PROOT_LOADER` variable in its
strings, and the bionic NDK build tags in its ELF notes.

The LGPL-3.0 obligation on `libtalloc` is the smaller one and it is met by the same table: LGPL-3.0
§4–§5 ask for a verbatim copy of the licence and for the library's own source to be conveyed, and
§6 asks that the recipient be able to relink the application against a modified library. Nothing
here links talloc into an application, so nothing here forecloses that; the source is the upstream
tarball above and the licence text is at <https://www.gnu.org/licenses/lgpl-3.0.html>.

The BSD-3-Clause obligation on `libandroid-shmem` asks for the notice and the disclaimer to travel
with the binary, and the notice is quoted in full above. GPL-2.0 §1's "keep intact all the notices
that refer to this License" is why the `proot` attribution above names STMicroelectronics and Cédric
VINCENT rather than only naming the licence.

**One gap, stated rather than hidden.** §1 asks for "a copy of this License along with the
Program", and the three licence texts are linked here rather than vendored into this repository:
GPL-2.0 at <https://www.gnu.org/licenses/old-licenses/gpl-2.0.html>, LGPL-3.0 at
<https://www.gnu.org/licenses/lgpl-3.0.html>, and the BSD-3-Clause notice quoted in full above.
Each is also inside the corresponding source archive the table above names, so a recipient who
downloads the source the GPL obliges them to be given has the licence text in hand as well. If
that link-and-name arrangement is not good enough for whoever publishes this, the fix is two
files — `COPYING.gpl-2.0` and `COPYING.lgpl-3.0`, both verbatim copies available from the URLs
above — and nothing else about the attribution would change.
