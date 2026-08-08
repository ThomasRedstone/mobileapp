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

Checked *why* rather than accepting that as a dead end: `loginctl` showed the Lomiri session had
freshly restarted (new session numbers, `lightdm.service` `Active: ... 27s ago`) — despite the
sibling's Mesa/glvnd systemd drop-in already being present. The heavy `apt`/package-install
activity from container creation almost certainly resource-starved this VM (2 CPU, 3GB RAM,
running a full compositor concurrently) enough to disrupt the session mid-launch. This is
environment fragility under load, not a new independent bug — but it does mean **reliable
app-launch testing on this specific VM needs either more resources allocated to it or spacing
heavy operations away from launch attempts**, and it's a useful data point for the sibling's own
ongoing LightDM stability work, which has seen this exact instability from a different angle.

**Where this leaves Phase 0**: spikes 1 and 2 are now genuinely confirmed on real
infrastructure, not proxied. Spike 3's remaining open question narrowed from "does
Compose-over-Xwayland-in-Libertine even work at all" to a specific, addressable one: get an
Xwayland instance running for the session (either via the proper
`lomiri-app-launch`/click-install path, matching what the sibling investigation is actively
pursuing, or by extending the EGL/glvnd systemd-drop-in fix to whatever's supposed to start it).
Spike 4 (touch input) still has no substitute for physical hardware and remains genuinely
untestable here.

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
