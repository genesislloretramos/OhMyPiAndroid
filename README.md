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
vm  omp  doctor  web  grant-storage
```

It is not a wrapper around toybox. The shell — the lexer, parser, expander, line editor and the
terminal emulator itself — is written from scratch in Kotlin. **The shell, the VM and the agent
are pure Kotlin, and none of them is a binary, a native library or a `Runtime.exec`.** Every
command on this page is Kotlin code running inside your app's own sandbox. The one native
artifact in the APK is `proot`, a GPL path emulator taken from Termux's packages, and it exists
for one reason: a real Debian is a real Linux userland and it has to be emulated rather than
reimplemented. **The native helper** below has the bytes, the licences and the source offer; it
has never been run on a device.

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
./gradlew :core:test             # 803 tests: the shell, the VM, the agent and the guest, on the JVM
./gradlew :app:testDebugUnitTest # 101 tests: the web server, the WebView policy, the helper
```

Both suites are green as written, and neither needs a device. `:core:test` is 803 tests in 39.3
seconds of execution and 49 seconds for the whole `--rerun-tasks` run with the Kotlin recompile.
`:app:testDebugUnitTest` is 101 tests in 0.7 seconds of execution and about two seconds of
Gradle — it is small because the only thing in `:app` with behaviour worth testing is the
loopback server, the policy around it and the names of the native helper. The debug APK is
2,294,381 bytes, and two builds of the same tree are byte-identical. `--rerun-tasks` cannot be
used on `:app`: it forces
`:app:compileDebugNavigationResources`, which is not in the offline build cache.

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

- There is a second shell inside this one — see **The VM**, below — and a real Debian on the
  other side of `omp provision` — see **The guest**. `omp` opens the conversation list;
  `omp doctor` prints everything this build can read about the device; `web` prints the chat
  server's address; `grant-storage` asks for the all-files grant the conversations need.

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

That table is about the **phone** shell, and it is still true of it. Inside the VM `su` and
`sudo` do exist, and both say in the place you are looking that nothing was escalated —
`docs/vm.md` has the sentence they print. They exist to be honest about the boundary, not to
cross it.

## The VM

There is a second shell inside this one. It is an **in-process userspace VM** — Ubuntu Server
24.04.1 LTS, minimal, running on a directory in the app's own storage. No image to download, no
kernel to boot, no process, no native code and no `Runtime.exec`: every command in it is the same
Kotlin the phone shell runs, with a different `Vfs` behind it. That is the whole of it, and it is
why this is not the same thing as the real Debian in **The guest**, below — the two are different
products and they are both here.

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

## The guest

The VM above is written in Kotlin and lives in this process. The guest is the other thing: **a
real Debian**, downloaded and unpacked on the phone, with a real `omp` in it on the two ABIs that
upstream builds one for.

```
$ omp provision
omp: arm64-v8a: 334.2 MiB (350,458,048 bytes) over the network — 55.7 MiB (58,379,360 bytes) of
Debian trixie rootfs and 224.0 MiB (234,866,984 bytes) of the omp v18.3.4 agent binary, and
54.6 MiB (57,211,704 bytes) of LAMP that apt fetches inside the Debian after the rootfs is
unpacked. It needs 710.9 MiB (745,403,584 bytes) of room on the device, of which 376.7 MiB
(394,945,536 bytes) is LAMP installed.
omp: the Debian is provisioned with LAMP from the Debian archive at first boot, not bundled in
this app: apache2-bin, libapache2-mod-php8.4, php8.4-cli, mariadb-server and their dependencies,
installed by apt inside the guest once the rootfs is unpacked, for 54.6 MiB (57,211,704 bytes)
over the same mobile connection and 376.7 MiB (394,945,536 bytes) on the device, both measured,
and not started without you asking. […]
omp: into /data/user/0/com.omp.terminal/files/omp, with a download in progress kept in
/data/user/0/com.omp.terminal/files/provision so a cancelled run continues instead of starting again

omp: type y to download that now, or anything else to stop:
```

**Nothing moves without an answer given in that run.** The cost is printed from the manifest
itself, and only a `y` — not an empty Enter, not `yes` — is the agreement. On a pipe, in a script,
under a `&`, or inside the VM, there is no question to answer and the command prints the cost and
the refusal and stops. `--yes` is the one exception: it is this run's own argument, and it says
in the output that it skipped the question. A download can be cancelled with Ctrl-C and resumed —
the `.part` file's own length is the offset the next run asks for.

**What it is on your device, and what it is not.** arm64 gets a Debian and the real agent; amd64
gets a Debian and the real agent; armhf gets a Debian and **no agent**, because upstream ships no
32-bit Linux build of `omp`; x86 gets **nothing**, because Debian publishes no netboot image for
i386. Those last two are refused before a question is asked, in a sentence that says which.

The two LAMP figures are measured **for arm64 only**; the other three ABIs print that nothing has
been measured for them and that the real first-run cost is therefore larger than the number above
it, rather than a number borrowed from arm64. The room check is a floor for the same reason: what
crosses the connection plus what the packages are measured to occupy once installed, and the
Debian's own unpacked size is not estimated.

**`omp provision` downloads, verifies and unpacks. Starting the guest is a separate step, and it
happens at every start of the app rather than at provisioning.** On a provisioned device the app
reserves a port, links the guest's Apache configuration in, starts Apache in the foreground,
asks whether a page actually comes back, runs the boot's `omp update`, and writes down which of six
states that was. It is idempotent — a rotation asks again and gets the same answer, because the
first run's Apache is holding the port and a second would be a collision with itself.

**The guest's own `apt` install of Apache, PHP and MariaDB is the one step in that sequence this
build does not take.** The sequence links a configuration drop-in into an Apache and starts it; the
`apt` step that would put Apache on the disk in the first place is written, priced, measured and
tested against a fake launcher, and nothing in the app calls it. **A device provisioned today
therefore has a Debian without Apache on it, and the guest start will reach `APACHE_NOT_ANSWERING`
and say so.** The state is the honest answer rather than a page from somewhere else, and that is
what the six states are for.

**The port is 8732, one above this app's own 8731.** proot gives the guest no network namespace, so
a listener inside the Debian holds a socket in the *phone's* loopback and is reachable by every app
on the device — which is why the number cannot be one somebody hopes is free. Three alternatives
were weighed and rejected: *discovering* the port from the guest's own configuration, because
Apache does not report the port it chose and the value would be a round trip through a file this
build had just written; *negotiating* it, because there is nobody to negotiate with — the guest is
Debian's own Apache under a path emulator, with no init and no socket this app may talk to
before the server exists; and an *ephemeral* port, which Apache cannot take because it binds a
number in a file, so "ephemeral" here would be a guess wearing a constant's name. One number, one
place, and a check immediately before use.

**The port is bind-checked immediately before Apache is started, and a port that is taken is the
named state `PORT_TAKEN`.** Nothing is launched, no guest origin is constructed, the app's own
server is shown — **and the report says, in those words, that it is the app's own server.** That
is the lie this whole path exists to prevent: a quiet fall back would tell a user the real agent was
answering while the Kotlin one was, and every decision taken from that screen would be about the
wrong program.

**A guest origin becomes eligible only after a positive probe, not because the guest was
launched.** The bind check is a check, not a lock — this app closes its socket before Apache is
told the number — so after Apache starts, a `GET /` for this build's own document marker is asked
up to six times, half a second apart. Taken with the bind check, that establishes two things and no
more: the port was free immediately before Apache was started, and an HTTP server is answering on
it now and serving *this app's* page rather than something else that happens to listen. It does
**not** establish that the thing answering is this install's process — a second profile of this
app is a real case, both serve byte-identical pages, and no content check can tell them apart. What
rules that out is the bind check, because a second profile's Apache is *already listening* on the
reserved number. The residual race is named rather than hidden: another process binding the same
number in the window between the check closing and Apache binding it. That window is a few
milliseconds and nothing here can close it, and the consequence of losing it is the safe one — the
probe fails, the state is `APACHE_NOT_ANSWERING`, and the app's own server is shown *and named as
the app's own server*.

**The six states, which are the report as much as the behaviour.** `NO_DEBIAN` and `NOT_STARTED`
are not serving. `PORT_TAKEN` and `APACHE_NOT_ANSWERING` are not serving, and both refuse the
guest's origin rather than replacing it. `AGENT_UPDATE_FAILED` and `UP` **are** serving, and the
whole difference between them is the agent inside. `AGENT_UPDATE_FAILED` is the one a reader needs
the wording of: **the page is the Debian's and the agent inside it is the one that was already
there, and that is not a failure of the page.** A guest whose agent is out of date is still a guest
serving a page, and showing this app's Kotlin server instead would trade a real page from a Debian
for a page from the app on the strength of a version number.

**Apache is started before the boot's `omp update` runs, and the update gates nothing on the web
half.** A phone on a radio where the check takes the full minute would otherwise show nothing for a
minute and then a page, and taking the `&&` seriously enough to leave the guest down on a failed
update would turn a failed update into a device with nothing on it. So the agent is the last thing
here: Apache, its PHP, its filesystem and the `web` command come up whether or not the update did.

**Nothing in this repository has ever run the guest, the native helper or the real agent on a
phone, an emulator or a test, and proot itself has never been executed in this environment at
all** — no device, no emulator, no ARM Android. The one line of the whole guest start path that can
only ever run on a phone is `ProotForegroundServer.launch`'s builder start, and the source marks it
as such. **Everything above it** — the argument vectors, the environment, the port check, the
probe, the record and every sentence of every report — is plain JVM and is covered by tests. A
helper present in an APK is not a helper that has run, and `omp doctor` says so in its own closing
line. Everything this document says about the guest's web half is a statement about code that has
been read and tested, not about a guest that has answered.

What is on the disk after a successful `omp provision` is real, and worth looking at: the unpacked
Debian under `files/omp/rootfs`, the agent at `files/omp/bin/omp`, and this app's three chat files
written into the guest's `/var/www/html` — the same three bytes `app/src/main/assets/web/` holds,
read once, so the guest's document root is not a second copy somebody has to remember to update.

**`omp doctor` is the command that answers "it does not work".** It is read-only, it starts no
download and writes no file, and it prints ten sections — `identity`, `guest`, `helper`,
`provisioning`, `guest state`, `agent update`, `guest origin`, `chat`, `gaps`, `next` — in a fixed
order, one `key: value` per line, so two runs can be diffed and one can be pasted into a bug
report whole. `agent update` is what the last boot's `omp update` did; `guest origin` is which of
the six states the last start of the guest reached, and it reads a file rather than a socket, so
the read-only promise holds. `docs/vm.md` has the section-by-section reading of the output, and the
closing line of that page says what a green report still cannot tell you.

### The guest's own agent, and what it cannot do

Everything above this point describes the Kotlin agent in this build. The Debian carries a
different one — the real upstream `omp` — and the two are not interchangeable.

**`omp update` runs in the guest at every start of the app, and the `&&` in `omp update && omp` is
preserved literally.** The vector is exactly `/usr/local/bin/omp update` and **no flags at all**,
taken from the real binary's own help text — none of `-c, --check`, `-l, --plugins`, `--canary` or
`--stable` is what a boot wants, because the user wrote `omp update && omp` and the agent is to
*become* current, not to be asked whether it is. A flag this build cannot quote from a help text is
a flag that silently does nothing on a phone, and a test fails if a flag-shaped token ever appears
in that vector.

**Six named outcomes, and they are not a shrug.** `NOT_PROVISIONED` and `NO_AGENT` (both exit 0)
mean the update did not run and must not read as though it failed; `UPDATED` and `ALREADY_CURRENT`
(both 0) mean it landed; `FAILED` is 1 with the guest's own last line recorded; and `TIMED_OUT` is
**124 — the shell's own number for a command that had to be killed**, a status no `omp` exits with,
so "we stopped it" can never be read as "it failed". **Only `UPDATED` and `ALREADY_CURRENT` let
the guest's `omp` start.** Everything else reports the Kotlin agent, which is the correct answer
when the update did not work — a different program, honestly named, rather than a stale one.

**The bound is 60 seconds, and the reason is arithmetic.** The measured fast path is 0.48–0.58 s
and the measured dead-network path is 0.23 s, so a minute is a hundred times anything this build
has ever seen, and short enough that a start of the app is a start of the app. **It is deliberately
not long enough to cover a real install.** A 224 MB binary on a phone radio is minutes, and the
consequence is stated rather than hidden: an update that is genuinely downloading is stopped at the
bound, recorded `TIMED_OUT` with whatever the guest last said, and run again at the next boot. The
alternative is minutes of a `WebView` waiting on a radio, which is the hang the bound exists to
prevent.

**It runs unattended, and that was measured rather than assumed.** `omp update < /dev/null` on the
real binary behaves identically to the interactive form — the same two lines, exit 0, no keypress
and no prompt. Running it twice in a row is also safe and leaves nothing behind, so a boot that
runs it on every start is not a boot that accumulates processes.

**What a successful install prints, how long it takes, and whether a partial download resumes are
not established by this build.** The machine this was measured on was already current, so
"Already up to date" is the only success that has ever been observed here, along with the
no-network failure. Nothing in this app reads a line naming a newly installed version, and the
before-and-after in the record is assembled from two boots' own reports rather than invented from
one. It must not appear to have watched an update land.

**The guest's agent is read-only, and that is a measured result rather than a preference.** The
page has an approval dialog and there is an `/api/approvals/{id}` route, because this app's own
agent puts every write to the user. Three runs of the real binary were measured, and the guest
cannot do it: with nothing asked, the `write` tool **ran and the file was created**; with
`--approval-mode always-ask` the question was asked on a stream that was **at end of input**, so
every answer read as a decline. A pipe the guest holds open makes the agent wait before it starts
at all, and a closed one is the only standard input a child of a PHP process can be given here.
The host protocol that would fix this is `--mode=rpc`, and **this build establishes nothing about
its messages**, so it is not used. The guest therefore passes `--approval-mode write`, and the
**cost is stated plainly: the guest's agent is read-only until a question can reach a person, and
`/api/approvals/{id}` answers every id with the app's own 404 sentence.**

**The guest's question arrives on standard input and is written there, not as an argument.** The
positional form would make a question beginning with `@` a *file to include*, read out of the
working directory — and a chat box must not be a way to name a file for the model. The guest
writes the question on standard input and closes it, and `@` means nothing there.

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

**It is in the app's private storage, and it is not encrypted at rest.** A file under `files/` is
unreadable by other apps and by a file manager, and that is the whole of the protection: anything
running as this app's uid — a debuggable build, an `adb backup`, a root shell — reads the
plaintext. There is no `KeyStore`-backed wrapping of the model key, and no part of this app claims
one. The file is written 0600 through a scratch file and a rename, so a reader sees the whole old
key or the whole new one, and never a key under a wider mode.

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
download, because nothing in this app's *agent* can download anything. It reports the version, the
provider, the endpoint, the model, the key's file, and the transcript's size against its cap. (The
one thing in this app that does download is `omp provision`, and it is a verb of the launcher, not
of the agent.)

`omp ls` lists and changes nothing. `omp rm NAME` refuses without `--force`, printing the real path
and what is under it first, and says so again for a folder this app did not make. Ctrl-C at the
prompt cancels it: no half-created folder, no session moved.

Outside a conversation, `omp update` says in three lines that this is not a conversation and how to
make one, adds a fourth that it downloaded nothing, and opens no folder. It is an agent verb and
not a launcher one, so it is never confused for a conversation called `update` — and the price,
stated plainly, is that `run`, `update`, `key` and `help` are unopenable as conversation names
everywhere.

The whole thing needs the all-files grant, because `Documents` does. Without `grant-storage` it
says exactly that and creates nothing — not a folder that looks like it worked. And on a pipe, in
a script or under `vm exec`, `omp` prints the list and the two options and returns, rather than
waiting forever for a keypress that is never going to arrive.

### The same agent, in a browser

There is a second door into the same agent: a browser on this phone. Every time the app opens, a
foreground service starts a small HTTP server on `127.0.0.1` and puts its address on a
notification. A browser pointed at that address gets the same agent, the same conversations and
the same five tools, as a chat. Closing the app does not stop it; the notification's **Stop** does.

**The terminal and the chat are two views of one Activity, and which one is on screen is decided
by which server answered.** The app carries a `WebView` and loads the chat into it on every
launch; it is *displayed* only when the origin in force is the guest's Apache inside the Debian,
and the terminal is shown otherwise. **A guest that comes up while you are in the terminal takes
the screen, and Back gives the terminal straight back** — the shell keeps running, and any command
in it keeps running, so the switch costs a glance and not a session. The decision is made once,
after the guest start has resolved, from four values: what is on the disk, the token, the published
URL, and whether a request for this build's own chat document came back from the guest's port. A
page cannot reach that decision, cannot cause it to be made twice, and cannot affect what it
returns. `docs/vm.md` has the line in `omp doctor`'s `chat` section that says which origin was
chosen and why.

The URL is on the notification in full, token included, because a loopback URL nobody can open is
not a URL. `web` in the terminal prints the same address, says whether anything is accepting on
that port, and names where the conversations are; `web stop` ends the service and gives the port
back.

**The token is the whole of the authentication, and it is a real boundary with a real edge.** A
loopback port is reachable by every app on the device, so without one, any of them could read your
conversations and spend your key. With one, a request that does not carry it is a `401`. What it
is not is a defence against a rooted phone or against you: anyone who has the notification, or a
photograph of it, has the token. The whole of that is written on `TokenGate`, and it is written the
way it is rather than the way it would sound better.

**A write is still put to you, and the prompt does not go away.** The browser's approval dialog has
no way out except its two buttons: not a tap on the backdrop, not Escape, not a timer. That is the
same rule the terminal follows, arrived at through the one byte `omp.agent.Session` reads for its
own question — the web server supplies that byte when you answer, and there is no route, setting or
URL that turns the question off.

`web` asks the port rather than a file, so it cannot print a URL that stopped answering: a
service the user stopped from its notification takes the socket with it, and the command says
"not running" instead of handing out a dead address.

The server is written from scratch, like everything else here: a `ServerSocket`, a small parser,
eleven routes, a fixed pool of four threads, a 5-second read timeout, a 64 KiB body cap, and a
`LocalServerTest` that drives it over a real loopback socket on the JVM. **No HTTP library, no
framework, no WebSocket** — a streamed answer is server-sent events, which is the grammar
`omp.agent.http.Sse` already parses on the client side, so the stream the phone writes is one the
app's own code can read.

**Which server answers needs two facts and not one.** The app's own server for the Kotlin agent
that is in this build, and Apache inside the Debian for the real one — but naming the guest's port
is necessary and not sufficient, and the difference is the whole of the rule: **the guest's origin
is built only after a request for this build's own chat document has come back from it.** A Debian
that was downloaded is not a guest that is answering, and a server on a port is not this build's
guest either. A build that named the port and stopped there would hand a `WebView` an address that
never loads on every device where the guest did not come up — and, where something else held the
number, a chat from a program the user was never told about. Both answers are real; neither is a
fallback in the sense of being embarrassing, because the Kotlin agent is the only agent a 32-bit
device can ever have.

**The token gets in the one way that works for both.** The app appends it to the URL it hands the
`WebView`; the page exchanges it for a cookie. Nothing guest-side has to know this app's token for
that to work, which is the point: the API side of the guest is a question that has not been
answered, and this half does not pre-empt it.

**A `WebView` renders whatever HTML the page sends, and the page can come out of a Debian the
user can `apt install` into.** That is inherent in serving the UI from a guest and it is not
mitigated here, because it cannot be. What is bounded is what the page can reach: no JavaScript
bridge, `file://` and `content://` off, mixed content off, and every URL that is not this origin's
own scheme, host and port refused — for subresources as well as navigations, because a page that
may only *navigate* to its own origin can still exfiltrate through an `<img>`. JavaScript is on,
because a chat is JavaScript, and that is the honest cost: the boundary is the network one, not
the scripting one.

## Permissions

| permission | why |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | to browse shared storage. Not granted at install: run `grant-storage`, or tap the hint in the prompt. |
| `INTERNET` | `curl`, and the model's endpoint |
| `QUERY_ALL_PACKAGES` | without it `pm list packages` returns almost nothing on Android 11+ |
| `KILL_BACKGROUND_PROCESSES` | `am force-stop` |
| `FOREGROUND_SERVICE` | the chat server outlives the Activity. It is a `specialUse` service, not `dataSync`: Android 14 caps `dataSync` at about six hours a rolling day and then stops it, which is the opposite of a server that should be up whenever the app is. `ChatServiceManifestTest` holds the manifest and the `startForeground` call to the same type, because a mismatch between them kills the app on launch with a stack trace about a type. |
| `POST_NOTIFICATIONS` | without it the notification that shows and stops the service is not shown on API 33+ |

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
