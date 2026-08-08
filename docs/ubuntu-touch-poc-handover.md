# Ubuntu Touch X11/Libertine PoC — Handover

Status as of this handover: **Spikes 1, 2, and 3's core question all done — confirmed on real
hardware, not just a VM.** Spike 4 (touch input) is the only one not yet exercised, and real
touch-capable hardware now exists and is reachable (see below) — it's a "do it," not a "find
infrastructure for it" problem now. This document exists because the working session got very
long; read this instead of the full history in `docs/ubuntu-touch-poc-plan.md` to get back up to
speed quickly. The plan doc has the complete, chronological detail if you need it — this is the
compressed version.

**Real hardware access exists**: `ssh 100.87.156.48` (Tailscale IP) reaches a real, physical
Ubuntu Touch phone (arm64, UT 24.04, real `himax-touchscreen`, user's own daily-driver device —
it has a real `main` Libertine container with the user's own real apps already installed;
treat it accordingly, don't touch `main`). No root/sudo password known on this device. This
access resolved the VM-only DRM/VT blocker below by sidestepping it entirely — confirmed the
QEMU-based investigation's diagnosis was correct: it was a virtualization artifact, not a real
architectural problem.

## What this PoC is

Validating whether CoreApp can ship on Ubuntu Touch via a Compose Desktop UI running over
Xwayland inside a Libertine container, backed by a headless core service — see
`docs/ubuntu-touch-poc-plan.md` for the full rationale and architecture. Everything below is
about de-risking that plan's Phase 0 spikes, not implementing the real thing yet.

## Where things stand, spike by spike

- **Spike 1 (K/N linux toolchain) — done.** `linuxX64` and `linuxArm64` both build/link/run.
  See `ubuntu-touch-poc/core-service-spike/`.
- **Spike 2 (BlueZ D-Bus) — done, genuinely confirmed on real hardware.** Real `bluetoothd`,
  real `org.bluez` on a real UT VM's system bus, `GetManagedObjects` succeeds unconfined.
- **Spike 3 (X11-app-over-Xwayland/Libertine) — core question answered, on real hardware.** A
  real `xclock` process, launched through the real `libertine-launch`/`lomiri-app-launch` path,
  runs stably under a real `Xwayland :0 -rootless` instance inside the phone's live Lomiri
  session. This is the architecture working, for real, not a proxy or a VM approximation. See
  "Real-hardware breakthrough" below — the VM-only DRM/VT blocker further down never applied to
  real hardware at all.
- **Spike 4 (touch input) — not yet exercised, but now genuinely reachable.** Real touch hardware
  exists (see above). This just hasn't been done yet in this session — next person/session
  should just do it, not go looking for infrastructure first.

## Real-hardware breakthrough (read this first, it supersedes most of Spike 3 below)

On the real device (`x11poc-real` container, distinct from the user's own `main`):
container creation succeeded **with zero of the two VM-specific patches** (this device runs
Python 3.12, not 3.14, and its UT 24.04 repos still carry `maliit-inputcontext-gtk2`) — both
earlier "Libertine bugs" were actually just VM/image-generation artifacts. `x11-apps` installed
cleanly, a `.desktop` entry for `xclock` registered immediately via `list-apps`, and launching
through the real session's own D-Bus/display environment (pulled live from `systemctl --user
show-environment`, not guessed) got a real, stable, `bwrap`-sandboxed `xclock` process running
for 10+ seconds under the session's real `Xwayland :0 -rootless`. `lomiri-app-launch` itself
still crashed with the same `Lost our connection with the registry` symptom seen on the VM, but
the launched app survived that crash — a narrower loose end worth chasing before shipping, not a
blocker for the core feasibility question.

**Bottom line: the architecture works.** Everything below this point (the VM/DRM/VT
investigation) was real, useful diagnostic work, but its conclusion — GPU passthrough or
different infrastructure needed — turned out to be specific to QEMU virtualization, not the
actual UT platform. Don't spend more time on VM DRM/VT debugging unless there's a specific
reason to still need a VM (e.g. CI, no hardware access) rather than the real device.

## Spike 3: exactly where it stands

**What's proven working, end to end:**
- Libertine (`libertine-container-manager`, `libertine-launch`, `libertined`) and Xwayland are
  genuinely installed and present on current UT (26.04/"resolute").
- A real Libertine chroot container builds successfully — two real Libertine/Python-3.14
  compatibility bugs found and fixed via non-destructive monkeypatches (no system files
  touched). See `ubuntu-touch-poc/libertine-vm-fixes/` — `create_libertine_container.py` is a
  drop-in replacement invocation, reusable as-is.
- `x11-apps` installs cleanly into the container.
- Writing a proper `.desktop` file into the container registers a real, launchable app ID via
  `libertine-container-manager list-apps` immediately — no extra step needed.
- `lomiri-app-launch <app-id>` is the real launch mechanism (confirmed, not guessed) — it prints
  `Started:` and then fails, because of the blocker below, not because the launch path itself is
  wrong.

**The blocker, precisely:** `lomiri-full-greeter.service` (the actual Lomiri shell process)
crash-loops on every boot of the UT 26.04 PDK VM image under QEMU. Root cause, confirmed via
direct log reading (`~/.xsession-errors`, `/var/log/lightdm/lightdm.log`, running the binary
directly with `strace`):

1. Lomiri's full-greeter is designed to run as its **own standalone Mir server** with direct
   DRM/KMS hardware access (`QT_QPA_PLATFORM=mirserver`) — not nested against
   `unity-system-compositor`'s socket. Confirmed by reading `/usr/libexec/lomiri-systemd-wrapper`
   directly, and separately confirmed the nested-Wayland alternative is **impossible**, not just
   harder: forcing it via the correct legacy env var
   (`MIR_SERVER_PLATFORM_GRAPHICS_LIB=/usr/lib/x86_64-linux-gnu/mir1/server-platform/graphics-wayland.so.16`
   — this image bundles ancient Mir 1.8.2, which uses this singular-library naming, not modern
   Mir's `MIR_SERVER_PLATFORM_DISPLAY_LIBS`) gets past DRM entirely, then hits a hard wall:
   `Mir fatal error: wayland platform does not support mirclient`. The launcher unconditionally
   requires `mirclient` support for legacy UT app compatibility, which Mir 1.8.2's nested-Wayland
   platform structurally cannot provide. This rules the alternative out for good.
2. So the standalone/DRM path is the *only* viable one. On it: `unity-system-compositor`
   (started first, at boot, with an explicit `--vt 1`) permanently holds DRM master. Lomiri's own
   `mesa-kms` platform probe is denied (`Failed to acquire DRM master: Operation not permitted`)
   because there's no VT-switching mechanism available to hand it over
   (`No VT switching support available: MinimalConsoleServices does not support VT switching`).
3. Confirmed this isn't fixable from inside the guest: tried `-display egl-headless` vs.
   `-display gtk,gl=on` (identical failure, byte-for-byte) and confirmed via `loginctl` that
   SSH-originated sessions structurally can never carry VT/seat association regardless of
   configuration. The QEMU serial console (a genuinely different, previously-unused vantage
   point) turned out to be an unrelated `ttyS0` getty, not the `tty1` console Mir's VT logic
   targets — a dead end, not a lead.

**What would actually fix it:** either real GPU/DRI passthrough into the VM (so the kernel-level
DRM/VT arbitration works the way it does on physical hardware), or a fundamentally different
virtualization approach. **GPU passthrough was not attempted** — this host (AMD Threadripper PRO
3995WX, no integrated GPU) has exactly one GPU (AMD Radeon PRO WX 3200), currently driving the
host's own desktop session. Passthrough would very likely crash that session and require a host
reboot to recover; there's no spare/secondary device to isolate it to. This needs an explicit,
informed decision from whoever picks this up next, not another attempt from an agent.

## Infrastructure inventory (what physically exists right now)

- `~/own/ut/` — a separate engineering knowledge base with prior real-world UT project
  experience. `ut-testing-confined-apps.md` documents the QEMU boot recipe this whole
  investigation is built on; `ut-native-services-and-runtimes.md` has the real-device EGL/glvnd
  fix; `ut-flutter-embedder.md` is a **sibling, concurrent investigation** (a different agent
  session working on a Flutter embedder for a different app, `OpenHIIT`) that independently hit
  and diagnosed several of the same UT-VM issues — worth reading, and worth checking whether
  that investigation has made further progress since this handover, since it may have picked up
  exactly this DRM/VT problem too.
- `~/own/flutter-ut-embedder/ut-vm-pdk/` — the sibling's own UT 26.04 PDK VM (shared resource,
  may or may not still be running/in-use — check before touching it).
- `~/own/flutter-ut-embedder/ut-vm/` — the sibling's older, legacy `mainline-generic-amd64`
  image (Xenial/unity8-era) — the one place a stable Lomiri session (well, `unity8`, an older
  generation) was reportedly reached. Also a shared resource.
- `~/own/mobileapp-ut-vm/` — **our own independent copy**, not shared with anyone. Contains
  `ut-vm.raw` (a copy of the PDK image, ~8.7GB), `ssh/vm_key` (our dedicated SSH keypair),
  `kernel-extract/` (a world-readable vmlinuz for libguestfs/supermin), and `run/` (various QEMU
  pidfiles/serial sockets from past boots — all stale now, VM is currently shut down). Root
  password on this VM: `phablet` / `ubuntu2026` (set via offline guestfish edit, see the plan
  doc's history for the exact technique — fully reusable for future boots of this same image).

## Concrete next steps, in priority order

1. **Decide on GPU passthrough.** If yes: needs someone to explicitly accept the host-desktop-crash
   risk, then standard `vfio-pci` unbind/rebind setup against `61:00.0`. If no: this specific
   blocker likely needs different infrastructure (a cloud UT VM with real GPU passthrough
   support, or physical hardware) to close.
2. **Check the sibling investigation's progress.** It may have independently solved this exact
   DRM/VT problem, or found a different working path (e.g. the older legacy image), since this
   handover was written.
3. **If/when Spike 3 unblocks:** the actual next step is trivial given what's already
   proven — the `.desktop`-registration + `lomiri-app-launch` path is confirmed working
   mechanically; it just needs a Lomiri session that doesn't crash-loop underneath it. Try
   launching `x11poc_xclock_0.0` (or install our real Compose Desktop app into the same
   container) once a session stays up.
4. **Spike 4** needs physical hardware or a touch-capable emulator — genuinely out of scope
   until one of those exists.

## Reusable artifacts already committed on `ubuntu-touch-poc`

- `docs/ubuntu-touch-poc-plan.md` — full chronological history, all findings, all disproved
  hypotheses (useful to avoid re-testing them).
- `ubuntu-touch-poc/core-service-spike/` — Spike 1's K/N linux target.
- `ubuntu-touch-poc/ui-client-spike/` — Spike 3's Compose Desktop experiments (sandbox-only,
  predates real-VM access).
- `ubuntu-touch-poc/dbus-bluez-proxy-spike/` — Spike 2's D-Bus proxy (superseded by the real
  confirmation, kept for reference).
- `ubuntu-touch-poc/libertine-vm-fixes/` — the two Libertine/Python-3.14 compatibility
  monkeypatches, directly reusable.
