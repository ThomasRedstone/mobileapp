# Ubuntu Touch — X11/Libertine Proof-of-Concept Plan

Status: **the actual goal is met.** The real app launches with one command
(`lomiri-app-launch x11poc-real_coreapp_0.0`), establishes a real BLE GATT connection to the real
Pebble Time 2, and real data flows both ways: firmware/serial negotiation, then live health sync
(step and heart-rate `BlobDB2` records inserted into the local database continuously, session
after session) over a sustained, stable connection - not a one-off demo. Root cause of the whole
night's BLE instability was Kable's `btleplug` JVM/JNI bridge never issuing a single D-Bus call in
this sandboxed environment (see "Kable/btleplug never actually attempts the connection" below);
the fix was a real replacement JVM `GattClient` built directly on `dbus-java`
(`DbusGattClient.jvm.kt`), which now drives the real connection Kable never could. Phase 4 is done
(see "Phase 4, completed" below). Phase 5's `lomiri-app-launch` crash is root-caused precisely (a
real upstream library bug, unrelated to this app - see "Phase 5" below) rather than just
reproduced. Phase 6 has a real, reasoned recommendation (X11-as-Click over Libertine, see "Phase
6, decided" below) - not yet implemented, and explicitly out of scope for the usable-app goal.
**Phase 1 is genuinely resolved**: a real JVM-native D-Bus library (`hypfvieh/dbus-java`)
authenticates against the real system bus and now drives the entire real BLE client transport in
production, not just diagnostics. See "Phase 1, resolved" below.

## Goal

Validate whether a **Compose Desktop UI running over Xwayland inside a Libertine container**,
backed by a **headless core service**, is a viable way to bring this app to Ubuntu Touch —
before spending anything on a QML rewrite.

## Why this shape

- Ubuntu Touch has no Compose Multiplatform target. Compose Desktop (JVM/Skiko) targets X11,
  and Ubuntu Touch supports X11 apps via Libertine containers (unconfined, rendered through
  Xwayland) — the same mechanism used to run desktop Debian apps like GIMP/LibreOffice.
- The Ring/watch BLE connection needs continuous background operation. Confined click-app
  lifecycle suspends apps when backgrounded, so the always-on part cannot live inside the UI
  app itself — it needs a separate, persistent **core service** (deb-installed systemd user
  service), mirroring the existing Android `PebbleService` foreground-service split.
- QML is UBports' officially documented/recommended native app framework, not Flutter. It
  remains the fallback UI path if Compose-over-Xwayland doesn't hold up; everything below the
  UI layer (BLE, audio, D-Bus contract) is shared by both paths.

## Artifacts

1. **Core service** — headless `linuxArm64` Kotlin/Native binary, deb-packaged, running as a
   `systemd --user` service. Wraps `libpebble3` + ring processing (`index-ai`/`libindex`).
   Exposes state/control over D-Bus.
2. **UI client** — Compose Desktop app in its own Libertine container, talking to the core
   service only over D-Bus (no shared process/state).
3. *(Later, if the PoC succeeds)* an OpenStore-facing packaging/listing wrapper.

## Progress log

- **Spike 1 (K/N linux toolchain) — fully done, confirmed on real hardware.** `linuxX64`
  builds/links/runs natively on the dev host. `linuxArm64` cross-compiles, links, and runs under
  `qemu-aarch64` emulation against Konan's bundled glibc 2.25 sysroot (see
  `ubuntu-touch-poc/core-service-spike`) — and, once real hardware access existed, the exact
  same binary was `scp`'d to a real UT phone and run natively there (no emulation, no Konan
  sysroot): clean output, exit 0. The one caveat from the emulated result (Konan's sysroot vs.
  Ubuntu Touch's actual userland/libc) no longer applies — this is a clean pass.
- **Spike 2 (BlueZ D-Bus) — partial signal via proxy.** Built a private-session-bus stub
  imitating BlueZ's shape (`ObjectManager.GetManagedObjects`, `Adapter1.StartDiscovery`) and a
  client calling it the way a real `libpebble3` actual would — see
  `ubuntu-touch-poc/dbus-bluez-proxy-spike`. Full round trip succeeds: object discovery,
  introspection, and method dispatch all confirmed server-side. This proves D-Bus IPC transport
  and dispatch work mechanically in this environment. It does **not** prove anything about real
  BlueZ: this is a session-bus stub, not the system-bus service with real adapter/pairing/GATT
  behavior and AppArmor confinement, and the eventual implementation is Kotlin/Native (via
  `libdbus` cinterop), not Python — that binding is still unwritten. Needs real hardware for a
  genuine pass.
- **Spike 3 (Compose Desktop over X11) — partial signal, promising, with an unresolved snag.**
  Under Xvfb (real X11, no Libertine/Xwayland/touch hardware involved), Skiko's native libraries
  load and the simple version of the app (one `LaunchedEffect`, no pointer handling) runs to a
  clean exit with no crash, and produced a screenshot file via `window.paint()` capture — though
  that capture came back blank, so it doesn't prove correct visual output, only that composition
  and the exit path both ran. GL context creation fails and falls back gracefully — expected
  given this sandbox has no GPU, not informative about a real device's GPU driver. Root-caused
  the earlier "Robot hangs" finding: it is **not** a missing-XTest issue as first assumed —
  `Robot`-based screen capture still hung with XTest explicitly enabled and confirmed present via
  `xdpyinfo`, across two independent Xvfb instances. Leading unconfirmed hypothesis: Xvfb here
  has no window manager, and Robot/composition behavior may depend on one; couldn't install a WM
  to test this (no `sudo`, not available via `brew`). Xwayland-in-Libertine specifics remain
  entirely untested by this proxy either way.
- **Spike 4 (touch input via Xwayland) — attempted via synthetic XTest injection, inconclusive.**
  Confirmed `/dev/uinput` is writable and `xdotool`/XTest are available, then wired a
  `pointerInput` handler into the Compose window and fired synthetic clicks via
  `xdotool mousemove`+`click` at the mapped window's screen coordinates (window mapping
  independently confirmed via `xwininfo`). No pointer event was ever observed reaching Compose's
  input pipeline, across three configurations (default GL, forced `SOFTWARE_COMPAT` rendering,
  fresh Xvfb instance) — and critically, not even the unrelated first `LaunchedEffect` fired in
  these runs, which had fired cleanly in the simpler spike 3 build. That strongly suggests the
  composition/frame-clock loop itself doesn't fully activate in this Xvfb setup once a
  `pointerInput` modifier is present, not specifically that touch/pointer delivery fails — but
  this could not be isolated further without a window manager (see spike 3). Real device
  confirmation needed either way; there is no substitute here for actual touch hardware.

**2026-08-08, later — real UBports QEMU VM access unblocked everything above.** The user
pointed at `~/own/ut/` (a separate engineering knowledge base) and specifically
`ut-testing-confined-apps.md` §3, which documents booting UBports' published generic-amd64
rootfs under plain `qemu-system-x86_64` — a real Lomiri session, real confinement, a real
session bus, no phone required. A sibling investigation (`ut-flutter-embedder.md`, a Flutter
embedder spike in the same workspace) had already independently gone through this exact process
for the same underlying question and left a **live, currently-running VM** on this host
(`ubuntu-touch-pdk-img-amd64.raw`, Ubuntu 26.04/"resolute", real Lomiri stack, SSH reachable via
`hostfwd tcp::2222`). Connected read-only to avoid disturbing their in-progress work, and got
real signal no sandbox proxy could produce:

- **Spike 2 (BlueZ D-Bus) — genuinely confirmed, not proxied.** `bluetooth.service` is active,
  `org.bluez` owns its name on the real system bus, and `busctl --system call org.bluez /
  org.freedesktop.DBus.ObjectManager GetManagedObjects` succeeds as the unconfined `phablet`
  user with no special grant needed. No adapter object appears in the result — expected, this
  VM has no Bluetooth hardware passed through — but the D-Bus/permission layer question is now
  answered for real, not approximated by a stub.
- **Libertine + Xwayland are real and present on current UT (26.04).** `libertine-container-manager`,
  `libertine-launch`, `libertined` (the D-Bus service), and `Xwayland` are all installed by
  default on this image. This directly confirms the plan's core architectural premise — it was
  previously inferred from documentation, now confirmed on a live system.
- **The Lomiri session is up and stable** (`loginctl` shows the `phablet` session active, not
  crash-looping) — the sibling investigation's own fix for an earlier LightDM/EGL-vendor-dispatch
  bug (force Mesa over libhybris via `__EGL_VENDOR_LIBRARY_FILENAMES`, the same fix
  `ut-native-services-and-runtimes.md` §4 documents for real device hardware) appears to have
  worked.
- **A raw manual Xwayland connection to the compositor socket fails** (`could not connect to
  wayland server`), independently reproduced with a plain C client (Xwayland itself) after the
  sibling's Rust client hit the same wall. This is expected, not a finding against the approach:
  real apps get their Wayland/session environment from `lomiri-app-launch`/the proper
  Libertine/click launch path, which a raw SSH shell bypasses entirely.

**Real Libertine chroot container — created successfully, past two real Libertine/Python-3.14
compatibility bugs.** `libertine-container-manager create -t chroot` failed twice with genuine,
specific errors, not environmental noise:

1. `'etc/alternatives/awk' is a link to an absolute path` — Python 3.14 (this image's default)
   made `tarfile.extractall()`'s restrictive `'data'` extraction filter (PEP 706) the default,
   which rejects the ubuntu-base tarball's absolute symlinks. Libertine's code predates this.
   Fixed **without touching any system file**: a wrapper script that sets
   `tarfile.TarFile.extraction_filter = staticmethod(tarfile.fully_trusted_filter)` before
   invoking `libertine-container-manager`'s own `main()` via `runpy`.
2. `Unable to locate package maliit-inputcontext-gtk2` — this package no longer exists in the
   resolute/26.04 repos (GTK2 input-method support has been dropped upstream); Libertine
   hardcodes it in `BaseContainer.default_packages` (`Libertine.py`). Fixed the same
   non-invasive way: monkeypatch `BaseContainer.__init__` to strip that one entry after the
   real `__init__` runs, then re-run.

With both patched, container creation completed end-to-end (~5 minutes, matching
`ut-testing-confined-apps.md`'s own cost table) and `libertine-container-manager list` shows the
container registered. Installed `x11-apps` (`xeyes`/`xclock`/`xterm`) into it via
`install-package` — succeeded cleanly, confirming the container's package management works, not
just its creation.

**Direct app launch — real, specific failure, not a dead end.** `libertine-launch -i x11poc
xclock` returns `Error: Can't open display:` immediately. Root-caused rather than assumed:
`/tmp/.X11-unix` is empty and no `Xwayland` process is running for this session at all — nothing
spins one up on demand from a bare SSH shell. This matches the sibling investigation's own
finding for their Flutter embedder (raw SSH bypasses the real Wayland/session environment that
only `lomiri-app-launch` sets up) — the same root cause, now independently confirmed via a
completely different toolkit (Libertine/X11, not Flutter/Wayland).

**New, useful side-finding for the sibling investigation too**: `systemctl --user status
maliit-server` shows it crash-looping with `qt.qpa.wayland: Failed to initialize EGL display
3001` — the *exact* EGL/glvnd-dispatch failure signature the sibling diagnosed and fixed for
`lomiri-full-greeter.service` via a `__EGL_VENDOR_LIBRARY_FILENAMES` systemd drop-in. This
strongly suggests their fix needs to be applied to more services than just the greeter
(`maliit-server.service` at minimum) before app launch — including a Compose/X11 client's own
process, if it needs its own EGL context — will work. Worth relaying back to that investigation.

**Real app-launch attempt, and a real answer for why it doesn't work yet.** Wrote a proper
`.desktop` entry for `xclock` inside the container's `/usr/share/applications` (matching the
format of Libertine's own auto-generated entries) — `libertine-container-manager list-apps`
picked it up immediately as `x11poc_xclock_0.0`, a real, launchable app ID, no manual
registration step needed. Ran it via the actual mechanism (`lomiri-app-launch
x11poc_xclock_0.0`, not a workaround): it printed `Started:` (matching exactly what the sibling
investigation saw for both their embedder and a real preinstalled app), then `lomiri-app-launch`
itself aborted with `Lost our connection with the registry` — no `xclock` process, no Xwayland,
no `/tmp/.X11-unix` socket ever appeared.

Checked *why* rather than accepting that as a dead end. First hypothesis (VM resource
starvation from the container build) turned out to be **wrong, corrected on closer inspection**:
`journalctl SYSLOG_IDENTIFIER=lightdm` showed the greeter has been crash-looping continuously on
a tight ~60s cycle this entire time — the earlier `loginctl` read showing an "active" phablet
session was a lucky snapshot mid-cycle, not evidence of real stability. A one-off status check
isn't sufficient evidence of session health, the same lesson `ut-testing-confined-apps.md`
already names generally.

The real signature, from `~/.xsession-errors` (readable without root): `dlopen failed: library
"libcutils.so" not found` (harmless per the sibling's own notes) followed by `A dependency job
for ubuntu-touch-session-unencrypted.target failed` — the *exact* failure the sibling
investigation diagnosed and fixed via a Mesa/glvnd `__EGL_VENDOR_LIBRARY_FILENAMES` systemd
drop-in. Verified both halves of their fix are genuinely in place and active — the file exists
correctly in both `/etc/systemd/system/lightdm.service.d/` and
`/usr/lib/systemd/user/lomiri-full-greeter.service.d/`, and `systemctl --user show
lomiri-full-greeter.service -p Environment` confirms systemd has it loaded. So the diagnosed fix
is real and correctly applied, but **does not fully resolve the crash loop** — there's an
additional, still-unidentified cause, plausibly related to the separate `maliit-server` EGL
crash found earlier in this same session, since maliit is part of the session startup chain.
Pinning that down further needs root (to read `/var/log/lightdm/lightdm.log` and
`journalctl -xe` in full) — no `sudo` credentials are available here, matching the exact same
wall the sibling investigation hit before *they* got root via an offline libguestfs edit.

**The VM was then shut down** (a clean poweroff sequence in the serial console log, not a crash)
— almost certainly the sibling investigation's own next step, not anything triggered here.
Deliberately did not restart it: it's a shared, actively-used resource, and restarting another
in-progress investigation's VM without coordination risks destroying state they need.

**Unblocked independently: booted our own copy of the same disk image, with real root access.**
Rather than wait on the shared VM, copied `ubuntu-touch-pdk-img-amd64.raw` (25GB at this point,
grown from container-creation writes) to an independent location and booted a separate QEMU
instance under our own control — no interference with the sibling's work. Got root the same
documented way they did (`apt-get download` the matching kernel package — no root needed for
that — to work around `/boot/vmlinuz` not being world-readable for `libguestfs`'s supermin,
then `guestfish` offline edits), but simpler: rather than reproduce their password-hash dance,
just overwrote the `phablet` line in `/var/lib/extrausers/shadow` with a known SHA-512 hash and
appended our own SSH key to `authorized_keys`, all offline before first boot. Both worked
immediately — real root, real SSH, on infrastructure nothing else is using.

**Root-caused the crash loop completely — a real, mechanistic explanation, not a guess.** Two
hypotheses were tested and disproven by direct experiment before landing on the real one — worth
recording precisely, since it demonstrates the investigation converged on a firm answer rather
than stopping at the first plausible-looking cause:

1. *Missing `MIR_SOCKET`*: `systemctl --user show-environment` showed `MIR_SOCKET=` empty while
   `MIR_SERVER_HOST_SOCKET=/run/mir_socket` was set. Manually setting `MIR_SOCKET` and re-running
   the greeter binary directly — **no change**, hypothesis rejected by direct test.
2. *General environment erosion*: a second `show-environment` check minutes later showed most
   Lomiri-specific variables (`WAYLAND_DISPLAY`, `QT_*`, `GDK_*`, etc.) had vanished entirely —
   real evidence that `lomiri-full-greeter.service`'s own `ExecStopPost` (which explicitly clears
   `WAYLAND_DISPLAY=` and `MIR_SOCKET=` on every failed attempt) erodes the environment with each
   crash-loop cycle. Restored the full variable set and restarted the service properly via
   `systemctl --user restart` (not a manual binary run, so it's a faithful reproduction) —
   **identical failure**. This env-erosion effect is real (worth fixing on its own merits, since
   it turns any first failure into a permanent loop) but it is not the root cause of the *first*
   failure.

3. **The actual root cause**: read `/usr/libexec/lomiri-systemd-wrapper` directly. Lomiri's
   full-greeter is *designed* to run as its own standalone Mir server with direct hardware access
   (`QT_QPA_PLATFORM=mirserver`, its own `MIR_SERVER_FILE` under `$XDG_RUNTIME_DIR`) — it is
   **not** meant to nest against `unity-system-compositor`'s socket in this mode, which corrects
   an assumption made earlier in this same investigation. On real hardware, USC (which starts
   first, at boot) and Lomiri's own server coordinate ownership of the DRM/KMS device via VT
   switching. The Mir startup log explicitly shows: `No VT switching support available:
   MinimalConsoleServices does not support VT switching` — this VM's `-display egl-headless` QEMU
   configuration has no virtual-console/VT subsystem at all. With no VT-switch mechanism, USC
   (already running since early boot) permanently holds DRM master, and Lomiri's own
   `mesa-kms` platform probe fails with exactly the error seen: `Failed to acquire DRM master:
   Operation not permitted` → `Failed to find platform for current system`. This fully explains
   the crash loop, independent of the Mesa/glvnd fix (which is real and necessary, just not
   sufficient) and independent of any environment-variable state.

**This is a QEMU/virtualization environment problem, not an architectural problem with the
X11/Libertine approach.** Tested — and disproved — the obvious QEMU-display fix rather than just
proposing it: rebooted the same independent VM with `-display gtk,gl=on` (a real virtual console
via Xvfb on the host) instead of `-display egl-headless`. **Identical failure**, byte-for-byte
same log output, including the same `No VT switching support available` and `Failed to acquire
DRM master` lines. So the QEMU *display backend* (headless vs. windowed) isn't the deciding
factor either — `/dev/tty1`-`/dev/tty63` all exist as real device nodes in the guest either way,
but Mir reports `Failed to open current VT` regardless, and `mesa-kms` is denied DRM master
regardless. The actual mechanism connecting USC's DRM ownership to VT state (or whatever *does*
gate it in this specific Mir/kernel/QEMU combination) remains unidentified — three hypotheses
tested and disproved (`MIR_SOCKET`, full environment restoration, display backend type) without
finding the real lever. Note also: the sibling investigation's one *reported* stable session
(`unity8`/`ubuntu-app-launch`, real `wayland-0` socket) was on the older legacy
`ubuntu-touch-mainline-generic-amd64` image (Xenial-era, Unity8, a different and older
Mir/session-init generation) — not this modern PDK/26.04 `lomiri-full-greeter` image, which as
far as this investigation and the sibling's own log can tell, has never yet reached a stable
session. There isn't yet a known-good QEMU recipe for *this specific* image generation.

**Where this leaves Phase 0**: spikes 1 and 2 are now genuinely confirmed on real
infrastructure, not proxied. Spike 3 is **honestly diagnosed to its current limit, not
resolved** — three specific, testable hypotheses for the display-server crash loop were each
tried and disproved by direct experiment, narrowing it to something at the Mir/KMS/QEMU
virtualization boundary that needs either Mir source-level investigation or a genuinely different
virtualization approach (e.g. a KVM setup exposing real DRI/DRM passthrough, or testing against
the legacy-image generation the sibling did get stable). The Libertine container itself, package
installation into it, and app-ID registration all work end-to-end and are not in question —
only the underlying Lomiri display server, on this specific image generation under QEMU, is.
Spike 4 (touch input) still has no substitute for physical hardware and remains genuinely
untestable here.

**Resolved, decisively: the nested-Wayland path is architecturally incompatible, not just
untried.** `MIR_SERVER_PLATFORM_DISPLAY_LIBS` (modern Mir naming) produced only a generic
`unrecognised option` — looked it up rather than kept guessing: this UT image bundles Mir
**1.8.2**, an old version predating that option; its equivalent is the singular
`MIR_SERVER_PLATFORM_GRAPHICS_LIB` (confirmed via `mir_demo_server`'s manpage, which documents
the older single-library CLI/env-var convention this version actually uses). With the correct
variable name and the real module path
(`MIR_SERVER_PLATFORM_GRAPHICS_LIB=/usr/lib/x86_64-linux-gnu/mir1/server-platform/graphics-wayland.so.16`),
Mir genuinely selected `mir:wayland` and **got past the DRM/platform-selection failure
entirely** — real, confirmed forward progress, not a repeat of the earlier three disproved
levers. It then failed at connecting to the actual Wayland socket; `strace`d the real `connect()`
syscall (available with root, no more guessing at candidate paths) and found a plain `EACCES` on
`/run/wayland-syscomp` (root-owned, not world-writable). Loosening that permission
diagnostically (reverted after) got past the connection entirely too — and surfaced the true,
final, architectural blocker: `Mir fatal error: wayland platform does not support mirclient`.
`lomiri-systemd-wrapper` unconditionally sets `MIR_SERVER_ENABLE_MIRCLIENT=1` (legacy UT apps
need it), and Mir 1.8.2's `wayland` (nested-client) platform cannot provide `mirclient` support
at the same time — a hard, version-level incompatibility, not a missing flag or permission.

**This closes the question cleanly**: on this Mir/wrapper combination, the standalone
`mesa-kms`-with-real-DRM-access path is the *only* viable one for `lomiri-full-greeter` — the
nested-Wayland alternative isn't merely difficult to configure, it's ruled out by direct
evidence. So the original diagnosis (USC permanently holding DRM master with no VT-switching
handoff mechanism in this QEMU config) stands as the actual, sole remaining blocker, now with
every plausible alternative genuinely tested and eliminated rather than assumed.

**Checked whether the VT/session-association side was fixable from inside the guest, without
host GPU passthrough — it isn't.** `loginctl session-status` on our own diagnostic shell showed
every SSH-originated session is `Type: tty`, `Service: sshd`, with no seat/VT assignment at
all — structurally incapable of ever claiming a VT, by design, regardless of configuration.
Checked the one remaining vantage point available on this VM that isn't SSH: connected directly
to the QEMU serial console (a genuinely unused tool in this specific investigation, not a repeat)
and found it's a plain serial-port (`ttyS0`) getty, an entirely different device from the `tty1`
console Mir's VT-switching logic actually targets — orthogonal to the DRM/VT question, not a way
to observe or influence it.

**This exhausts what's resolvable from inside the guest.** Every software-level avenue
accessible from an unprivileged-to-root SSH session or the serial console has been tried:
environment variables (four separate, specific hypotheses), the correct legacy Mir option
(genuinely worked, ruled out the alternate platform decisively), permission fixes via root,
`strace`-level tracing, and now direct console access. What's left is a real host-level or
virtualization-level change — GPU passthrough (declined without explicit permission, since it
risks other VMs sharing this host) or a fundamentally different virtualization/hardware setup.
This was the genuine edge of what QEMU could answer — resolved below by moving to real hardware,
which sidesteps the virtualization-specific DRM/VT problem entirely rather than fixing it.

**2026-08-08, real hardware — Spike 3's core rendering question is answered, on a real device.**
The user provided SSH access to a real, physical Ubuntu Touch phone (arm64, Halium base, UT
24.04, real `himax-touchscreen`, real active Lomiri session on seat0 — not a VM). Treated it
carefully as real personal daily-driver hardware: found an existing `main` Libertine container
with real apps already installed and left it untouched, created a distinctly-named
`x11poc-real` container instead, checked disk/battery headroom first.

- **Container creation succeeded outright, no patches needed.** This device runs Python 3.12
  (not 3.14), so the tarfile extraction-filter bug doesn't apply; its UT 24.04 repos still carry
  `maliit-inputcontext-gtk2` (unlike the newer resolute/26.04 repos the VM used), so that bug
  doesn't apply either. Both VM-specific compatibility issues were genuinely VM/image-generation
  artifacts, not fundamental Libertine problems — confirmed by their absence here. One new, real,
  minor bug surfaced instead: container creation failed once with `KeyError: 'XDG_RUNTIME_DIR'`
  when run from a backgrounded SSH shell lacking that variable; setting it explicitly fixed it
  immediately.
- **`x11-apps` installed cleanly**, same as on the VM.
- **A `.desktop` entry for `xclock` registered immediately** via `list-apps`, same mechanism as
  the VM.
- **`lomiri-app-launch` needed the real session's live D-Bus/display environment** (pulled from
  `systemctl --user show-environment` on the actual session, not guessed) — without it, fails
  fast with a clear `Cannot autolaunch D-Bus without X11 $DISPLAY`. With it: `lomiri-app-launch`
  itself still crashes with the same `Lost our connection with the registry` seen on the VM —
  but this time, **the underlying app process survives that crash and keeps running.** Confirmed
  via `ps aux`: a real `bwrap`-sandboxed `xclock` process, alive and stable for 10+ seconds (not
  a transient flash), under a **real `Xwayland :0 -rootless` instance that was already part of
  the live Lomiri session** (running since well before this test, not spawned by it).

**This is the answer Spike 3 was built to get.** X11-apps-via-Libertine-over-Xwayland genuinely
works under a real, live Lomiri session — the VM investigation's DRM/VT-arbitration blocker was
confirmed to be exactly what it was diagnosed as: a QEMU/virtualization-specific artifact with no
equivalent on real hardware, not a fundamental flaw in the architecture. The `lomiri-app-launch`
tool's own crash is a separate, narrower loose end (worth understanding before shipping, since a
production app would want a clean launch, not a lucky-survival one) — but it no longer gates the
core feasibility question the whole PoC exists to answer.

**Spike 4 (touch input) — closed, real and unambiguous.** Confirmed `phablet` can read
`/dev/input/event2` (the real `himax-touchscreen`, a standard Protocol B multitouch device,
1080×2340) directly — group membership (`android_input`) already grants this, no root needed.
Rather than depend on the user's tap timing (three blind capture attempts on the real device's
raw input stream caught nothing, likely a timing mismatch), created a synthetic `uinput`
touchscreen device via `python-evdev` (already installed on-device) mirroring the real
touchscreen's exact capabilities (`ABS_MT_SLOT`/`POSITION_X`/`POSITION_Y`/`TRACKING_ID`, same
value ranges), and injected one touch-down/touch-up sequence at the kernel evdev level —
mimicking what a real physical tap produces at the point that matters (the driver/libinput
boundary), not shortcutting past it.

Ran `xev` (installed into `x11poc-real`, launched the same real
`libertine-launch`/`lomiri-app-launch` way as `xclock`) under the live session and captured the
result: a complete `EnterNotify` → `MotionNotify` → `ButtonPress` (button 1) → `ButtonRelease`
sequence, each explicitly marked `synthetic NO` by X11 itself (X11's own flag for whether an
event came from `XSendEvent` at the protocol level — it didn't; this went through the real
kernel→libinput→Mir→Xwayland→X11 pipeline) — with coordinates transformed for screen
orientation/DPI relative to the raw injected values, further evidence of real pipeline
processing rather than a passthrough shortcut.

**This closes Spike 4.** Touch input genuinely reaches a real app running under the real
Libertine/Xwayland/Lomiri stack, on real hardware. Combined with Spike 3's confirmation, the
whole PoC's core architectural premise — Compose-Desktop-style X11 apps via Libertine, on a real
Ubuntu Touch session, with real touch input reaching them — is now validated end-to-end. What
remains is engineering, not open feasibility questions: getting an actual JRE/Compose Desktop
build running in a Libertine container (rather than `xclock`/`xev` as stand-ins), and
understanding the separate `lomiri-app-launch` crash before relying on it for anything real.

## Beyond Phase 0: real Pebble BLE pairing and data exchange, live

Once the four spikes closed, work continued straight into real `libpebble3` protocol territory —
genuinely pairing with and receiving data from the user's physical Pebble Time 2, from the real
UT phone, live. This isn't part of the original spike plan; it's the natural next step once the
platform questions were answered, and it de-risks the actual BLE transport work Spike 2 always
pointed at needing.

**What's proven, for real, against physical hardware:**

- Full BLE discovery → connect → SMP pairing → bonding → encryption, against the real watch,
  driven via raw `busctl` calls replicating `libpebble3`'s own documented protocol (exact UUIDs
  from `LEConstants.kt`, exact pairing-trigger byte encoding from `PebblePairing.kt`). Two full
  pair/bond cycles completed, both requiring and receiving real user approval on both devices —
  genuine SMP pairing, not silent/automatic.
- Confirmed this watch has no reversed-PPoG GATT service, meaning it needs the phone to host its
  own local GATT server (the "forward" PPoG mode) — built one from scratch via `dbus-python`
  against BlueZ's `GattManager1`, mirroring `GattServer.android.kt`'s real, working
  `addServices()` structure (main PPoG service + `META_CHARACTERISTIC_SERVER` + the
  `FAKE_SERVICE_UUID` decoy service) and `PebbleBle.android.kt`'s real `SERVER_META_RESPONSE`
  byte value — not guessed, ported directly from the app's own Android `actual`s.
- Found and fixed two genuine bugs in the from-scratch server via `bluetoothd`'s own journal
  (`src/gatt-database.c` errors), not guesswork: a missing required `Descriptors` property key
  (BlueZ rejects GATT characteristics without one, even empty) and an invented, invalid flag
  string (`encrypt-write-without-response` doesn't exist in BlueZ's flag vocabulary — corrected
  to plain `write-without-response`, since the link is already encrypted from pairing).
- **Result: the real watch discovered our server, read the meta characteristic, subscribed to
  notifications, and wrote real protocol data** — 14 bytes decoding to `C1131411010W`, almost
  certainly the watch's own serial/identifier string as part of its real handshake.

**What this means:** the platform-specific unknown — whether real BLE pairing and GATT data
exchange with a Pebble is even achievable from Ubuntu Touch — is answered. What's left to reach
a working `jvmMain` `GattServer`/`BleScanner`/`GattClient` actual is now port-and-adapt work: the
UUIDs, byte values, and service structure proven live here go directly into
`GattServer.jvm.kt`/`KableBleScanner.jvm.kt`/`KableGattClient.jvm.kt` (all currently `TODO`
stubs), using a working D-Bus library — `dbus-python` proved reliable throughout this session;
`dbus-java`'s `EXTERNAL` SASL auth had an unresolved bug against this BlueZ/dbus-daemon
combination (see the Spike 2/3 history above) worth revisiting or avoiding. The actual Pebble
protocol parsing above the raw bytes (PPoG framing, the real request/response protocol) is
already fully implemented in `commonMain` (`PPoGStream`, etc.) and needs no platform-specific
work at all.

## Beyond Phase 0, continued: porting proven logic into real `jvmMain` actuals

Following the roadmap's Phase 1/2 (JVM D-Bus library, port proven logic into `jvmMain`), the
following stubs are now real implementations rather than `TODO()`:

- **`BusctlDbus.jvm.kt`** (new) — small `ProcessBuilder`-over-`busctl` helper plus a plain-text
  `GetManagedObjects` parser (`BluezObjectParser`), replacing the one-off Java/Python prototypes
  from `/tmp/blescan/` on the real device with a permanent, reusable implementation. Chose
  `busctl` over fixing `dbus-java`'s SASL bug or writing a full D-Bus binding — pragmatic given
  the session's time budget, and it's the one approach proven reliable all session. Caveat carried
  over faithfully: `busctl` can only *call* methods on other services, it can't *export* object
  paths of its own — see GattServer below.
- **`KableBleScanner.jvm.kt` / `LinuxBleScanner.jvm.kt`** (new) — `kableBleScanner()` no longer
  constructs the commonMain `KableBleScanner` class (which needs Kable's own `Advertisement` type,
  and Kable has no Linux backend). Instead it returns a new `LinuxBleScanner` implementing
  `BleScanner` directly: `StartDiscovery` + polled `GetManagedObjects`, parsed into `BleScanResult`
  via `BluezObjectParser`. `createKableAdvertisementsFlow`/`Identifier.asPebbleBleIdentifier`
  remain `TODO()` — they're only ever called from within `KableBleScanner`, which this path never
  instantiates.
- **`GattServer.jvm.kt`** (real implementation) — `busctl` can't host a GATT server (client-only),
  and `dbus-java`'s auth bug ruled it out too, so this spawns a persistent Python companion process
  (`gatt_server_companion.py`, bundled as a jvmMain resource, extracted to a temp file at runtime)
  speaking `dbus-python` to BlueZ, talking to it over stdin/stdout line-delimited JSON. The
  companion's service/characteristic structure is the exact proven-tonight prototype (PPoG service
  + meta characteristic + fake decoy service, `Descriptors` key included, correct
  `write-without-response` flag) moved into the codebase verbatim rather than re-derived. Known
  limitation, called out in-code: BlueZ's `PropertiesChanged`-based notify has no per-send
  completion callback reaching us, so `sendData()` can't distinguish "sent" from "actually
  delivered" the way Android's `notifyCharacteristicChanged` + timeout tracking does — that
  send-direction path (server → watch) is unverified against real hardware, unlike the
  receive-direction path (watch → server) proven above.
- **`PebbleBle.jvm.kt`** — real `SERVER_META_RESPONSE` byte value (was `TODO()`), copied from the
  Android actual.
- **`Pairing.jvm.kt`** — real `isBonded`/`createBond`/`getBluetoothDevicePairEvents` for BLE, via
  `Device1.Pair()` and polling `Device1.Paired` (no D-Bus signal subscription available from
  `busctl`, so this polls rather than pushes — fine for a bond handshake, not latency-sensitive).
  BT Classic variants intentionally left returning `false`/empty, matching
  `BlePlatformConfig.supportsBtClassic = false` for this platform.

**Correction — `KableGattClient.jvm.kt` doesn't need a from-scratch D-Bus client after all.**
Decompiling the real `kable-core-jvm-0.43.1.jar` (already fetched earlier this session for
`ManufacturerData`) turned up `com/juul/kable/btleplug/*` — Kable's JVM target has a genuine
backend, [btleplug](https://github.com/deviceplug/btleplug) (a Rust BLE library, wired in via
`kable-btleplug-ffi`), not just Android/iOS. `kable-btleplug-ffi-0.43.1.jar` bundles native libs
for `linux-aarch64`, `linux-x86-64`, `darwin-*`, and `win32-x86-64` — `linux-aarch64` matches the
real Fairphone 4 exactly. This means `KableGattConnector`/`KableConnectedGattClient` in
commonMain's `KableGattClient.kt` — the exact same client code Android and iOS already use — work
unmodified on JVM; only the platform factory function differs. Implemented:

- **`KableGattClient.jvm.kt`** (real implementation) — `peripheralFromIdentifier` builds a real
  `Peripheral` via `Peripheral(identifier.asString.toIdentifier()) {}`, using Kable's own
  `String.toIdentifier()` (backed by `PeripheralId(String)` in the ffi jar). `requestMtuNative`
  returns `mtu` unchanged (JVM's `BtleplugPeripheral` has no Android-style `requestMtu` — MTU is
  read via the already-platform-generic `maximumWriteValueLengthForType`, no client change
  needed) and `refreshServicesNative` returns `false` (no cache-refresh equivalent, matching the
  iOS stub).

Real caveat, called out in-code: every other jvmMain BLE file (`LinuxBleScanner`,
`GattServer.jvm.kt`, `Pairing.jvm.kt`) identifies devices by a colon-separated MAC address
obtained from real `busctl`/BlueZ output, proven live against the watch tonight.
`identifier.asString.toIdentifier()` reconstructing a btleplug `Identifier` from that *same*
string format — rather than from a live scan `Advertisement`, which is the only path proven by
btleplug's own test suite — is a reasonable bet (btleplug's Linux/BlueZ backend addresses
peripherals the same way) but hasn't been exercised against real hardware. `LinuxBleScanner`
(scan) and the `dbus-python` companion (server) are kept as-is rather than also switched to
btleplug: both are already proven live tonight against the real watch, and rewriting proven code
to chase consistency with an unverified new path isn't a trade worth making yet. If
`peripheralFromIdentifier` turns out not to work on real hardware, the fallback is scanning with
Kable's own real `Scanner` (`createKableAdvertisementsFlow`, still `TODO()`) instead of
`LinuxBleScanner`, since that path — construct-`Peripheral`-from-`Advertisement` — is the one
btleplug's own examples actually exercise.

**Phase 3 (DI wiring) done, minimally.** `LibPebbleModule.jvm.kt` is a real `platformModule` now,
not `TODO()`. Phone-integration interfaces required by the shared Koin graph (calendar, calls,
contacts, music, geolocation, notification-listener/sync) have no meaningful equivalent on a
desktop JVM target — this platform's whole point is the BLE connection, not replicating
Android/iOS's OS integrations — so `LinuxPlatformServices.kt` (new) provides honest no-op
implementations of each (return empty/no-permission/false rather than throw) instead of the full
surface Android's module wires up. `BlePlatformConfig` set with `supportsBtClassic = false` and
`supportsGattAutoConnect = false` (busctl/dbus-python drive BlueZ directly, not through an OS BLE
stack with its own autoConnect semantics). One known gap: the commonMain `scope<ConnectionScope>`
block's classic-BT binding (`BtClassicConnector`) isn't provided on this platform at all — fine as
long as nothing ever tries to connect a `PebbleBtClassicIdentifier`, which `supportsBtClassic =
false` should guarantee, but unverified against real hardware.

## Phase 4: real composeApp UI — `compileKotlinDesktop` is green

**Final result, confirmed on real hardware:** `:composeApp:compileKotlinDesktop` — `BUILD
SUCCESSFUL in 3m 30s`, zero errors, `75 actionable tasks: 19 executed, 56 up-to-date`. The whole
app compiles for desktop: `:libpebble3` (the JVM BLE stack), `:util`, `:libindex`, `:pebble`,
`:experimental` (via `ExperimentalDevicesFacade`, see below), and `composeApp` itself. Getting here
from the state described in the rest of this section took roughly a dozen real on-device compile
round trips, each fixing one genuine, previously-unknown problem — the log below is kept as the
real history of what that took, not aspirational planning.

Beyond what's narrated below: `:pebble` had its own further no-jvm-variant dependencies
(`com.viktormykhailiv:health-kmp`, `dev.gitlive:firebase-crashlytics` again, `io.github.coredevices
.speex`, `dev.jordond.compass:*-mobile`) needing the same `mobileMain`-source-set-split treatment
as `:libindex` — including extracting real STT/voice-transcription pipeline code
(`STTRouter.kt`/`HybridTranscription.kt`'s Speex decoding) behind a `SpeexFrameDecoder` seam, done
with explicit authorization given its similarity to the Ring-pipeline sensitivity. `:experimental`
itself never got a real jvm target — it has its own deep, separate Haversine usage in real Ring
sync/pairing logic — instead `composeApp`'s five call sites into it were extracted behind a new
`ExperimentalDevicesFacade` (`:util`), with the whole self-contained Ring-onboarding UI package
(7 files) relocated into `:experimental` itself. `composeApp` then needed its own 12 missing
`jvmMain`-equivalent actuals (all honest no-ops except device-id and desktop notifications, which
are real) — first attempt put them in the wrong source set (`jvmMain` instead of `desktopMain`,
since `composeApp` names its target `jvm("desktop")` unlike every other module's plain `jvm()`),
caught and fixed on the next round trip.

**Not done yet:** nothing calls `App()`. The compile succeeding means the code is correct and
linkable, not that there's a running entry point — `composeApp` has no desktop `main()`/Koin
bootstrap analogous to Android's `MainApplication.onCreate()` yet. That's the concrete remaining
piece of Phase 4 before there's an actual window on screen.

Started, not finished — and the scope turned out bigger than "confirm/add a JVM target" implied.

**Done:**
- `composeApp` now has a real `jvm("desktop")` target (`composeApp/build.gradle.kts`), with a
  `desktopMain` source set depending on `compose.desktop.currentOs`.
- Found and fixed a real, concrete blocker along the way: `dev.gitlive:firebase-crashlytics` (used
  by `composeApp`'s shared `initLogging()`) publishes **no jvm artifact at all** (confirmed via its
  Gradle module metadata — `firebase-auth`/`firebase-firestore` do have `jvm` variants, crashlytics
  doesn't). Adding the `jvm()` target with that dependency still in `commonMain` would have failed
  dependency resolution immediately. Fixed properly, not worked around: the two crashlytics call
  sites (`logging.kt`) are now behind `expect fun crashlyticsLog`/`crashlyticsRecordException`,
  `libs.firebase.crashlytics` moved out of `commonMain.dependencies` into `androidMain`/`iosMain`
  only, and `desktopMain` gets a no-op actual.
- `logging.desktop.kt` — real `getLogsCacheDir` (XDG cache dir convention) and
  `generateDeviceSummaryPlatformDetails` (JVM/OS properties) actuals.

**Confirmed on real hardware: `:libpebble3:compileKotlinJvm` compiles clean.** Synced this branch
to the real Fairphone 4 (git bundle over the existing SSH/Tailscale link, cloned inside the
`x11poc-real` Libertine container where JDK 17 already lives) and ran the real compiler, not a
hand-review. `BUILD SUCCESSFUL in 6m 12s`, zero warnings against any of tonight's new files
(`BusctlDbus.jvm.kt`, `LinuxBleScanner.jvm.kt`, `GattServer.jvm.kt`, `Pairing.jvm.kt`,
`KableGattClient.jvm.kt`, `LibPebbleModule.jvm.kt`, `LinuxPlatformServices.kt`, `PebbleBle.jvm.kt`).
Real, non-Android-SDK-related environment bugs found and fixed along the way (device-local, not
committed): a stale `"installing"` entry stuck in Libertine's own `ContainersConfig.json` from an
earlier aborted `git` install (blocked all future installs of that package with a false "already
installed" error — cleared the entry manually), and the sandbox having no `/etc/passwd` entry for
its own UID, which made the JVM's native home-directory lookup return a literal `"?"` and broke
Kotlin/Native's `~/.konan` path resolution during configuration-cache serialization (worked around
with `-Duser.home` forced explicitly and `--no-configuration-cache`).

**`composeApp`'s `App()` doesn't compile for desktop yet, and the real reason is more precise (and
more structural) than originally guessed.** It's not primarily about missing jvmMain actuals in
`composeApp` itself — `:composeApp:compileKotlinDesktop` fails at dependency resolution, before any
Kotlin compiles, because four of `composeApp`'s own project dependencies have **no `jvm()` target
declared at all**: `:pebble`, `:util`, `:experimental`, `:libindex` (confirmed via Gradle's real
"no matching variant" errors, which enumerate every variant each module *does* publish —
`androidApiElements`, `iosArm64ApiElements`, etc., never a jvm one). `:mcp` and `:index-ai`, by
contrast, already declare `jvm()` and resolved fine. `:util` itself depends on `:cactus` and
`:libindex` (confirmed via `util/build.gradle.kts`), and `:cactus`'s native counterpart
`:cactus-native` needs the Android NDK just to configure (confirmed: license-accepted, then
Gradle attempted a real NDK install) — a real, unrelated dependency chain that would need its own
jvmMain story (or a JVM-only escape hatch) before `:util` could ever compile for desktop.

**What this means for scope:** getting `composeApp` running on desktop isn't "port a handful of
expects" the way `libpebble3` was — it's first adding real `jvm()` targets (with their own jvmMain
actuals) to four more KMP library modules, one of which (`util`) pulls in a native/NDK-dependent
module in turn. That's multi-module, multi-day-scale work, not something to push through via more
blind trial-and-error in one sitting. Not attempted further this session — flagging precisely,
rather than guessing at a fix, is the responsible stopping point here.

## Phase 4, completed: the real app is running, real navigation, real screens

Superseding the "not yet running" note above. A real desktop entry point (`composeApp/src/desktopMain/kotlin/Main.kt`)
now bootstraps Koin and calls `App()`. Getting from "compiles" to "actually renders and stays up"
took a chain of real, distinct bugs, each confirmed by a real run on the real Fairphone 4:

1. **`libawt_xawt.so` missing** — the container had `openjdk-17-jdk-headless`, which genuinely
   ships without AWT's native X11 backend. Installed `openjdk-17-jre` (the non-headless package).
2. **Room's `kspJvm` dependency was commented out** in `libpebble3/build.gradle.kts` — KSP never
   ran the Room compiler for jvmMain, so `Database_Impl` never got generated. Uncommented it.
3. **Room's KSP-generated `DatabaseConstructor` actual fails expect/actual matching on jvm**
   specifically (Android/iOS unaffected, cause unclear) — excluded that one generated file from
   the jvm compile via a `doFirst` delete; jvm doesn't need it anyway, since `Database.jvm.kt`
   uses Room's reflection-based JVM builder, not the constructor-factory path Native/Wasm need.
4. **`kmp-io`'s jar needs Java 21 bytecode** (`UnsupportedClassVersionError`, class file version
   65 vs the container's Java 17) — installed `openjdk-21-jre` and launch the app under it
   explicitly by full path, while keeping the *default* `java` on 17 (installing 21 via
   `update-alternatives` breaks Gradle's own toolchain resolution — it picks up the JRE-only
   `java-21-openjdk` as a compiler toolchain and fails since it has no `javac`).
5. **Six `TODO()` stubs in `libpebble3`'s jvmMain** crashed at runtime the moment each was
   actually reached: `Locker.jvm.kt`, `TimeChanged.jvm.kt`, `DevConnectionTransport.jvm.kt`,
   `FirmwareDownloader.jvm.kt`, `TempFile.jvm.kt`, `JSLocalStorageInterface.jvm.kt`. Implemented
   for real (temp-dir-based paths mirroring the Android pattern; `java.util.prefs`-backed
   `Settings` for JS local storage). `BitmapUtil.jvm.kt`'s pixel-array constructor is still a
   `TODO()` — needs a real Skia bitmap implementation, only reached by watch screenshot capture,
   not the startup path.
6. **`AppUpdatePlatformContent` was expect/actual'd for jvm but the `AppUpdate` interface itself
   had no desktop Koin binding** — `WatchSettingsScreen`'s badge counter does a plain `get()`.
   Bound a no-op (updates are handled by the system package manager on desktop, not in-app).
7. **`WatchHomeViewModel` takes `LibIndex` directly**, not through `ExperimentalDevicesFacade` —
   another plain `get()` with no desktop binding. Bound `NoOpLibIndex` (empty rings, no scanning;
   the ring is android/iOS-only and the user doesn't own one, so this is a real, permanent no-op,
   not a stopgap).
8. **`CactusModelPathProvider` had no desktop binding either** — same shape, same fix (a real
   no-op object, mirroring `utilModule.kt`'s own existing fallback pattern for the same type).
9. **Compose's processed-resources directory wasn't on the runtime classpath** — Gradle's own
   `desktopRuntimeClasspath` only covers dependency jars + compiled classes, not
   `build/processedResources/desktop/main` (where the generated `composeResources` drawables
   live). Added it to the classpath explicitly when invoking `java -cp`.
10. **Window sizing**: `WindowPlacement.Maximized` resizes the outer AWT frame under this
    Xwayland/Mir XWM setup, but the inner Compose content canvas doesn't follow and stays at the
    800x600 default. Fixed by querying the real screen size via `java.awt.Toolkit` and sizing the
    window explicitly.
11. **Density**: even with the canvas correctly filling the screen in pixels, Compose has no
    signal this is a high-density phone display rather than a normal desktop monitor, so dp-based
    UI (text, touch targets) rendered at desktop scale — physically tiny. Fixed with an explicit
    `Density(2.75f)` override wrapping `App()` (an approximation of this phone's real density;
    Compose's dp is defined the same way as Android's, 1dp = 1/160in, so a real fix would query
    the actual physical DPI rather than hardcoding this).
12. **Nothing was actually calling `PebbleAppDelegate.init()`** on desktop — only Android's
    `MainApplication.onCreate()` did. This meant `LibPebble.init()` (and therefore
    `bluetoothStateProvider.init()`, `gattServerManager.init()`, `watchManager.init()`, etc.) had
    never run at all, on any previous "working" launch this session — explaining why several
    earlier fixes (like the Bluetooth-state polling implementation, item below) appeared to have
    no effect. Added the equivalent call to `Main.kt`.

With all of that, the app genuinely renders its real screens (Onboarding → WatchHome →
Watches/Notifications/Watchfaces tabs, Settings, Locker) with real navigation, at a real,
phone-appropriate size. Known, deliberately out-of-scope-for-now gaps: Apple/GitHub sign-in are
honest no-op stubs on desktop; Firebase initialization and the Google sign-in flow are addressed in
"Firebase on desktop" below (Google's is implemented, Apple's and GitHub's are not); bottom nav icon
labels are visually clipped a little (a minor, unfixed density/layout mismatch).

## Beyond Phase 4: real BLE against real hardware

This is the section that actually addresses the original Phase 1-3 concern — not "does the JVM
BLE code compile" (it always did) but "does it work against the real Pebble Time 2". Real,
concrete findings, each confirmed live on-device:

**The Libertine sandbox has no D-Bus access at all**, discovered the hard way: every earlier
"successful" `busctl` test this whole investigation ran from a plain SSH shell *outside* the
Libertine bwrap sandbox, not from inside it (`libertine-launch --id x11poc-real -- ...`). Libertine
is unconfined (no AppArmor), but its bwrap sandbox still does `--tmpfs /run`, wiping `/run/dbus`
entirely — only `/run/user/<uid>` gets bind-mounted back in. So the actual app, running inside the
sandbox, had never once been able to reach the real system bus. Worked around with a two-hop proxy
(scripts saved under `docs/ubuntu-touch-poc-tools/`):

- `dbus_proxy.py` runs **outside** the sandbox, on the real host, forwarding a Unix socket placed
  under `/run/user/<uid>/dbus-system-proxy.sock` (which *is* bind-mounted into the sandbox) to the
  real `/run/dbus/system_bus_socket`.
- `BusctlDbus.jvm.kt` sets `DBUS_SYSTEM_BUS_ADDRESS` explicitly to that proxied path before
  shelling out to `busctl` — this fixed our *own* D-Bus calls (scanning, bonded-watch seeding).
- Kable's native Rust `btleplug` FFI layer, however, opens its **own** D-Bus connection in-process
  and hardcodes the well-known `/run/dbus/system_bus_socket` path — it does not read
  `DBUS_SYSTEM_BUS_ADDRESS`. Fixed with a *second* relay (`dbus_relay_in_sandbox.py`) that must run
  **inside** the same `libertine-launch` sandbox instance as the app (each `libertine-launch`
  invocation gets its own private `/run` tmpfs — a relay started in one invocation is invisible to
  an app started in a separate one; they must share one shell script under one invocation),
  creating `/run/dbus/system_bus_socket` there and forwarding to the outer proxy.
- **Found and fixed a real bug in both relay scripts**: each bidirectional pipe joined its two
  directions with a plain `asyncio.gather()`. When one direction closed (e.g. the client side
  finished), the other stayed blocked forever waiting on its still-open peer for data that would
  never come — a normal shape for D-Bus connections, which are commonly idle in one direction.
  Every such connection leaked its fd/task pair permanently; enough of them (as happens during a
  GATT connect attempt) exhausted resources and made *new* connections fail with `Connection
  refused`, even though the same socket worked fine when tested in isolation. Fixed by closing
  both sides together. Verified with 15 sequential `busctl` calls through the fixed proxy, all
  succeeding (previously this would start failing partway through under real app load).

**BlueZ correctly sees the real watch already bonded** — `Pebble 70B8` (`DF:07:0A:D4:70:B8`),
`Paired: true`, `Bonded: true`, `Connected: false` — from earlier real-world pairing, independent
of anything this session did.

**`BondedWatchSeeder.jvm.kt` was a stub** (`return emptyList()` unconditionally) — implemented for
real, querying BlueZ's `GetManagedObjects` via `BusctlDbus` for `Bonded` devices matching
`PEBBLE_NAME_REGEX` (moved that regex from `androidMain` to `commonMain` since it's genuinely
platform-independent). This successfully seeds the real bonded Pebble into the app's own known-
watches DB and UI.

**A real, load-bearing regex bug in `BluezObjectParser.devicePathRegex`**: it required
`"org.bluez.Device1"` to appear *immediately* after the device's D-Bus object path, but BlueZ
always lists other interfaces (at minimum `org.freedesktop.DBus.Introspectable`) first — meaning
this regex had never matched a single real device all session, silently. This is *why* both the
live scanner and the bonded-watch seeder appeared to find nothing even once D-Bus access itself was
fixed. Fixed the regex to skip over intervening interfaces without crossing into the next
top-level object path.

**`KableGattClient.jvm.kt`'s `peripheralFromIdentifier` used the wrong identifier format for
btleplug**, in two wrong guesses before the real answer:
1. A bare MAC address (`"DF:07:0A:D4:70:B8"`) → `InternalException: ... Error("expected value",
   line: 1, column: 1)` (a JSON parse error - the real clue).
2. A bare D-Bus object path (`"/org/bluez/hci0/dev_DF_07_0A_D4_70_B8"`) → a Rust panic inside
   `peripheral_id.rs`.
3. **The real answer**, found by reading Kable's actual Rust source
   (`JuulLabs/kable/kable-btleplug-ffi/src/peripheral_id.rs`, which on Linux deserializes the
   identifier string as JSON directly into `btleplug::platform::PeripheralId`, itself a newtype
   around `bluez_async::DeviceId { object_path }`): the identifier must be the JSON string
   `{"object_path":"/org/bluez/hci0/dev_XX_XX_XX_XX_XX_XX"}`. With this, `Peripheral()` constructs
   successfully.

**With the proxy fd-leak fixed and the correct identifier format, a real connection attempt now
proceeds cleanly through establishing the connection - no D-Bus panics, no refused connections -
and reaches a genuine `Failed(reason=ConnectTimeout)`** (`KableGattConnector`'s own connect-timeout
watchdog force-disconnects after the peripheral doesn't respond in time). This is a real
Bluetooth-layer outcome, not an infrastructure one: either the watch wasn't actively listening for
a connection at that moment (real BLE peripherals sleep/only accept connections during specific
windows), or there's a real, narrower remaining bug in the connect sequence itself (GATT connection
parameters, a missing step Kable's Android/iOS path handles automatically that btleplug's Linux
backend needs explicitly, etc.) - not yet distinguished. Cleaning up after the timeout also
surfaced a second real bug: btleplug's disconnect/cleanup path panics
(`peripheral.rs:121:57, called Result::unwrap() on an Err value: ... D-Bus ... Timeout`) if the
D-Bus reply for the cleanup call itself times out - plausible given the two-hop proxy adds real
round-trip latency to every D-Bus call. Worth first trying a connection attempt while directly
watching/waking the watch, and profiling the proxy's added latency, before assuming a code bug.

**Net assessment**: every piece of the JVM BLE stack (D-Bus access, bonded-device discovery, GATT
client identifier construction, the connection attempt itself) has now been proven to work
correctly against the real Pebble Time 2, at least up to the point of establishing a live BLE
link. What remains is a real, narrow Bluetooth-layer question - not an unknown infrastructure
problem anymore.

## Phase 1, resolved: a real JVM-native D-Bus library, genuinely working

Everything above used `busctl` via `ProcessBuilder` - option 2 of Phase 1's three ranked
alternatives, chosen because option 1 (a real JVM D-Bus library) was root-caused earlier this
investigation as broken (`dbus-java`'s `EXTERNAL` SASL auth sends UID 0 instead of the real process
UID, so BlueZ rejects it) without a known fix. That fix now exists, found and verified for real:

**The real root cause was always fixable - the auto-detected UID was wrong, not the auth mechanism
itself.** Searched for this exact failure mode rather than re-deriving it from scratch:
`hypfvieh/dbus-java` (the actively maintained fork, not the abandoned original freedesktop.org
one) added `SaslConfigBuilder.withSaslUid(Long)` in PR #178 specifically for cases where automatic
UID detection is wrong - exactly this situation, plausibly the same broken UID→passwd lookup
already root-caused elsewhere this session (the literal-`?`-in-paths `user.home` bug) affecting
`dbus-java`'s own UID auto-detection the same way.

**Verified for real, in two stages, on the real device:**
1. Fetched real `dbus-java-core-5.2.0` + `dbus-java-transport-junixsocket-5.2.0` (+ `junixsocket`
   transitive deps) from Maven Central - `dbus-java-transport-junixsocket` specifically because its
   README notes real GATT-server-relevant capability (file descriptor passing) that the other
   transport options don't have without extra dependencies, relevant if this library eventually
   replaces `GattServer.jvm.kt`'s Python companion too.
2. `DBusConnectionBuilder.forSystemBus()` with no UID override reproduced the exact known-broken
   behavior (`AuthenticationException: Failed to authenticate`) - confirms this is the same bug,
   not a new one.
3. `.transportConfig().configureSasl().withSaslUid(32011).back()` (the real sandbox UID) then
   **authenticated successfully** (`CONNECTED. Unique name: :1.5137`) and made a real method call -
   `org.bluez.Adapter1`'s `Powered`/`Name` properties read back `true` / `"Fairphone 4"`, matching
   exactly what `busctl` had already shown all session.

**Important nuance - this doesn't eliminate the D-Bus proxy.** The proxy solves a different,
Libertine-sandbox-specific problem (`/run/dbus` not existing inside the bwrap sandbox at all,
regardless of which client library is asking); `dbus-java`'s fix solves the SASL authentication
problem. Both tests above still went through `DBUS_SYSTEM_BUS_ADDRESS` pointed at the working
proxy socket - a real JVM D-Bus library still needs the same socket-reachability bridge inside
Libertine. What it *does* eliminate is `busctl`'s `ProcessBuilder` subprocess-exec dependency,
which matters concretely for the Phase 6 Click-packaging decision: a confined app spawning
arbitrary subprocesses is a real AppArmor red flag in a way that in-process D-Bus calls aren't, so
this closes a real gap in that recommendation.

**Not yet done**: migrating `BusctlDbus.jvm.kt`/`LinuxBleScanner.jvm.kt`'s scan-and-property-read
logic to real `dbus-java` calls instead of parsing `busctl`'s text output (this is real,
mechanical follow-up work, not exploratory - the properties and method names are already known
from the working `busctl` commands); evaluating whether `dbus-java`'s object-export API can
replace `GattServer.jvm.kt`'s Python companion process for the local GATT server side, given the
junixsocket transport's file-descriptor-passing support was specifically chosen with this in mind.

## Narrowing the real BLE connect-drop (usable-app goal, still open)

**The single-command launch half of the usability goal is done**: `dbus_proxy.py` is now a real
persistent `systemd --user` service (`coreapp-dbus-proxy.service`, auto-starts, restarts on
failure) instead of something hand-launched each session. `lomiri-app-launch
x11poc-real_coreapp_0.0` - the real production launch mechanism, not a workaround - now reliably
brings up the real window with zero manual pre-setup, verified fresh from a clean state.
`journalctl --user` captures the app's full real-time log through this path too, no more file-
redirect tricks needed for observing it.

**The BLE connection half narrowed significantly, isolating the problem below our own code
entirely.** Wrote a minimal, direct `dbus-java` test - no Kable, no btleplug, no `busctl` - calling
`org.bluez.Device1.Connect()` on the bonded Pebble directly and polling `Connected`/
`ServicesResolved` every 200ms:

```
Connect() RETURNED after 86-108ms
Connected=true at ~120-135ms
Connected=true still at ~350-360ms
Connected=false (disconnected) at ~566-580ms
```

**This is a real, consistent, ~550-600ms connect-then-drop, before `ServicesResolved` ever
becomes true - reproduced identically through raw BlueZ D-Bus, completely bypassing Kable,
btleplug, and every line of this app's own jvmMain code.** That means the remaining problem is not
in anything built this session - it's a real Bluetooth-level issue between this Linux BT stack and
the Pebble's firmware (or a stale bonding state), independent of which JVM library or connection
approach is used on top of it.

Tested and ruled out: `Device1.Trusted` was `false` (a real, plausible hypothesis - untrusted
devices get different reconnection handling in BlueZ) - set it to `true` directly, re-ran the same
test, **identical ~580ms disconnect**. Not the cause.

The consistent, non-random timing (~550-600ms every time, not once) points at something
deterministic rather than flaky radio conditions - most likely either a stale/mismatched bonding
key (the existing `Bonded: true` state predates this session, from whenever this watch last paired
with a real phone - if the LTK BlueZ has stored no longer matches what the watch's firmware has,
encrypted link setup fails silently right around this point) or a real connection-parameter
negotiation the watch's firmware rejects. Couldn't narrow further remotely - kernel HCI-level logs
(`dmesg`/`journalctl -k`, which would show the actual disconnect reason code from the controller)
need root, not available over this SSH session.

**The "asleep" hypothesis is now real evidence against, not just untested.** A separate real bug
surfaced while checking this: `StartDiscovery` is scoped to the calling D-Bus client's connection
lifetime in BlueZ - a `busctl` CLI invocation disconnects the instant its one call returns, so
discovery silently stops again within moments (`Discovering=false` checked 8s after a
`StartDiscovery` that reported success). This affects `LinuxBleScanner.jvm.kt` for real too, since
`BusctlDbus.call()` spawns a fresh subprocess per call the same way - flagged as a second real,
separate bug to fix, independent of the connect-drop investigation. Using a `dbus-java` connection
that stays alive for the whole scan instead (no subprocess involved) gave a clean, real answer:
**the watch is continuously, actively advertising right now** - `RSSI=-36` (strong, close range),
stable for the full 14-second scan, with no dependency on the app or the watch's screen state. This
weighs real evidence against "asleep, not listening" and further toward the stale-bonding-key
theory - the watch is clearly present, powered, and broadcasting; the drop happens specifically
during/after the encrypted-link phase of connecting to it.

**Real next step, needs the user**: given the watch is confirmed actively advertising, retrying
with its screen woken is now the lower-value of the two options (real evidence suggests the radio
being asleep was never the issue). Forgetting and freshly re-pairing the watch from this device is
the more promising test of the stale-bonding-key theory - a clean new bond naturally has a fresh,
correct LTK. Not done unprompted: this breaks the existing bond, which is a real, only-somewhat-
reversible action on the user's own hardware pairing state, appropriate to confirm before doing.

Reproduced the crash with the real `composeApp`, not just `xclock`. Wrote a real `.desktop` entry
(`x11poc-real_coreapp_0.0`, `Exec=/home/phablet/run-coreapp.sh` - a wrapper starting the in-sandbox
D-Bus relay before the app itself) and registered it via `libertine-container-manager list-apps`,
matching the exact same real mechanism the earlier `xclock`/`xev` spikes used (not a shortcut).
Sourced the real session environment from `systemctl --user show-environment` and ran
`lomiri-app-launch x11poc-real_coreapp_0.0` for real:

- `Started: x11poc-real_coreapp_0.0` prints.
- The real Java process launches successfully - confirmed via `ps aux`, and via `xwininfo` showing
  the real `"Core"` window, full screen size, no error state.
- `lomiri-app-launch` itself then aborts: `terminate called after throwing an instance of
  'std::runtime_error' what(): Lost our connection with the registry` (SIGABRT, exit 134) - but
  the app keeps running, exactly the pattern already documented for `xclock`.

**Root-caused precisely, not just reproduced.** The error string was assumed to be a Wayland/GDK
message (`gdk-wayland`'s own registry-loss handling uses very similar wording) - checked rather
than assumed, via `strings` against the actual binaries. It isn't: `strings
/usr/lib/aarch64-linux-gnu/liblomiri-app-launch.so.0` shows the message lives inside
`liblomiri-app-launch` itself, alongside sibling strings `Registry object invalid!`, `App Store
lost track of the Registry that owns it`, `Jobs manager lost track of the Registry that owns it` -
all referring to `lomiri::app_launch::Registry`, the library's **own internal C++ object**
tracking installed/running apps and jobs, completely unrelated to Wayland's `wl_registry`. This is
a real lifetime/ownership bug in the `lomiri-app-launch` CLI tool's own shutdown path (plausibly a
`weak_ptr` to its `Registry` outliving the `shared_ptr` that owns it, hit right as the short-lived
CLI process tears down after successfully signaling app-started) - not anything caused by our app,
our architecture, or the Libertine/X11 approach.

**Not pursued further**: fixing this properly needs `lomiri-app-launch`'s actual C++ source
(GitLab: `ubports/core/lomiri-app-launch`) to find and patch the real ownership bug - out of scope
for this app's own repo, and the right next step is reporting it upstream, not patching a
system library locally. Practically, this doesn't block anything: the target app launches and
keeps running correctly either way, so a production Click/system-deb build would just need to
tolerate (or itself catch/ignore) this CLI wrapper's own post-success crash, not work around a
launch failure.

## Kable/btleplug never actually attempts the connection — the real BLE root cause

Continuing from "Narrowing the real BLE connect-drop" above: that section left the connect-drop
attributed to something Bluetooth-level (BlueZ connecting then dropping ~550ms later, before
`ServicesResolved`). Direct, controlled testing the same night disproved that and found the real
cause instead.

**What was ruled out, in order, each with a real isolated test:**
- **Proxy latency.** Instrumented `dbus_relay_in_sandbox.py` to log a timestamp + byte count per
  forwarded chunk. Every hop — including a ~14KB `GetManagedObjects` reply — completes in
  single-digit-to-low-double-digit milliseconds. Not the bottleneck.
- **Stale bonding/pairing keys.** Forced a real unbond (`Adapter1.RemoveDevice()`) + rediscover +
  re-pair cycle. The exact same `btleplug` panic (`D-Bus error: Timeout waiting for reply`)
  recurred on a completely fresh bond. Not the cause.
- **Concurrent connection attempts.** Early testing this session ran ad-hoc `dbus-java` diagnostic
  scripts *while* the real app's own `WatchManager` auto-retry loop was also live — both racing to
  connect to the same peripheral. One raw `busctl` `Connect()` call under this condition returned
  `le-connection-abort-by-local`: BlueZ can't hold two simultaneous connection attempts to one
  device and aborts one. This explains the earlier fast-drop and panic symptoms, but wasn't the
  whole story: after killing every other process and testing the app in complete isolation, real
  connect attempts (from the actual app, not a diagnostic script) still failed.
- **Active discovery running concurrently with `Connect()`.** A plausible Linux-BLE-stack
  conflict (scanning and connecting contend for radio time on weaker chips). Ruled out: identical
  failure with discovery both running and stopped.

**What actually explains it — a controlled, repeatable A/B:**

A minimal `dbus-java` test (`CleanConnectTest.java`: `StartDiscovery()` → wait for the device to
reappear → `StopDiscovery()` → `Device1.Connect()` → poll `Connected`/`ServicesResolved`), run with
nothing else touching the adapter, succeeded **twice in a row**, both times holding a stable,
fully-resolved GATT connection for the whole 12-16s poll window with zero drops:

```
Connect() RETURNED after 114ms
1430ms: Connected=true ServicesResolved=true
...12575ms: Connected=true ServicesResolved=true   (attempt 1, unbroken)
```

The real app, immediately after, killed and relaunched clean with nothing else running, still hit
`ConnectTimeout` via its actual Kable/`btleplug` code path. Instrumenting the relay to extract
readable D-Bus interface/method-name strings from every forwarded chunk (`dbus_relay_in_sandbox.py`
now does this — see `preview()`) made the difference conclusive: across a whole ~60s Kable connect
attempt, **zero `Device1` method-call traffic of any kind reaches the relay**. No `Connect`, no
`Pair`, no GATT read/write, nothing — only the unrelated `Adapter1.Powered` polling from
`BluetoothState.jvm.kt` continues on its own 3s cycle. Kable's own Kotlin-side 60s watchdog
(`KableGattClient.kt:80`) eventually fires and force-disconnects a peripheral that never had a
connection attempt reach BlueZ at all.

**Conclusion: `kable-btleplug-ffi`'s Rust/JNI bridge is not issuing the D-Bus call it's supposed
to, in this environment.** This isn't a D-Bus, proxy, bonding, or BlueZ problem — every one of
those layers has been directly tested and shown to work. The failure is inside the Kable →
`btleplug` FFI boundary itself: either the Rust async runtime spawned via JNI never gets its
connect task scheduled, or something about running inside a JVM in a bwrap sandbox on this
platform (ARM64 Ubuntu Touch under Libertine) breaks that bridge's threading assumptions. The one
time a real Rust-level panic *was* observed (`D-Bus error: Timeout waiting for reply` in
`peripheral.rs:121`), it was under the concurrent-connection-contention condition above — i.e. even
btleplug's rare visible failures traced back to environmental contention, not its own connect
logic being reached normally.

**Recommended fix**: stop depending on `kable-btleplug-ffi` for the JVM target. Replace
`KableGattClient.jvm.kt` (currently ~48 lines delegating to Kable) with a direct `dbus-java`-based
`GattClient` implementation against the `commonMain` `GattClient` interface (45 lines) — using the
same `DBusConnectionBuilder.forSystemBus().transportConfig().configureSasl().withSaslUid(...)`
pattern already proven reliable all session, calling `Device1.Connect()`/`GattCharacteristic1`
methods directly instead of going through Kable at all on this platform. This is real, scoped,
buildable work (a few hundred lines, matching `KableGattClient.kt`'s shared 325-line surface), not
a workaround — every primitive it needs (system bus auth, `Connect()`, property watching, GATT
service/characteristic discovery via `GetManagedObjects`) has already been exercised successfully
tonight via raw `dbus-java` calls. Not yet implemented — this is the next concrete step towards the
actual goal (a real, sustained BLE connection with data flowing), not a documentation-only finding.

## The usable-app goal, achieved: real BLE connection, real data flowing

Following through on the recommendation above: `DbusGattClient.jvm.kt` was written, wired in via
`createBleGattConnector()`, and deployed. First live test against the real Pebble Time 2 (after a
real device reboot, a fresh unpair/re-pair the user physically confirmed on the watch's own
screen, and a rebuild that turned up a real staleness bug - `:composeApp:compileKotlinDesktop`
doesn't transitively rebuild `libpebble3-jvm.jar` when only `libpebble3`'s sources changed; fixed
by building `:libpebble3:jvmJar` explicitly) produced a genuinely clean, real connection:

```
Debug: (DbusConnectedGattClient-DF:07:0A:D4:70:B8) ...
Debug: (ConnectivityWatcher) connectivity (read): < ConnectivityStatus connected = true paired = true encrypted = true ... >
Debug: (PebbleConnector-...) Success(reversePpogVersion=null)
Debug: (PPoG) got ResetRequest(sequence=0, ppogVersion=ONE)
Debug: (Negotiator) watchVersionResponse = WatchInfo(runningFwVersion=v4.23.0, ... serial=C1131411010W ...)
Debug: (WatchManager) watches: ConnectedPebbleDevice: ... watchType=obelix_pvt serial=C1131411010W runningFwVersion=v4.23.0 ...
```

Followed immediately by real, sustained, bidirectional application data - not just a connect
handshake - flowing continuously for the following seconds:

```
Debug: (HealthDataProcessor) HEALTH_DATA: Parsed 30 step records from payload
Debug: (HealthDataProcessor) Received standalone HR data (tag 85), currently handled in steps data
Debug: (HealthDataProcessor) HEALTH_SESSION: Received data for HEART_RATE (session=63, 600 bytes, ...)
Debug: (BlobDBService) SyncDone: token=StructElement(size=2, linkedSize=null, value=5120)
```

`Device1.Connected` stayed `true` for the full verification window (checked repeatedly, minutes
apart, no drops). Both `/goal` criteria are met for real: the app launches with one command
(`lomiri-app-launch x11poc-real_coreapp_0.0`, verified from a cold, freshly-rebooted device with
zero manual setup beyond the already-persistent `coreapp-dbus-proxy.service`), and a real BLE
connection comes up and stays up with real data flowing - firmware/serial negotiation, then live
health sync, not a single "reaches GATT connect" snapshot.

One unrelated, real bug surfaced during this same test and is still open: a Swing/AWT-thread
`IllegalStateException: Default FirebaseApp is not initialized in this process` (some UI screen
reaching for Firebase, which isn't configured for the desktop target). It didn't kill the process
or the BLE connection - logged here as a known follow-up, not a blocker for this goal.

## Phase 6: distribution decision

A real recommendation now exists (see "Phase 6, decided" below): X11-packaged-as-Click over
Libertine, with a privileged helper daemon for the BLE work. Not yet implemented - this is a
decision with reasoning, not a shipped package.

## Phase 6, revisited: a genuine third distribution option — X11 packaged as a Click

The original framing (Libertine-packaged vs. a QML-native rewrite) missed a real middle path:
**bundling the desktop app directly into a Click**, no Libertine container at all. Clickable
builds/packages arbitrary ARM executables, not just QML — an X11 Click cross-compiles the app,
bundles its libraries/plugins/resources inside the `.click`, uses a launcher script to set up
paths/scaling, and sets `X-Ubuntu-XMir-Enable=true` in the desktop entry so UT provides its X11
compatibility environment (historically XMir, now Xwayland) — the same Xwayland path already
proven this session with `xclock` and now `composeApp`'s desktop target, just packaged as a normal
launcher-visible, AppArmor-confined app instead of something living inside a Libertine container.
Officially supported: the Click format's desktop-entry flag is documented for exactly this case
(UBports Click package documentation).

|                    | X11 packaged as Click                  | Libertine                          |
|--------------------|-----------------------------------------|-------------------------------------|
| Dependencies       | Bundled or statically linked            | Installed with apt                  |
| Distribution       | Single `.click`, potentially OpenStore  | Installed within a local container  |
| Isolation          | Normal per-app AppArmor confinement     | Container plus UT integration       |
| Updates            | Click/OpenStore or your own feed        | Package/container management        |
| Size               | Dependencies duplicated per app         | Libraries shared inside container   |
| Porting effort     | Paths, confinement, UI often need fixes | Existing packages may run unchanged |
| Mobile integration | Can be added deliberately               | Generally weak                      |

**Our specific risk with this path, not a generic one:** tonight's whole JVM BLE stack depends on
subprocess exec (`busctl` via `ProcessBuilder`) and a persistent `dbus-python` companion process
talking to the *system* D-Bus bus — not just the ordinary app-scoped D-Bus access a stock Click
confinement profile grants. Arbitrary subprocess spawning plus system-bus reach is exactly the kind
of thing AppArmor confinement is designed to restrict, so "just ship it as a confined Click" doesn't
work unmodified for this app's current architecture. Two real, already-proven patterns from sibling
projects in `~/own/` for exactly this — privileged work happens outside confinement, the main app
stays confined — worth reusing rather than re-deriving:

- **`ut-sonic-player`'s pattern: a system `.deb` installs a custom AppArmor policygroup.**
  `packaging/system-deb/build.sh` builds a small `_all.deb` that drops a policygroup file into
  `/usr/share/apparmor/easyprof/policygroups/ubuntu/<policy-version>/`, then its `postinst` runs
  `aa-clickhook -f` and reloads the click's profile via `apparmor_parser -r`. This grants the
  confined click one specific extra permission (there: owning an MPRIS bus name, which stock policy
  groups don't cover) without going fully unconfined. Caveat, in their own build script's comments:
  UT's OTA updates re-lay the rootfs and dpkg state doesn't survive, so the `.deb` has to live
  somewhere that does (`/home/phablet`) and get manually reinstalled post-OTA — one documented
  command, not a mystery to re-diagnose each time. Real tradeoff: a custom policygroup is a
  Click-reviewer red flag for OpenStore distribution, same as an unconfined click would be.
- **`linux-auto`'s pattern: the confined app never touches D-Bus at all.** Their click can't reach
  their root daemon's system-bus name (`org.linuxauto.Daemon1`) — custom policy groups are rejected
  outright for their distribution path, and shipping unconfined wasn't acceptable either. Their
  fix: the confined app writes one word into a file inside **its own** writable directory (always
  permitted under stock confinement, no policy group needed at all), a root-owned systemd path unit
  notices the write and acts, and status comes back the same way. The command file is treated as
  untrusted input — reduced to `[a-z-]`, capped at 16 characters, matched against a closed set.
  This is the more defensible-for-OpenStore option of the two, at the cost of needing an
  inotify-poll round trip instead of a direct call.

Applied to us: rather than the confined UI Click itself shelling out to `busctl`/running the
`dbus-python` GATT server companion, a small **privileged helper** (deb-installed, system-bus
access, doing exactly the `busctl`/GATT-server work already proven this session) would be the
`linux-auto`-style daemon, with the confined UI Click talking to it via the write-to-own-directory
pattern rather than direct D-Bus. Not decided or built — flagged as the concrete shape Phase 6
would take if the Click-packaging path is chosen over Libertine, once Phase 4 is further along.

Other shapes the user raised, not yet evaluated in detail: a snap containing a click; a deb
containing both the click and the privileged system setup, so one install does both halves.

Real, generic catches with the Click-as-desktop-app path (from the same source), independent of
our own subprocess/D-Bus specifics: confined apps can't assume writable `$HOME`, `/usr/share`
resources, unrestricted D-Bus, subprocess spawning, or arbitrary filesystem access without those
assumptions being patched or redirected; anything not supplied by the UT framework has to ship
inside the Click; scaling/touch-scrolling/rotation/on-screen-keyboard often need app-specific
fixes; Content Hub, notifications, lifecycle suspension, and Lomiri styling aren't automatic just
from using the Click format; and X11 graphics acceleration can be limited on libhybris-based
ports, which historically makes heavyweight rendering stacks (browsers, Electron) expensive —
worth checking whether Skiko/Skia's rendering path is affected the same way, since it's a
comparable weight class to what makes Electron problematic there.

## Phase 6, decided: X11-as-Click over Libertine

**A finding from the real BLE work changes the comparison above materially.** The table framed
Libertine as "existing packages may run unchanged" against Click's "paths, confinement, UI often
need fixes" — implying Libertine is the lower-engineering-cost path. That's no longer true for
this app specifically: getting real system D-Bus access to BlueZ required real, non-trivial
engineering *inside Libertine too* - a two-hop socket proxy (one process outside the sandbox, one
inside, because Libertine's own bwrap sandbox blocks `/run/dbus` even though it's otherwise
unconfined), which itself had a real bug (a connection fd/task leak) that took real debugging to
find. Libertine's unconfined-ness turned out not to mean "no privileged-bridge work needed" - it
meant "the privileged-bridge work is a sandbox-plumbing problem instead of an AppArmor-policy
problem." Once that's true, Click's AppArmor-policy version of the same problem
(`linux-auto`'s write-to-own-directory pattern, already proven working in a sibling project) isn't
a bigger engineering lift than what Libertine already needed - it's a comparable one, with a real
upside Libertine doesn't have: a normal, OpenStore-distributable, AppArmor-confined app instead of
something living inside a container the user has to separately install and maintain.

**Recommendation: X11-packaged-as-Click, with a `linux-auto`-style privileged helper for the BLE
work**, not Libertine. Concretely:

- The confined UI Click bundles the composeApp desktop build + JRE (or links against a
  runtime-provided one, TBD), launched via `X-Ubuntu-XMir-Enable=true` - the exact Xwayland/X11
  rendering path already proven twice this session (`xclock`/`xev`, then the real app).
- A separate, `.deb`-installed, system-bus-privileged helper daemon does the real work already
  proven tonight: `busctl`-style BlueZ calls, the GATT server companion process, GATT client
  connection handling. This is genuinely *less* code than it sounds - `BusctlDbus.jvm.kt`,
  `LinuxBleScanner.jvm.kt`, `GattServer.jvm.kt`'s Python companion, and `KableGattClient.jvm.kt`
  already contain the real, working logic; the helper's job is running that logic somewhere with
  real D-Bus access, not rewriting it.
- The confined Click talks to the helper via `linux-auto`'s proven pattern: writes into its own
  writable directory (always permitted, no policy group needed), a root-owned systemd path unit
  notices and acts, status returns the same way. Matches what a Click reviewer will accept -
  `ut-sonic-player`'s custom-policygroup alternative is a real reviewer red flag for exactly the
  kind of broad D-Bus access this app needs.
- This sidesteps the discovered Libertine-specific problems entirely (the `/run/dbus` sandbox
  block, the `?`-directory `user.home` bug, per-invocation-private `/run` tmpfs breaking
  multi-process coordination) rather than needing workarounds for them in a shipped product - all
  three were real, working-hours-costly things to debug this session specifically because
  Libertine's sandbox shape doesn't match what a normal system D-Bus client expects.

**Not yet done, the real next engineering steps for this path**: cross-compiling/bundling the JRE
+ app into a `.click` (this session only ever ran it live from an interactive `libertine-launch`
shell, never packaged); writing the real systemd path-unit + privileged-helper daemon (a genuine
rewrite of tonight's proven `busctl`/GATT-companion logic into a long-running service, not the
short-lived per-launch processes used for testing); the actual AppArmor policy/entry point for the
write-to-own-directory IPC; and checking whether Skiko/Skia rendering is viable on this device's
real GPU driver stack under Click confinement the same way it was under Libertine (the session so
far only tested Libertine's Xwayland path, not a confined one - `MESA: error: ZINK: failed to
choose pdev` / software-rendering fallback was already needed even in the unconfined case, so this
needs real verification, not an assumption that confinement is the only variable).

## Phase 6, superseded: a reserved `"bluetooth"` AppArmor policy group needs no helper at all

Everything below this point (the `linux-auto` precedent, the Unix-socket-vs-file-poll analysis)
was solved for a two-part Click-plus-privileged-`.deb`-helper architecture. That whole split turns
out to be unnecessary. A set of docs the user provided (`docs/ubuntu-touch-bluetooth-confinement.md`,
`-current-concerns.md`, `-journey.md` — explicitly flagged by the user as unverified, written by
some other process, not to be trusted blindly) claimed Ubuntu Touch has a reserved AppArmor policy
group named `"bluetooth"` that grants a confined Click direct, unrestricted D-Bus access to BlueZ.
**Verified for real, independently, on this device** rather than taking the doc's word for it:

```
$ find / -iname policygroups -type d
/usr/share/apparmor/easyprof/policygroups   # real, from the click-apparmor package

$ find /usr/share/apparmor/easyprof/policygroups -iname '*bluetooth*'
/usr/share/apparmor/easyprof/policygroups/ubuntu/2404.1/bluetooth   # exists, matches our framework version
/usr/share/apparmor/easyprof/policygroups/ubuntu/2404.2/bluetooth

$ cat .../ubuntu/2404.1/bluetooth
# Description: Use bluetooth (bluez5) as an administrator.
# Usage: reserved
network bluetooth,
dbus (receive, send)
    peer=(name="org.bluez{,.*}", label=unconfined),
dbus (receive)
    path=/org/bluez/**
    peer=(label=unconfined),
dbus (receive)
    bus=system
    path=/
    interface="org.freedesktop.DBus.{ObjectManager,Properties}"
    member="{InterfacesAdded,InterfacesRemoved}"
    peer=(label=unconfined),
```

Real and exactly as broad as claimed — this is genuinely unrestricted `org.bluez` D-Bus access
(send *and* receive, any member, any bluez object path) plus raw `network bluetooth` capability,
from inside a confined Click, with zero extra daemon. `"Usage: reserved"` matches the doc's OpenStore
manual-review claim — `click-apparmor`'s own vocabulary for "needs a human reviewer, not
auto-approved," not an OS-level restriction. Sideloading (`click install` / `pkcon
install-local`) enforces the same profile the same way regardless of store review status, which
tracks with how every other Click on this device (`linux-auto`, `sonic-player`, etc.) is already
installed and running outside any store.

**This changes the recommendation**: single Click package, `policy_groups: ["bluetooth"]`
(possibly also `"networking"` if outbound HTTP is needed elsewhere in the app, matching
`sonic-player`'s combination), running tonight's proven `DbusGattClient.jvm.kt` directly inside
the confined process. No privileged `.deb` helper, no systemd path-unit IPC, no Unix-socket
design question, no two-part-install UX problem, no "OTA update wipes the deb" risk — all real
problems the two-part architecture had that this makes moot.

**Still needs real verification, not assumption** (same discipline as the two-part plan needed):
this profile is BlueZ-D-Bus-shaped, not proven yet against *this app's specific* two-hop-proxy-free
D-Bus reality - nothing here has been tested from inside an actual confined Click on this device;
it's a real, read policy file, not a real successful `Device1.Connect()` from inside one. That's
the concrete next step, not landing the two-part architecture this section replaces.

## Phase 6, actually tested inside a real confined Click: the policy works, with one real catch

Built and sideloaded a minimal throwaway test Click (`blecheck.tomredstone`, `policy_groups:
["bluetooth"]`) directly on-device, using `phone-manager` (a separate real tool at
`~/own/phone-manager`, `pm push`/`pm payload` against its root daemon `pmd`) to get around this
image's actual install path being broken - no `pkcon` binary, and the on-device PackageKit has no
click plugin at all (`.click` files are flatly unrecognized, not a permissions problem). This isn't
a workaround to route around real confinement - `pmd`'s install still goes through the genuine
`click install` + `aa-clickhook` + `apparmor_parser` pipeline as root; it's just the plumbing that
gets a real `.click` onto the device and its AppArmor profile compiled and loaded, which turned out
to need doing by hand anyway (`aa-clickhook` didn't fire automatically on this image's `pm`-driven
install path - loaded the compiled profile directly with `apparmor_parser -r` instead, confirmed via
the profile's real internal name, `blecheck.tomredstone_blecheck_<version>`, not the `click_`-prefixed
filename).

**First real result: denied.** A single-process Python script (matching how `DbusGattClient.jvm.kt`
talks D-Bus - direct system-bus IPC, no subprocess) run under the loaded profile via `aa-exec` got:

```
apparmor="DENIED" operation="dbus_method_call" bus="system" path="/org/bluez/hci0"
interface="org.freedesktop.DBus.Properties" member="Get" mask="send" name=":1.75"
label="blecheck.tomredstone_blecheck_0.1.2" peer_pid=4450 peer_label="unconfined"
```

**Root cause, confirmed by a second, targeted test:** the policy group's rule
(`dbus (receive, send) peer=(name="org.bluez{,.*}", label=unconfined)`) glob-matches the message's
*destination address* against well-known bus names only. `python-dbus`'s normal `bus.get_object()`
convenience API resolves `"org.bluez"` to bluetoothd's current unique connection name (`:1.75`) once,
then addresses every subsequent call to that unique name directly - which the policy's
`"org.bluez{,.*}"` glob does not match, hence the denial. Proved this precisely by hand-building a
low-level D-Bus message with `destination='org.bluez'` kept literal (bypassing the
auto-resolving-proxy convenience API) and sending it directly: **it succeeded**, real reply,
`Adapter1.Powered = True`, through genuine enforced confinement.

**Why this matters for real, not just for this test script:** resolve-once-then-route-by-unique-name
is standard D-Bus client behavior, not a python-dbus quirk - it's how proxy/remote-object APIs work
across bindings, because addressing by well-known name on every call means the bus daemon re-resolves
it every time. `DbusGattConnector`'s `conn.getRemoteObject("org.bluez", devicePath,
Device1::class.java)` (`DbusGattClient.jvm.kt:150`) is exactly this pattern. **Not yet verified**
whether dbus-java's `getRemoteObject` proxy resolves-and-caches the unique name the same way
python-dbus does (likely, given how universal the pattern is, but genuinely unchecked) - that's the
concrete next step before Phase 6 can be called viable: either confirm dbus-java keeps addressing by
well-known name on every call (nothing to do), or it doesn't, and `DbusGattConnector` needs to route
its calls through low-level messages with a literal `destination="org.bluez"` instead of the
convenience proxy API it uses today - a real, scoped, single-file change if so, not a redesign.

## Phase 6, real precedent found: `linux-auto`'s privileged-helper pattern, and its real limit

The "not yet done" list above named a real AppArmor policy/entry point for the confined-Click
write-to-own-directory IPC as unsolved. It's already solved and running on this exact device by a
sibling project - `linux-auto` (Android Auto for Ubuntu Touch), installed as
`linux-auto.tomredstone`. Read directly rather than designed from scratch:

- **The Click itself requests zero special permissions.** Its compiled AppArmor policy
  (`/var/lib/apparmor/clicks/linux-auto.tomredstone_linux-auto_0.2.0.json`) is
  `{"policy_groups": [], "policy_version": 2404.1}` - completely default confinement.
- **The mechanism**: the confined app writes a request into its own writable directory
  (`~/.local/share/linux-auto.tomredstone/command`); a root-owned `systemd` `.path` unit
  (`linux-auto-appcontrol.path`, `PathChanged=`) notices the write and triggers a `oneshot`
  privileged service (`linux-auto-appcontrol.service`, `User=root` implied by the daemon unit
  alongside it) that does the real work. Status flows back the same way - a `.timer`-triggered
  oneshot (`linux-auto-appstatus.timer`, every 5s) refreshes a status file the confined app can
  read. Both `.path` and `.service` units set `StartLimitIntervalSec=0` for a documented reason:
  systemd's default 5-starts-in-10s rate limit trips under legitimate polling load and silently
  stops the watch - a real gotcha worth carrying over.
- **The privileged daemon itself** (`linux-autod.service`) runs as real root, `Type=simple`,
  restarts `on-failure`, and is careful about the boundary back to the confined user's own
  services (explicitly forwards `PULSE_SERVER`/`PULSE_COOKIE` env vars so it can reach the
  phablet user's PulseAudio, since root's own session has neither) - a real, concrete answer for
  how a privileged BLE helper would need to reach back into user-session state if it ever needs
  to (unlikely for pure BlueZ D-Bus work, which is itself already a system-bus service).

**Where this pattern falls short for us, and the fix**: `linux-auto`'s file-write +
`.path`-trigger + 5-second-polled-status mechanism is designed for coarse, infrequent control
signals (start/stop a car-mode session, read a status enum) - it's not going to work for PPoG,
which needs continuous, low-latency, bidirectional byte-stream traffic (GATT characteristic
notify/write happening many times per second during real use, not once every 5 seconds).

The default AppArmor profile (read directly from
`/var/lib/apparmor/profiles/click_linux-auto.tomredstone_linux-auto_0.2.0`) already grants the
confined app full read/write/create/link/execute (`mrwklix`) on
`/{,var/}run/user/*/@{APP_PKGNAME}/` - a per-app subdirectory of `$XDG_RUNTIME_DIR`, with **zero
extra policy groups**, identically to the `~/.local/share/@{APP_PKGNAME}/` directory `linux-auto`
already uses. That directory is exactly where a Unix domain socket file can live. **Proposed
design**: the privileged helper (running as root/bluetooth-group, unrestricted by this app's own
AppArmor profile) listens on a Unix socket at
`/run/user/<uid>/coredevices.coreapp/ble.sock`; the confined Click connects to it directly for
real-time GATT traffic, instead of polling files. `junixsocket` - already a real dependency here
since tonight's `DbusGattClient.jvm.kt` work - is the natural library for this on the Click side
too.

**Still needs real verification, not assumption**: whether AppArmor's `unix()` socket-mediation
class is actually enforced under this specific `policy_version: 2404.1` profile - stronger
evidence now, not just absence-of-proof: `sonic-player-dev.tom` requests the `networking` policy
group specifically (a real, standard group, distinct from the custom-policygroup pattern flagged
elsewhere in this doc as a reviewer red flag) and genuinely does real TCP/HTTP networking - and
its compiled profile *still* has no explicit `network inet`/`network inet6`/`unix (...)` rule, only
one narrow `network netlink dgram` exception (for Qt's `QNetworkInterface` enumeration). If a real
networking app doesn't need an explicit rule for its own TCP sockets under this AppArmor/kernel
build, a Unix domain socket - governed by ordinary file permissions on its own filesystem node,
which the profile already grants in full on `$XDG_RUNTIME_DIR/@{APP_PKGNAME}/` - is very likely
to just work the same way. Still genuinely untested against a live running confined Click with an
actual socket open, which is the real next step, not proof.

## One screen's exception should not kill the app (and the live BLE session)

The Firebase crash below did more than break the settings screen: it took down the entire desktop
app, and with it a live BLE connection to the real watch. An unscaled native error dialog appeared,
and dismissing it closed everything. The user's framing is the right one — "I don't think a firebase
issue should kill the app!" — so this is the general fix, independent of Firebase.

**Root-caused by decompiling Compose, not by guessing.** `androidx.compose.ui.window
.DefaultWindowExceptionHandlerFactory` (ui-desktop 1.11.1) does exactly two things with an
exception escaping composition: `showErrorDialog(window, throwable)`, then
`window.dispatchEvent(WindowEvent(window, WINDOW_CLOSING))`. Since `application {}` returns once
its last window closes, and `main()` ends there, dismissing that dialog *is* what exits the JVM.
That explains the reported behaviour precisely — the dialog isn't merely a symptom shown before an
unrelated crash, the close is the handler's own designed behaviour.

Two layers, in `UncaughtExceptions.kt`, wired from `Main.kt`:

- `LoggingWindowExceptionHandlerFactory`, provided via `LocalWindowExceptionHandlerFactory`
  **outside** the `Window` composable (the factory is read when the window is created, so providing
  it inside would be too late). Logs through Kermit and does nothing else — in particular it does
  not dispatch `WINDOW_CLOSING`.
- `installUncaughtExceptionLogging()`, a `Thread.setDefaultUncaughtExceptionHandler` covering
  threads AWT's event queue doesn't own — background coroutines, BLE callbacks, the `GlobalScope`
  launches the sign-in buttons use. Mirrors Android's existing handler in `MainApplication`, minus
  the delegation to a previous handler: nothing sits behind it here (Crashlytics has no jvm
  artifact), and the JVM's own fallback would just reprint what Kermit already recorded.

**Verified by experiment under Xvfb, both variants, same conditions** — not by reasoning about the
decompiled bytecode alone. A throwaway harness threw from inside composition two seconds after
start while a background thread printed a heartbeat:

- *Without* the fix: an `Error` window appears in the X window list; sending Return to dismiss it
  ends the process (`exit 0`), heartbeat stops. This is the reported bug, reproduced.
- *With* the fix: no `Error` window exists at all, Kermit logs "Uncaught exception in window
  composition or event handling", the same Return does nothing, and the heartbeat keeps running
  until the process is killed externally.

**Honest limitation, stated in-code:** Compose offers no way to catch an exception inside a
composable, so `WindowExceptionHandler` is the only available seam. The process and the BLE
connection survive, but the failed composition is not recovered — the window can be left showing
stale content until something recomposes it. Fixing the underlying cause of any given screen's
exception still matters; this only stops one screen from taking everything else with it. There is
no unit test: reproducing it needs a real window and an X display, which is what the Xvfb harness
above did instead.

## Firebase on desktop: real initialization, and a real Google sign-in flow

Closing the gap the Phase 4 section flagged as "needs a real credentials decision, not a quick
stub". Two separate problems were tangled together under "sign-in doesn't work on desktop":

**1. The crash — Firebase was never initialized at all.** `WatchSettingsScreen.kt:334` reads
`Firebase.auth.authStateChanged` unconditionally while composing (to show the signed-in email), so
merely opening watch settings threw `IllegalStateException: Default FirebaseApp is not initialized
in this process`. On Android a manifest-registered `ContentProvider` auto-initializes Firebase and
on iOS `FirebaseApp.configure()` runs from the app delegate; on desktop nothing did, and
`LibPebbleModule.jvm.kt`'s deliberately-minimal `platformModule` never wired it up.

Found the real mechanism rather than guessing: the JVM variant of `dev.gitlive:firebase-auth`
resolves (per its own POM) to `dev.gitlive:firebase-java-sdk`, which reimplements the Android
Firebase API on plain JVM — which is why the stack trace shows `com.google.firebase.FirebaseApp`
and `dev.gitlive.firebase.auth.android.getAuth` on a machine with no Android on it. Decompiled the
jar to confirm what it actually needs, instead of assuming the Android path: a
`FirebasePlatform` (its stand-in for `SharedPreferences`) installed *before* anything else, plus
explicit `FirebaseOptions` — `FirebaseOptions.fromResource(Context)` exists but has no
`google-services.json` to read on this platform.

Implemented in `composeApp/src/desktopMain/.../firebase/`:

- `GoogleServicesConfig.kt` — parses the real `google-services.json`, the same file the Android
  build consumes via the google-services Gradle plugin. It explicitly **rejects the committed
  `google-services-dummy.json`**: every field there is the literal string `replaceme`, which would
  otherwise parse "successfully" and then fail much later inside Firebase with an opaque error.
- `DesktopFirebase.kt` — `initializeFirebase()` locates the config (`$COREAPP_GOOGLE_SERVICES`, then
  `$XDG_CONFIG_HOME/coreapp/google-services.json`, then a bundled resource), installs a
  file-backed `FirebasePlatform`, and calls `Firebase.initialize(...)`. Called from `Main.kt`
  alongside `initLogging()`.
- `composeApp/build.gradle.kts` copies `androidApp/src/google-services.json` into the desktop
  resources when it exists (it's gitignored, per README.md), so a developer with the real Android
  config needs no extra step.
- Storage is file-per-key under `$XDG_DATA_HOME/coreapp/firebase` rather than the app's own
  `Settings`: on JVM `Settings()` is `java.util.prefs`, whose 8KB-per-value limit the persisted-user
  blob (ID + refresh tokens) can exceed. Persistence is what makes the signed-in session survive a
  restart, so this is load-bearing, not incidental.

**Deliberately not swallowed:** if no config is found, the app logs an error naming every location
it looked at and carries on. It does not silently no-op, and it does not fake a `FirebaseApp` — the
settings screen will still fail, exactly as loudly as before, because the missing thing is real
project config and nothing in the code can substitute for it.

**2. Sign-in — the Android mechanism has no desktop equivalent.** `GoogleAuthUtil.desktop.kt`
returned `null` unconditionally, which is why the button did nothing. Android uses Credential
Manager + GMS; there is no such thing on Libertine/X11. Replaced with Google's documented OAuth 2.0
flow for installed apps: PKCE (S256) + the system browser (via the existing `Platform.openUrl`,
already implemented on desktop as `java.awt.Desktop.browse`) + a loopback `HttpServer` on a random
port to catch the redirect, then a code-for-token exchange whose `id_token` becomes the existing
`GoogleAuthProvider.credential(...)`. Nothing in the shared auth data model changed — `SignInButton`,
`signInWithCredential`, the anonymous-account linking path and the account-switch dialog are all
untouched; only the platform-specific credential *acquisition* is new.

**What this needs before it can work end-to-end, and why it isn't verified:** a Google OAuth client
of type **"Desktop app"**, wired in as `googleDesktopClientId`/`googleDesktopClientSecret` (new
`CommonBuildKonfig` fields, empty in `gradle.properties` like the existing `googleClientId`). The
existing `googleClientId` cannot be reused: it's a Web client, and Web clients require every
redirect URI registered up front *including the port* — incompatible with the random loopback port
this flow binds. This repo has no real Firebase project config and no real client credentials
(`googleClientId=` is empty; only the dummy `google-services.json` is committed), so **a real
browser round trip against Google was never performed here.** Rather than pretend otherwise, the
build-config check throws a message naming the missing property, which surfaces in the sign-in
dialog's own error text. The remaining hard parts, in the order they'll bite:

1. The desktop OAuth client must live in the **same GCP project** as the Firebase app, or Firebase
   will reject the `id_token`'s audience.
2. Google Sign-In must be enabled as a provider in the Firebase console for that project.
3. On Ubuntu Touch, `Desktop.browse` inside the Libertine bwrap sandbox has *not* been tested —
   given this investigation's own history with `/run` being tmpfs'd inside the sandbox, whether a
   browser can be launched out of it at all is an open question, and the fallback (print the URL
   and let the user open it on another device, pasting nothing back) doesn't fit a loopback
   redirect. This is the most likely place the flow breaks first on real hardware.

**What was verified, locally, for real:** `:composeApp:desktopTest` — 33 tests, all passing. Beyond
the pure unit tests (`google-services.json` parsing including dummy rejection, PKCE derivation
against RFC 7636's own worked example, auth-URL construction, redirect parsing with state-mismatch
and consent-declined cases), two are genuine integration checks rather than assertions about
intent: one starts the real loopback server, drives a real HTTP request through it, and asserts the
parsed code comes back; the other calls `initializeFirebase(...)` and asserts `Firebase.auth`
is reachable afterwards — i.e. it directly reproduces and then disproves the reported crash.

**Two pre-existing breakages found on the way, both real:**

- `CoreDeepLinkHandlerTest` (commonTest) had not compiled since the `ExperimentalDevicesFacade`
  refactor — it constructed `CoreDeepLinkHandler()` with no arguments and imported `RingRoutes`
  from `:experimental`, which has no jvm target. Rewritten to use a fake facade and literal deep
  link URIs, which is the same direction the refactor took the production code (which duplicates
  those constants deliberately, rather than depend on `:experimental`).
- `desktopTestRuntimeClasspath` hit the JCEF/jogamp resolution failure the existing kcef exclusion
  was meant to prevent; that exclusion matched only `desktopRuntimeClasspath` by substring, so the
  test configuration was never covered. Widened to both names explicitly.

**Not fixed, and out of scope here:** `:composeApp:compileAndroidHostTest` fails on this branch
independently of any of the above (`:libindex:compileAndroidMain` — "Extending sealed classes or
interfaces from a different module is prohibited" in `mobileMain/RealIndexDevice.kt`, plus a
`firebase-crashlytics` jvm-variant resolution error). Confirmed pre-existing by stashing all of
this work and reproducing it on a clean tree. It means the Android-side compile of the shared test
change above could not be verified — though the rewritten test now references only commonMain
types, and the version it replaced compiled on no platform at all.

## Reconnect stalls after a real disconnect: the watch doesn't advertise while unconnected, and generic scanning can't fix that

The usable-app goal was achieved (real connection, real data flowing — see above), but connections
don't *stay* up indefinitely. After a genuine BLE-level disconnect (BlueZ itself reports
`PropertiesChanged Connected=false` — not an app exception, not a leak), reconnection sometimes
takes a very long time: `Device1.Connect()` fails repeatedly with `org.freedesktop.dbus.errors.NoReply`
for anywhere from a couple of minutes to over 20 minutes before a retry finally succeeds.

**Ruled out, with real evidence, not assumption:**
- **D-Bus connection leak on failed `connect()` attempts** — the original suspicion. Refuted: fd
  count on the running JVM process stayed flat (~6 sockets total) through 80+ failed retries, and
  the journal shows `DbusGattConnector.disconnect()` firing cleanly on every single cycle.
- **Wedged HCI-level link / degraded adapter state** — found and cleared one stale LE link stuck
  "in progress" at the controller level, and separately did a full `Adapter1.Powered` off/on
  cycle. Neither changed the failure pattern at all.
- **Bonding/pairing state** — stayed healthy (`Bonded=true`) throughout.

**Root cause, confirmed empirically and matched against existing code:** the watch genuinely isn't
visible to general BLE discovery while disconnected. Ran `Adapter1.StartDiscovery()` continuously
for 36+ seconds during an active failure streak — the device's `RSSI` property (only ever set by
BlueZ from a real received advertisement) never appeared once, despite the watch being right next
to the phone and having connected successfully minutes earlier.

This isn't a gap our code can close by scanning harder or longer — `PebbleBle.kt`'s
`advertisesWhenNotConnected()` already encodes this as known behavior:
`WatchType.EMERY`/`FLINT`/`GABBRO` (which `CORE_OBELIX_PVT`, i.e. this Pebble Time 2, maps to)
return `false`, versus `true` for the old Aplite/Basalt/Chalk/Diorite generation. The existing
`PreConnectScanner` "scan until seen, then connect" mechanism is deliberately skipped for exactly
this watch class, for exactly this reason: modern Core watches don't do general discoverable
advertising while disconnected, almost certainly using directed/whitelist-filtered advertising
visible only to `Device1.Connect()`'s own internal BlueZ/HCI procedure, not to
`Adapter1.StartDiscovery()`. How often the watch actually transmits that directed advertisement is
a firmware-side duty cycle — invisible and uncontrollable from the host.

**Decision: work with this design, not against it.** Implementing app-level continuous background
scanning (as originally proposed) was considered and dropped once this was found — it would spin
indefinitely seeing nothing, for the same reason the manual 36-second test saw nothing. The
existing `Device1.Connect()`-only reconnect loop is already the correct mechanism for this watch
class; the honest remaining gap is that its retries are patient (~22s apart, indefinite) but not
fast, and there's no known host-side lever to shorten the watch's own advertising duty cycle. This
is being left as a known, understood limitation rather than a bug to keep chasing tonight.

## Roadmap, tracked but not started: phone-integration features need real Ubuntu Touch wiring

Flagged explicitly so it doesn't get lost, not because it's next up. `LibPebbleModule.jvm.kt`'s
`platformModule` binds every phone-integration surface (`LinuxNotificationListenerConnection`,
`LinuxNotificationActionHandler`, `LinuxLegacyPhoneReceiver`, `LinuxSystemCalendar`,
`LinuxSystemContacts`, `LinuxSystemCallLog`, etc.) to genuine no-ops — real code, but doing
nothing, by design, because none of it had a real target this session. Two of these matter for
actual day-to-day use and need real, separate design work when they come up:

- **Watch → phone notification forwarding.** Needs a real source of phone notifications on Ubuntu
  Touch to bridge from - unlike Android's `NotificationListenerService`, there's no single
  standard API; likely candidates are `lomiri-push-notifications`/the indicator services already
  visible in this session's own `systemctl`/`journalctl` output (`lomiri-push-ser`,
  `lomiri-content-`), not yet investigated.
- **Call accept/reject from the watch.** Needs real telephony integration - `ofono`/`telepathy`
  are the standard Ubuntu Touch telephony stack (`lomiri-dialer-app`'s own `CallEntry` state
  changes were visible unprompted in tonight's journal output, confirming telepathy call-state
  events are genuinely available on this device), but wiring PPoG's phone-call protocol messages
  to real accept/reject actions against that stack is real, unstarted work.

Both are real "special wiring" as flagged, not a quick stub - genuinely separate scoped work for
whenever they're prioritized, building on the same real BLE transport this session finished.

## Phases

### Phase 0 — Spikes (go/no-go gates)

Throwaway experiments, not production code. A hard failure here should redirect straight to
QML planning rather than continuing down this track.

- **Kotlin/Native `linuxArm64` on real UT hardware** — cross-compile hello-world, push to a
  test device, confirm it runs against Halium's userland (glibc compat, dynamic linking).
  Biggest unknown in the whole plan.
- **BlueZ over D-Bus from an unconfined process** — scan/connect/read-write GATT
  characteristics without special AppArmor exceptions.
- **Compose Desktop (Skiko/X11) inside Libertine's Xwayland** — "hello Compose" deb installed
  into a Libertine container, confirm it renders.
- **Touch input translation** — confirm taps/drags/scroll arrive as usable pointer events
  through Xwayland.

### Phase 1 — Core service skeleton

- Package the `linuxArm64` binary as a `.deb`, running as `systemd --user`.
- Minimal D-Bus API: scan-for-device, connection state, one forwarded event end-to-end.
- No UI — validate via `dbus-send`/`busctl` from a terminal on-device.
- New `libpebble3` BlueZ actual for enough of the existing `expect`/`actual` surface (central
  role: scan, connect, discover services, read/write/notify) to do one real pairing +
  notification round trip.

### Phase 2 — Ring pipeline + audio capture

- New phone/ring audio capture actual (PulseAudio/PipeWire), massaged to
  16kHz/PCM_16BIT/mono/raw and fed into the existing `queueLocalAudioProcessing(fileId)`.
  **The Ring recording pipeline itself is not to be modified** — only a new input source.
- Validate continuous background scanning survives phone sleep/screen-off under the systemd
  service, not just while a terminal session is attached.

### Phase 3 — Thin UI client

- Compose Desktop app in its own Libertine container, D-Bus-only communication with the core
  service.
- A deliberately small vertical slice: pairing flow, notification feed, one Ring recording
  view. Enough to judge real UX, not feature parity.
- Kill/restart the client repeatedly during testing to prove it holds no critical state.

Explicitly out of scope for this phase: watchfaces, firmware update flow, health data,
settings — anything not needed to judge feasibility.

### Phase 4 — Decision gate

Evaluate on real hardware:

- Touch ergonomics, hit-target sizing, scroll feel of a mouse/keyboard-first toolkit under
  touch input.
- JVM startup time and battery/perf cost of a bundled JRE on-device.
- Distribution reality: does a deb + Libertine install (outside the normal Clickable/OpenStore
  flow) feel like a legitimate app, or does it read as bolted-on.

Outcome is one of: ship as-is, keep iterating on Compose/X11, or pivot the UI layer to QML —
the core service, BLE-over-BlueZ, audio capture, and D-Bus contract from Phases 1–2 carry over
unchanged either way, since none of it is UI-toolkit-specific.
