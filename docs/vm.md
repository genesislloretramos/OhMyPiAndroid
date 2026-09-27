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

- **Not a hypervisor and not QEMU.** There is no second CPU, no guest memory, no `KVM`, no ELF
  binary anywhere in the app. There is no NDK and no `Runtime.exec`.
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
conversation in the namespace and a folder in the user's `Documents` are one folder. It differs in
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

## Known gaps

- **No processes.** Nothing is forked, ever. `systemctl start` is bookkeeping and a journal line.
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
  proc/ sys/ dev/      the three generated filesystems
  pkg/                 the dpkg database, the local index, install and remove
  service/             systemd-lite and the journal
  cmd/                 the commands that have to speak the Vfs
```

`core/src/test/kotlin/omp/vm/VmEndToEndTest.kt` drives the whole thing the way a user does —
keystrokes into one `InputChannel`, answers off one `Screen`, and every fact checked against the
real files underneath.
