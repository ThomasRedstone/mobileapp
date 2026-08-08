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
- **Spike 3 (Compose Desktop over X11) — partial signal, promising.** Under Xvfb (real X11, no
  Libertine/Xwayland/touch hardware involved), Skiko's native libraries load and the app runs to
  a clean exit with no crash (`ubuntu-touch-poc/ui-client-spike`). GL context creation fails and
  falls back gracefully — expected given this sandbox has no GPU, not informative about a real
  device's GPU driver. Pixel-level proof of correct rendering was **not** obtained:
  `window.paint()`-based capture returned a blank frame, and `Robot`-based screen capture hung
  (this Xvfb build lacks the XTest extension). So: the rendering stack initializes and doesn't
  crash on X11, but visual correctness is unverified, and Xwayland-in-Libertine specifics plus
  touch input are entirely untested by this proxy.
- **Spike 2 (BlueZ D-Bus) — blocked, no useful proxy available.** This sandbox has no system
  D-Bus, no `dbus-python`/`gi` bindings, and no practical way to host a mock `org.bluez` service
  without effort disproportionate to the signal it would produce — a stub service wouldn't tell
  us anything about BlueZ's real object paths, permissions, or AppArmor behavior on-device
  anyway. Needs real hardware.
- **Spike 4 (touch input via Xwayland) — blocked, no proxy possible.** Touch input translation
  can only be observed on physical touch hardware; there is nothing to substitute for it here.
  Needs real hardware.

No further Phase 0 progress is possible in this environment without physical Ubuntu Touch
hardware (or at minimum a Halium/UT emulator image).

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
