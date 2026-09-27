# The VM

An in-process userspace VM, living in a directory under the app's own storage. This is the design
and, more importantly, the limits — the parts a user is most likely to get wrong.

```
$ vm enter
ubuntu:~$ cat /etc/os-release | head -2
PRETTY_NAME="Ubuntu 24.04.1 LTS"
NAME="Ubuntu"
ubuntu:~$ exit
back on the phone; the VM is still booted and still on disk
```

## What it is

A **namespace**. A `Vfs` over a mount table, a process table that starts at pid 1, a passwd file,
and four generated filesystems. The phone's own command set runs in it unchanged, with a few
commands registered over the top that only make sense inside a namespace.

A **userspace Ubuntu**. `/etc/os-release` says `Ubuntu 24.04.1 LTS`, the login user is `ubuntu`
uid 1000, and the prompt is `ubuntu@ubuntu:~$`. The architecture is the **host's**, mapped once from
`ro.product.cpu.abi` in [omp.vm.VmArch] (`arm64-v8a` -> `aarch64`, `x86_64` -> `amd64`), which is why
`uname -m`, the boot line, `/etc/apt/apt.conf` and `dpkg --print-architecture` all agree and all move
together when the device does. Nothing is downloaded to make
that true: every file is text this process generates.

An **ordinary directory**. `appFilesDir()/rootfs`, writable with no permission an app lacks, and
readable from a computer with `adb shell run-as com.omp.terminal ls -R files/rootfs`. A file you
create in there is a file.

## What it is not

- **Not a hypervisor and not QEMU.** There is no second CPU, no guest memory and no `KVM` **in
  this namespace**, which is Kotlin: a `Vfs`, a process table and some generated filesystems, in
  this process. The real Debian on the other side of `omp provision` is a different thing
  entirely, and it does have an ELF binary under it — see **The guest** in README.md.
- **Not a container.** There is no second uid, no mount namespace in the kernel's sense, and no
  `CAP_` anything. The boundary is a `Vfs` object.
- **Not isolated.** One process, one heap. `root` inside the VM is a name the VM kernel answers to,
  not an escalation: every file in there is already the app's uid, which is why the real one is one
  `cat` away in `/sys/omp/android/uid` and why `/etc/sudoers` says so in the place a user looks.
- **Not a snapshot of a real machine.** `/proc` and `/sys` are generated from the phone's own facts
  and are not the kernel's. Where a real system's file would need a privilege an app does not have,
  this one says what it used instead.

`sudo` and `su` say so themselves, in the place the user is looking, because a root prompt with
no such line is the one thing in here that could mislead:

```
$ sudo id
uid=0(root) gid=0(root) groups=0(root)
sudo: root was recorded for one command; no boundary was crossed (the real one is in /sys/omp/android/uid)
```

## The mount table

Decided once, in `VmKernel`'s constructor, in the order a real kernel builds one. `mount` inside the
VM and `/proc/mounts` both print it, and `vm mounts` prints it from the phone's side.

| mount | source | type | what it actually is |
|---|---|---|---|
| `/` | `omp-root` | ext4 | the directory `appFilesDir()/rootfs`, through `RealVfs` |
| `/mnt/omp` | `shared-storage` | ext4 | the app's own bind: `/storage/emulated/0/Documents/omp`, made at every boot, never in `/etc/fstab` |
| `/mnt/android` | `shared-storage` | ext4 | a real bind onto the phone's shared storage, or an empty directory that says why it is empty |
| `/proc` | `proc` | proc | generated per read from the process table and the platform |
| `/sys` | `sysfs` | sysfs | generated per read from the platform, plus `/sys/omp/android/*` |
| `/dev` | `devtmpfs` | devtmpfs | generated nodes, including `/dev/omp-host` |
| `/run` | `tmpfs` | tmpfs | an ordinary directory named `run/` |
| `/tmp` | `tmpfs` | tmpfs | an ordinary directory named `tmp/` |

Two things about that table are worth stating out loud.

**`/proc` and `/sys` are generated, not mounted from the kernel.** Android closes most of `/proc`
and all of `/sys/block` to an ordinary app, so the phone shell answers `cat /proc/stat` with
`Permission denied`. The VM generates a `/proc` from the facts the app *can* read — its own pid
table, `SystemClock`, `/proc/meminfo`, the battery, the network interfaces the framework will tell
it about — and `/proc/version` says out loud that it did.

**`/run` and `/tmp` are directories, not tmpfs.** They are in the table as tmpfs because that is
what a real rootfs shows and nothing should depend on them holding anything, but nothing clears them
at boot: a file left in `/tmp` by yesterday's session is still there today, and only `vm reset`
empties them.

A path that matches no mountpoint is `ENOENT`. A symlink in the rootfs is resolved **inside** the
namespace, never against the host filesystem a mount happens to be rooted at — otherwise a link
pointing at `/etc/passwd` would quietly read the phone's.

## `#!omp/v1 program`

A file in the rootfs is a program when its first line is exactly:

```
#!omp/v1 program ls
```

Deliberately not a real `#!` shebang: there is no kernel here to read one, and a marker that could
collide with a shebang is a marker waiting to be misread. The name after it is looked up in the
VM's command table, so `/usr/bin/ls` is a real, listable, `stat`-able, 0755 file in the rootfs that
happens to run the same Kotlin `Ls` the phone runs.

The sync runs at every boot, both ways: a command added to the table gets a file, and a file whose
program is no longer in the table is removed — so `ls /usr/bin` and the table cannot drift. Only
files this wrote are ever removed, which is decided by the marker, so a user's own script in
`/usr/bin` is safe.

Anything else reached as a command is `Exec format error` with exit 126, which is what the phone
says for a file it will not run. Nothing is executed: a program file is a *routing* decision, never
a permission to run host code.

`ls -l` in here prints the file's **real** mode from the filesystem, not the phone's `ls -l`
letters: those deliberately show only what this app can do, because an app cannot read a file's uid
or group, so a 0755 program file would come out as `-rwx------`. In the namespace the mode is
knowable, and `-rwxr-xr-x` is the truth about a file the VM made executable itself.

## The dpkg database, and why `apt` never prints `Get:`

`/var/lib/dpkg/status` is a real Debian status file, in the real stanza format, read and written
through the Vfs. `/var/lib/dpkg/info/<pkg>.list` records what a package owns, which is what makes
`dpkg -L` list files that exist and `dpkg -S` resolve a path back to its package.

It is seeded at boot from an index **compiled into the app** (`omp/vm/pkg/PackageIndex.kt`). So:

```
$ apt install openssh-server
Selecting previously unselected package openssh-server (1:9.6p1-3ubuntu13).
The files for openssh-server come from the in-process userland; nothing is downloaded.
  openssh-server owns no files in this userland.
  Setting up /etc/ssh/sshd_config

$ apt install openssh-server
openssh-server is already the newest version (1:9.6p1-3ubuntu13).

$ apt update
Reading package lists... 14 packages in the local index, 0 upgraded, 0 newly available.
```

There is no mirror, no `.deb`, no download and no unpack. `apt` therefore **never prints a `Get:`
line**, and a test asserts it: a package manager that claims bytes travelled is lying, and this one
is the only honest `apt` an Android app can ship. What an install really does is write the
package's conffiles, add a stanza to the status file, and create or remove program files for the
commands this userland actually has. A package with no programs here — `openssh-server`, because
there is no sshd — says so in the install output rather than appearing to succeed silently.

## The journal

`/var/log/journal/<unit>.log`, one plain text file per unit, and `/var/log/journal/README` says in
its own first line that it is **not** systemd's binary journal and that no `journalctl` will read it
as one. A line is:

```
<ISO-8601 UTC> <boot id> <unit>[<pid>]: <priority>: <message>
```

which is the syslog shape `journalctl` filters on, so `-u`, `-n`, `-b` and `-p` work against the
real text. A line that does not parse is left out of a *filtered* view rather than printed as if it
had.

Timestamps come from the wall clock, because a journal line answers "when". The boot id is
`boot-<hex of the monotonic clock at boot>`, so one boot of the app is one boot of the VM and `-b`
filters on it.

## The service manager

systemd-lite: unit files under `/etc/systemd/system`, an enable symlink under
`/etc/systemd/system/multi-user.target.wants`, and a state file per unit in
`/var/lib/omp/units/<unit>.state`. `Description`, `Type`, `ExecStart` and `WantedBy` are the four
keys that mean anything here.

**Nothing is forked.** That is the whole limit and it is stated once: there is no `systemd` to ask
and no process to start. `start` records the unit active, gives it the pid its `ExecStart` names
*when that pid is one the VM's own process table owns*, and writes a journal line saying exactly
that. A unit whose `ExecStart` names no pid is `active (exited)`, which is what systemd calls a
`Type=oneshot` unit that finished.

There are three units — `omp-vmd`, `systemd-journald` and `vm-hostbridge` — and there is no
`ssh.service`, because there is no sshd and nothing is listening. A unit claiming otherwise is a
lie with a `systemctl status` attached to it, and the `openssh-server` package has no programs for
exactly the same reason.

The interesting failure is a restart. State survives an app restart because the rootfs is a real
directory, so a unit that was `active` with a `MainPID` the new process has never heard of comes
back **failed**, with the reason in the journal — never as active, because a unit that says it is
running something nothing is running is worse than one that admits it died:

```
$ systemctl status vm-hostbridge
● vm-hostbridge.service - omp host bridge (/dev/omp-host)
   Active: failed
    Reason: Main process exited, status=gone/MainPID-gone
```

## The Android bridge

`/mnt/android` is a real bind onto `Environment.getExternalStorageDirectory()`, one path in, one
path out: a file written in the namespace is a file in shared storage, and a photo the user takes
afterwards shows up in the namespace with no refresh.

Without the "All files access" grant there is nothing to bind, so the mountpoint still exists — as
a real, empty directory — and `mount` prints the reason under the line. That is better than a path
that is not there and an `ls` that says `No such file or directory`. Run `grant-storage` on the
phone and reboot the VM.

`/mnt/omp` is the same idea one directory in: `/storage/emulated/0/Documents/omp`, so a
conversation in the namespace and a folder in the user's `Documents` are one folder. That is also
why the agent runs there: a conversation is a folder, and a model needs a folder. Bare `omp` is the
launcher outside one and the coding agent inside one, decided by the same `OMP_WORKSPACE` the
launcher sets — and the agent's own words (`run`, `update`, `key`, `help`) are checked before a
bare name is taken for a conversation, so `omp update` out here is a question about the agent and
not an attempt to open a folder called `update`. It differs in
where it comes from. A user's bind is typed, is recorded in `/etc/fstab`, comes back at every boot
and can be taken away with `vm umount`; this one is **the app's own** — made at the end of every
boot from the app's own facts, in no fstab line, and refused by `vm umount` by name. A second
record of it could only go stale, and a mount the user could remove is a mount the app's own
feature would stop working without. `mount` shows it as a bind with its real device, and the
`omp` command is what asks the kernel to make it.

The grant is the same one and the same sentence: without it the boot logs one failed line, the
mount is absent, and `omp` says `grant-storage` rather than creating a folder somewhere it cannot
show.

`/dev/omp-host` is the other half: a device node that answers with the platform's facts at the
moment it is read, and `/sys/omp/android/` is the same idea as files — `uid`, `model`, `package`,
and the rest of `getprop` as a tree.

## The pid namespace

The VM's pids are its own, and they are not the phone's: an app cannot see or signal another
process's pid on Android, so printing the host's numbers would be a lie `kill` could not honour.

- pid 1 is `omp-init`, registered on every boot, and it *is* this process.
- pid 2 is the login shell.
- commands start at 100 and count up.
- a process that has exited stays listed as `Z` until it is reaped, because a pid that silently
  disappears from `ps` is a pid somebody will wait for forever.

`/proc/<pid>` is a directory with `stat`, `status`, `cmdline`, `exe` and `cwd`, generated from that
table. `/proc/self` is a symlink to the current process, which on a phone is the one process that
really exists.

## What persists, and where

Everything is under `appFilesDir()/rootfs`, and it all survives a reboot of the app:

| path | what |
|---|---|
| `/etc` | including anything you edit; a boot only writes what is missing |
| `/var/lib/dpkg` | the status file and the file lists |
| `/var/lib/omp/units` | unit state, so a dead pid comes back `failed` |
| `/var/log/journal` | the journal |
| `/home/ubuntu`, `/root` | your files |
| `/usr/bin`, `/usr/sbin` | the program files, resynchronised every boot |

What does not persist is the process table: pids are handed out again from 100 after a restart, and
a unit whose `MainPID` is gone comes back failed. `shutdown()` is a shutdown, not a factory reset;
`vm reset --force` is the factory reset, and it refuses without `--force` after printing the
directory and its size.

`/etc/localtime` is a text file and not a tzfile, `/etc/machine-id` is derived from the app's
identity and is not a machine id, and `/etc/resolv.conf` says "no route" when the platform reports
no DNS. Each of them says so in its own content. A userland that lied in its own configuration
files would be worse than one that admits what it is.

## `omp doctor` — reading the output

`omp doctor` is the one command that answers "it does not work". It reads the live platform and the
live filesystem and prints a fixed set of sections in a fixed order, one `key: value` per line, so
two runs can be diffed and one can be pasted into a bug report whole.

**It is read-only.** It starts no download, writes no file, and opens exactly one socket: a
400 ms connect probe against the loopback port the chat service published. It says so in its own
`help` line. The ten sections are `identity`, `guest`, `helper`, `provisioning`, `guest state`,
`agent update`, `guest origin`, `chat`, `gaps` and `next`, and they print in that order every time.
The two new ones — `agent update` and `guest origin` — read **files** rather than the network, and
that is deliberate: `omp doctor` runs in a different process from the one that started the guest,
possibly after a reboot, and the fact that answers "is the guest up, and is it the one answering"
has to have survived on the disk.

**The `origin:` line is the one to read first, because it is the whole of "which agent is
answering".** The WebView is handed the guest's Apache inside the Debian when a whole rootfs and
the real agent are both on the device **and the last start of the guest saw this build's own chat
document come back from the guest's port**; the app's own loopback server otherwise. Both branches
are real and neither is a fallback in the sense of being embarrassing — the Kotlin agent in this
build is the only agent a 32-bit device can ever have. **Naming the port is necessary and not
sufficient**: a Debian that was downloaded is not a guest that is answering, and a build that
stopped at the port would hand a `WebView` an address that never loads wherever the guest did not
come up. Read it together with the `guest origin` section above it, which says which of the six
states the last start reached and why the app settled on the origin it did.

**The unprovisioned case is the one this build can show on its own.** A device with no Debian, or
one that has not started the guest, reports `this app's own loopback server:` and one of the
reasons — and the sample below is a device that got all the way to `UP`, which is a reachable state
and not a hypothetical one.


A healthy, fully provisioned arm64 phone:

```
$ omp doctor
omp doctor: read-only — nothing below downloads, writes, starts or stops anything

identity
  app:           com.omp.terminal
  version:       1
  installed at:  /data/app/~~kQ==/com.omp.terminal-1==/base.apk
  build:         google/shipped/panther:14/AP1A.240505.004/11269751:user/release-keys
  uid:           10345
  gid:           10345
  abi:           arm64-v8a
  debian arch:   arm64
  real agent:    obtainable on arm64-v8a: omp-linux-arm64, 224.0 MiB (234,866,984 bytes)

guest
  payload:       /data/user/0/com.omp.terminal/files — exists, 2 entries
  exec dir:      /data/app/~~kQ==/com.omp.terminal-1==/lib/arm64 — exists, 5 entries
  wire:          334.2 MiB (350,458,048 bytes)
  room:          710.9 MiB (745,403,584 bytes)
  space check:   would pass: 41.2 GiB (44,239,986,176 bytes) is free and the manifest asks for 710.9 MiB (745,403,584 bytes)

helper
  expected:      libproot.so, libproot-loader.so, libproot-loader32.so, libtalloc.so, libandroid-shmem.so
  libproot.so:   247408 bytes
  libproot-loader.so: 18136 bytes
  libproot-loader32.so: 6244 bytes
  libtalloc.so:  31440 bytes
  libandroid-shmem.so: 14432 bytes
  verdict:       all 5 are there, which is as far as this build can check

provisioning
  state file:    /data/user/0/com.omp.terminal/files/provision/state — not there
  debian-trixie-rootfs-arm64: complete, unpacked at /data/user/0/com.omp.terminal/files/omp/rootfs
  omp-linux-arm64: complete, unpacked at /data/user/0/com.omp.terminal/files/omp/bin/omp
  last attempt:  no record at /data/user/0/com.omp.terminal/files/provision/state: nothing has been downloaded by this build
  last run:      none recorded in this app's memory, which does not survive a restart; the state file and the .part files above are the only evidence there is
  phase:         installed: nothing in progress

guest state
  rootfs:        unpacked, .omp-provisioned is in /data/user/0/com.omp.terminal/files/omp/rootfs
  agent:         installed at /data/user/0/com.omp.terminal/files/omp/bin/omp
  web root:      installed at /data/user/0/com.omp.terminal/files/omp/rootfs/var/www/html
  web bytes:     3 of 3 are this build's own copy — index.html is this build's own copy; app.css is this build's own copy; app.js is this build's own copy
  lamp:          not installed: no .omp-guest-packages in /data/user/0/com.omp.terminal/files/omp/rootfs

agent update
  record:        /data/user/0/com.omp.terminal/files/provision/update — 164 bytes
  outcome:       ALREADY_CURRENT: 'omp update' ran and said the agent was already current, so nothing was downloaded
  version:       18.3.5 is what the guest reported for itself
  last run:      at 2026-09-28 07:41:12 UTC, status 0, bound 60000ms

guest origin
  state:         UP: Apache inside the Debian is answering on http://127.0.0.1:8732 and the boot's 'omp update' landed, so the page the WebView was handed is the Debian's
  port:          8732, reserved by the run that started the guest: it was free, and it was given back so Apache could take it
  apache:        answering: the start asked http://127.0.0.1:8732 for this build's own chat document and got it back
  agent:         ALREADY_CURRENT: the boot's 'omp update' landed at the same start, so the agent the Debian's page is talking to is the one it was brought up to; the 'agent update' section above has what it printed

chat
  origin:        the guest's Apache inside the Debian: a whole rootfs and the real agent are both on this device, and the run that started the guest saw this build's own chat document come back from http://127.0.0.1:8732
  port:          8731, from /data/user/0/com.omp.terminal/files/web/url
  listening:     yes, something accepted a connection on 127.0.0.1:8731 inside 400ms
  token:         /data/user/0/com.omp.terminal/files/web/token — 43 bytes — read, never printed by this command

gaps
  none: every fact above was read from this device

next
  web: the agent's web front end is up on 127.0.0.1:8731; 'web' prints that url and this install's token

  what this cannot tell you: nothing above executed the native helper. "the helper is
  there" is not "the guest will boot", and no build of this app has ever run proot on any
  device. The next failure after a report that reads healthy is the kernel refusing to
  exec a file out of the exec directory, a proot that will not accept these flags, or a
  Debian that unpacked without a loader in it.
```

#### The lines that matter most

| line | what it settles |
|---|---|
| `verdict:` under `helper` | whether the package manager extracted the native helper at all. **This is the single most useful line in the command**, because an unextracted helper is the most likely first-install failure and its symptom — a directory that exists and is empty — looks from a shell like a device with no proot on it. |
| `space check:` | whether `omp provision` would refuse before its first byte, answered with the manifest's own two numbers. |
| `web bytes:` | whether the guest is serving *this build's* chat UI, and which of the three files is not. |
| `origin:` and `listening:` | which server the WebView was handed, by name, and whether the app's own port is accepting right now. |
| `state:` under `guest origin` | which of the six the last start of the guest reached. **`AGENT_UPDATE_FAILED` is a serving state and not a fault**: the page is the Debian's and the agent inside it is the one that was already there. |
| `outcome:` under `agent update` | what the last boot's `omp update` did, by name — and `TIMED_OUT` there is the shell's own killed-command number, so it can never be read as the command having failed. |
| `last attempt:` and `last run:` | the resume record from the disk, and the outcome the running app holds in memory. |
| the closing line | what this command **cannot** tell you. Read it before concluding anything from a green report. |

**A `guest origin` state of `UP` is not a statement that a guest booted.** It says this build's own
document came back from the reserved port and that the boot's `omp update` landed. It does not say
which of them did the work, and it cannot: every fact in the two new sections is read off a file
this app wrote, and none of them is an `execve`.

### Five situations you will actually hit

#### 1. The helper was never extracted

```
helper
  expected:      libproot.so, libproot-loader.so, libproot-loader32.so, libtalloc.so, libandroid-shmem.so
  libproot.so:   unreadable: not in /data/app/~~kQ==/com.omp.terminal-1==/lib/arm64 (No such file or directory)
  …
  verdict:       none of the 5 packaged files is in /data/app/~~kQ==/com.omp.terminal-1==/lib/arm64: the package manager did not extract them, and android:extractNativeLibs="true" is the attribute that makes it
```

**What it means.** The APK carries the helper; the install did not put it on the device. The
`exec dir` line above it will read `exists and is empty`, which is the signature of this and only
this. AOSP's extractor keeps a `lib/<abi>/` entry only if the name starts with `lib` and ends in
`.so`, and `android:extractNativeLibs="true"` is what makes it copy them at install time at all.
There is nothing the app can do about it from inside itself, and the honest answer names the
attribute rather than suggesting the user try again.

#### 2. A download stopped half way

```
provisioning
  state file:    /data/user/0/com.omp.terminal/files/provision/state — 41 bytes
  debian-trixie-rootfs-arm64: complete, unpacked at /data/user/0/com.omp.terminal/files/omp/rootfs
  omp-linux-arm64: partial, 12.0 MiB (12,582,912 bytes) of 224.0 MiB (234,866,984 bytes); the next run continues from there
  last attempt:  omp-linux-arm64, recorded as '12582912 partial'
  phase:         downloading: 1 artifact(s) have a .part file, and the next 'omp provision' resumes each from that file's own length
```

**What it means.** A radio dropped a 224 MB transfer, which on a phone is the normal case and not
an edge. Both byte counts are printed because the `.part` file's own length *is* the offset the
next run asks the server to resume from — so the number is the resume point, not an estimate.
Running `omp provision` again continues from exactly there. `last run:` is the app's in-memory
record of how the run ended and says so when there is none, because it does not survive a restart
and the disk is then the only evidence there is.

#### 3. The guest is up but the page is somebody else's

```
guest state
  web root:      installed at /data/user/0/com.omp.terminal/files/omp/rootfs/var/www/html
  web bytes:     2 of 3 are this build's own copy — index.html is 53 bytes here and 35 bytes in this build: different bytes; app.css is this build's own copy; app.js is this build's own copy
```

**What it means.** The Debian's own `apache2` package ships an `index.html` and overwrites the one
this build put there when it was installed. Both lengths are printed so the difference is visible
rather than asserted, and the file is named, because one stale page out of three is exactly what
this looks like and "the web root is broken" would not have said which file. `omp provision` puts
the app's own copy back.

#### 4. Nothing is listening

```
chat
  origin:        this app's own loopback server: a whole rootfs and the real agent are not both on this device
  port:          8731, from /data/user/0/com.omp.terminal/files/web/url
  listening:     no, nothing accepted a connection on 127.0.0.1:8731 inside 400ms
```

**What it means.** The service published a URL and then stopped — a user who pressed **Stop** on
the notification, or an app that was killed. The file records that the service *started*; it does
not record that it is still here, so the port is asked and not read. A refused connect is an
answer, and it is why the `web` line is absent from `next` below: advice that does not follow from
what was just read is not advice.

#### 5. Something else on the phone holds the guest's port

```
guest origin
  state:         PORT_TAKEN: something on this phone already holds http://127.0.0.1:8732, so Apache was not started at all, and the Debian's origin was refused rather than replaced by this app's own server
  port:          8732, and the run that started the guest could not reserve it: something on this phone already held it, so Apache was never started on it
  apache:        not started: the start launched nothing inside the guest
```

**What it means.** The reserved number — 8732, one above this app's own 8731 — was not free, so
nothing was launched and **the guest's origin was refused rather than replaced**. That refusal is
the point: a quiet fall back to the app's own loopback server would tell a user the real agent was
answering when the Kotlin one was, and every decision they took from that screen would be about
the wrong program. The state is named, and `chat` below it says the same thing in its own words.

### `unreadable:`, and what the gaps section is for

**A fact this run could not read is printed as `unreadable: <reason>` and named again in the
`gaps` section — never as silence, and never as a value that looks healthy.** A missing line and a
healthy line must not be the same shape on a screen, because the whole point of this command is
that a person reads it.

A **gap** is a fact the command failed to read: a directory the filesystem will not open, a page
this build does not have, an ABI nothing here knows. A device in a bad state says so in the section
it belongs to — `would refuse` for a full disk, `not installed` for a missing agent — and those are
answers, not gaps. When nothing failed to be read, the section says so:

```
gaps
  none: every fact above was read from this device
```

**Nothing in it executes the native helper.** Every line above is a fact about a filesystem and a
platform, and none of them is an `execve`. The next failure after a report that reads healthy is
the kernel refusing to exec a file out of the exec directory, a proot build that will not accept
the flags `ProotCommand` builds, or a Debian that unpacked without a loader in it — and no build of
this app has ever run proot on any device, which is why the last line of the output says so rather
than letting a green report imply the device was ready.

**The same holds for the two guest sections, and it is worth being exact about why.** `agent
update` and `guest origin` do not open a socket and do not start anything; they read a file this
app wrote at the last boot. What is new is that the file has a writer now, so the report can be
green about a path that has never executed. **One line of the whole guest start path can only ever
run on a phone** — `ProotForegroundServer.launch`'s builder start, which the source marks as such.
**Everything above it** is plain JVM and is covered by tests: the argument vectors, the
environment, the port check, the probe, the record, and every sentence of every report. **proot
itself has never been executed in this environment at all** — no device, no emulator, no ARM
Android — so every statement this document makes about the guest's web half is a statement about
code that has been read and tested, not about a guest that has answered.

## Known gaps

- **No processes here.** Nothing in *this namespace* is forked, ever; `systemctl start` is
  bookkeeping and a journal line. The guest on the other side of `omp provision` is a different
  thing and does have processes — see **The guest** in README.md.
- **No networking of its own.** The namespace shares the phone's: the same `curl`, the same route,
  the same DNS. There is no socket namespace, no port and nothing listening.
- **No swap and no block devices.** `SwapTotal` is 0 because an app cannot see the kernel's
  number, and `/sys/block` is closed to apps, so the namespace does not pretend.
- **No timezone arithmetic.** `/etc/timezone` names the device's zone and `/etc/localtime` is a
  text file whose first line says it is not a tzfile, because the namespace ships no zoneinfo to
  convert with. A date is in the device's zone.
- **`ifconfig`, `route` and `netstat` are not here.** The phone's README lists why, and a package
  entry does not wish them back.
- **One core unless a build property says otherwise.** `ro.config.cpu_count` is the one property
  AOSP writes on the devices that have one, so a stubbed or a real property turns the lie into the
  truth.
- **`apt-get upgrade` is honest and useless:** there is no newer version to upgrade into, because
  the userland is this app's own code.

## Where the code is

```
core/src/main/kotlin/omp/vm/
  VmSystem.kt          the class the app calls: kernel, table, users, boot, openSession
  VmKernel.kt          the mount table, boot, the pid namespace, the units
  VmVfs.kt             the seam: a path is a mount, and a command cannot tell
  VmExec.kt            the #!omp/v1 program format
  VmProcessTable.kt    pids, from 1
  VmUsers.kt           /etc/passwd through the Vfs
  VmCommand.kt         the `vm` command, registered from the phone's SystemCommands
  rootfs/Rootfs.kt     the tree, and only-what-is-missing
  workspace/          the conversation folders: the model, the names, the listing
  launcher/           the `omp` command, and where the container and its bind come from
  provision/           the first-run layer: the real Debian and the real agent, per ABI; the
                       guest's port, its bind check, its probe, its six states and its record
  doctor/              `omp doctor`: the ten sections, and the closing line about what it
                       cannot tell you
  guestapi/            what the guest's own API does, the measured flags it runs `omp` with, and
                       the bounded `omp update` the boot makes in there
  proc/ sys/ dev/      the three generated filesystems
  pkg/                 the dpkg database, the local index, install and remove
  service/             systemd-lite and the journal
  cmd/                 the commands that have to speak the Vfs

core/src/main/kotlin/omp/agent/
  Agent.kt             the version, the verb list, the key store, the help, who may ask
  Session.kt           the loop: a prompt, a question, a stream, the transcript
  KeyCommand.kt        `omp key`, the one command that handles a credential
  UpdateCommand.kt     `omp update`: the identity block, and the one thing it cannot do
  Endpoint.kt          the base URL, checked before a key goes anywhere near it
  store/ http/ json/   the key store, the transcript, the state file; SSE, refusals, JSON
```

```
app/src/main/java/com/omp/terminal/vm/
  ProotHelper.kt       where the native helper is, the names it has to be found under, and the
                       two variables the linker and proot read out of their own environment
  NativeProot.kt       the ProotLauncher that hands ProotProcessLauncher a vector, an environment
                       and a directory with those two things said
  ProotProcessLauncher.kt   the half that forks; no android.* in any of the three
  ProotForegroundServer.kt  the half that starts Apache and walks away. Its builder start is the
                       one line of the whole guest path that can only ever run on a phone
  GuestRuntime.kt     the boot composition: wires the seams together once, on its own thread,
                       and publishes the one report two callers read

app/src/main/jniLibs/<abi>/
  libproot.so          proot, the one file the kernel execs
  libproot-loader.so   the loader proot execs in the guest program's place
  libproot-loader32.so the 32-bit loader; 64-bit ABIs only
  libtalloc.so         libtalloc 2.4.3, renamed so the package manager will extract it
  libandroid-shmem.so  libandroid-shmem 0.7
```

```
app/src/main/java/com/omp/terminal/web/
  LocalServer.kt       the ServerSocket, the parser, eleven routes, four threads
  ChatApi.kt           the routes, the streamed answers, and the pages
  TokenGate.kt         the one credential check, and an honest account of what it is not
  UiOrigin.kt          the two origins, the rule that picks one, and the URL predicate
  UiOrigins.kt         where the port and the token are read from, and the rule that builds a guest
                       origin only after a page came back from 8732

app/src/main/assets/web/
  index.html app.css app.js   the chat. Three files, no build step, and the same three bytes
                               are written into the guest's document root by `omp provision`.
```

Every name there is `lib*.so` because AOSP's extractor keeps a `lib/<abi>/` entry only if it starts
with `lib` and ends with `.so`, in a non-debuggable build, with no exception — which is why
`libtalloc.so.2`, the name in proot's `DT_NEEDED`, cannot be the name it ships under and is copied
to `filesDir/omp/proot-libs/` at first run instead. The licence, the versions, the repository index
checksums and the source offer are in README.md; nothing under `jniLibs` is a project-owned work.

The agent is in `:core` and not in `omp/vm/`, because most of it never touches the namespace: it
is an HTTP client with a prompt. What it does go through the seam for is the two files in the
conversation's own `.omp/` — the transcript and the state file — which is the same promise the
rest of this document makes. **The key is the one thing it does not go through the seam for**: it
is read by the app out of its own private storage and handed to the transport, so no path inside
the namespace leads to it.

`core/src/test/kotlin/omp/vm/VmEndToEndTest.kt` drives the whole thing the way a user does —
keystrokes into one `InputChannel`, answers off one `Screen`, and every fact checked against the
real files underneath.
