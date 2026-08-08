# Ubuntu Touch — X11/Libertine Proof-of-Concept Plan

Status: not started. This is an exploratory proof-of-concept, not a committed feature — see
`CLAUDE.md` platform rules (Android/iOS are the supported targets). Nothing here should be
read as changing that until the PoC proves itself out.

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

- **Spike 1 (K/N linux toolchain) — partially verified, promising.** `linuxX64` builds/links/runs
  natively on the dev host. `linuxArm64` cross-compiles, links, and runs correctly under
  `qemu-aarch64` emulation against Konan's bundled glibc 2.25 sysroot (see
  `ubuntu-touch-poc/core-service-spike`). This de-risks the toolchain question significantly,
  but is **not** confirmation on real Halium hardware — the emulated run uses Konan's own
  sysroot, not Ubuntu Touch's actual userland/libc. Still needs a real-device run before this
  spike can be called a clean pass.
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
