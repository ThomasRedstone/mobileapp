# Notification bridge + core-service daemon: design notes

Follow-on from `docs/ubuntu-touch-phase6-handover.md` (the pairing/PPoG fix session) and
`docs/ubuntu-touch-architectural-paths.md` (the SIGSTOP-on-background architecture question that
session raised but deliberately deferred). This doc is from a later, separate session that picked
that question back up, ran a real validation spike, and scoped out phone-notification forwarding
alongside it. Not implemented yet — this is the plan to start from next time.

## Recap: where the app actually stands

Pairing, PPoG, and real bidirectional sync (BlobDB, health data) are confirmed working end-to-end
on real hardware — see `ubuntu-touch-phase6-handover.md` for that session's full root-cause chain
(X11/DISPLAY, AppArmor GATT send/receive rules, the `java.util.prefs` writable-path bug class).
Branding (name, icon) and a couple of UI bugs (nav bar clipping fixed; onboarding scroll confirmed
as an upstream AWT-on-Linux limitation, not fixable in app code) were also closed out. Current
shipped version at the end of that session: `coreapp.tomredstone` 0.1.27.

This session's starting point was `docs/ubuntu-touch-architectural-paths.md`'s open question:
does the app need to split into a confined UI click + a separate background daemon to survive
Ubuntu Touch's `SIGSTOP`-on-background lifecycle, or is that overkill?

## Finding 1: the core-service split is real, validated, and cheap

Confirmed via a real spike (not just the earlier SIGSTOP-freeze/resume test, which turned out
inconclusive — the app was sitting on the un-tapped onboarding screen the whole time, never
reached the actively-connected state): a headless build of `coreapp` — same `Main.kt` entry point,
gated by `$COREAPP_HEADLESS=1` to skip the `application { Window { ... } }` block entirely — brings
up the full backend (`PebbleAppDelegate.init()`, `WatchManager`, `GattServerManager`) with **zero**
Compose/AWT/Xwayland involvement, and sits stable (low CPU, no exceptions) indefinitely. Confirmed
live via `aa-exec` against the real installed click.

This validates `docs/ubuntu-touch-poc-plan.md`'s original Phase 1 plan (never implemented): a
headless `linuxArm64` binary, `.deb`-packaged, running as a `systemd --user` service, wrapping
`libpebble3` + the connection/business logic, talking to a thin UI client over D-Bus/a Unix socket.
The UI click stays properly confined and SIGSTOP-able; the daemon, not being a click at all,
doesn't fall under Lomiri's foreground-app lifecycle and isn't SIGSTOPped when the UI backgrounds.

**Practical note for whoever picks this up:** don't reuse `$COREAPP_HEADLESS` as the real daemon's
entry point long-term — it was a one-off validation gated inside the existing Click for speed
(AppArmor's exec grant is scoped to the exact installed
`@{CLICK_DIR}/@{APP_PKGNAME}/@{APP_VERSION}/**` path, so a genuinely separate daemon binary needs
its own `.deb` packaging from the start, not a copy-and-patch trick — that was tried and doesn't
work without root). The real daemon should be its own build target from day one.

## Finding 2: notification forwarding has no free lunch on Ubuntu Touch, and needs the daemon anyway

Android's `NotificationListenerService` — a privileged, OS-granted "see every app's notifications"
API — has no equivalent here. Confirmed directly:

- `org.freedesktop.Notifications` (what every app calls to show a notification) is owned by
  `lomiri` itself (the shell/compositor), not something a third party can intercept without help.
- Checked every AppArmor policy group shipped on this OS version
  (`/usr/share/apparmor/easyprof/policygroups/ubuntu/2404.1/`) — no "eavesdrop the notification
  bus" group exists. The only notification-adjacent group, `push-notification-client`, is scoped to
  `/com/lomiri/Postal/@{APP_PKGNAME_DBUS}/**` — our own app's private push mailbox only, useless
  for seeing other apps' notifications.
- `history` (SMS/call aggregation via `com.lomiri.HistoryService`) *is* real and usable — same
  "reserved" tier as `bluetooth`, which this app already declares. Worth wiring up on its own
  merits regardless of the broader notification question — cheap, sanctioned, real value.

Real prior art exists for the general case: [RockWork](https://gitlab.com/muhammad23012009/rockwork)
(GPLv3, Qt/QML + C++, classic-Bluetooth-SPP-first — doesn't target this hardware's BLE-only
Core/Rebble generation, and the codebase/toolchain is too far from ours to adopt or fork directly).
Its `rockworkd/platformintegration/ubuntu/notificationmonitor.cpp` solves exactly this problem via
genuine D-Bus eavesdropping:

```c
addMatchRule("type='method_call',interface='org.freedesktop.Notifications',"
             "member='Notify',eavesdrop='true'");
```

Telling detail: no AppArmor confinement discussion anywhere in that code, because `rockworkd` is a
`.deb`-installed `systemd` daemon, not a click — click AppArmor confinement doesn't apply to it at
all, so it's unconfined by construction and the eavesdrop match rule just works. This is the same
conclusion as Finding 1 from a different angle: the daemon split isn't only about surviving
`SIGSTOP`, it's *also* the thing that makes real notification forwarding possible in the first
place, since the confined UI click structurally cannot do this.

Also worth porting later (same file tree, same technique, not yet read in detail):
`callchannelobserver.cpp` (telephony call state — the other stubbed `Linux*` no-op,
`LinuxLegacyPhoneReceiver`, in this codebase's own `LibPebbleModule.jvm.kt`) and
`organizeradapter.cpp` (calendar, likely via `evolution-data-server`).

## Finding 3: the daemon should be Rust, not Kotlin/JVM

Decision, not yet started. Rationale:

- Matches this fleet's own established convention for standalone Ubuntu Touch system daemons/tools
  (`contacts-ut`, `linux-auto`, `systools`, `tailtoggle` were all deliberately ported to Rust — see
  `../ut/rust-qml-without-python-or-cpp.md`).
- Avoids the JVM startup-time/idle-memory cost `docs/ubuntu-touch-architectural-paths.md` flagged
  as a real concern for anything meant to run persistently in the background.
- `zbus` (not the older `dbus` crate) is already proven in this fleet for a real Lomiri D-Bus
  service (`contacts-ut`'s `com.lomiri.pim.AddressBook` integration) — the natural choice here too.
- Because the daemon is headless (no QML/UI at all), it's *simpler* than `contacts-ut` -
  `qmetaobject`/`cpp`/Qt bridging isn't needed at all, just `zbus` + `systemd`. Closer to that
  project's `native/contacts-core` — a plain, Qt-independent library crate, testable via a
  throwaway on-device CLI binary before any client wiring exists — than to its QML-facing root
  crate.
- One landmine to carry forward from that doc: `zbus`'s `#[zbus::proxy]` macro defaults to
  PascalCase D-Bus method names, but not every real interface matches (confirmed there against
  `com.lomiri.pim.AddressBook`'s actual camelCase methods) — verify every method name against
  `Introspect()`/`busctl` before trusting the macro default, same discipline this session's own
  AppArmor/BlueZ work leaned on all the way through.

## Standalone distribution + permission gating

Explicit goal, not just an implementation detail: if this becomes a standalone daemon other clicks
can hook into (not just this app), access needs to be user-controlled, not "any click that knows
the bus name gets it." Two independent, stackable gates:

1. **OS-level (coarse, kernel-enforced):** AppArmor peer-label mediation, same mechanism this
   project already uses for `bluetoothd` (`dbus (send/receive) ... peer=(label=unconfined)` scoped
   to a specific path). By default no click can talk to a service we expose; each consumer needs an
   explicit grant in *their own* profile. A true first-class "reserved" policy group (like
   `bluetooth`/`history`, which force OpenStore manual review) would need upstreaming into UBports'
   OS image — slow, not something we control. Realistic middle ground: a supplementary AppArmor
   patch per consumer, same pattern as this session's own `coreapp-apparmor-patch` payload.
2. **App-level (fine-grained, actually user-facing):** the daemon can identify *which* click is
   calling via `org.freedesktop.DBus.GetConnectionAppArmorSecurityContext`/`GetConnectionCredentials`
   — a kernel-verified identity, not spoofable by the caller — then maintain its own allow-list,
   default-deny anything unrecognized, and prompt (via our own UI, or a system notification) the
   first time an unknown app asks, persisting the decision. Same shape as Android's runtime
   permissions or `polkit`'s authentication agent, just implemented ourselves since Ubuntu Touch
   doesn't provide one generically. Enforcement lives in our code, but the identity check
   underneath is kernel-backed, so it's a real gate.

## Suggested order for next session

1. Scaffold the daemon as its own Rust crate/repo (or a new directory alongside `mobileapp`, tbd),
   `builder: rust` in `clickable.yaml`, `.deb`-packaged, `systemd --user` unit — matching
   `docs/ubuntu-touch-poc-plan.md`'s original Phase 1 shape.
2. Get `zbus` eavesdropping `org.freedesktop.Notifications.Notify` working standalone first
   (throwaway CLI-style validation, no coreapp integration yet) — proves the core technique before
   any permission-broker or watch-forwarding work sits on top of it.
3. Design the allow-list/consent broker (Finding 3's two-gate model) before wiring any real
   consumer to it, including `coreapp` itself — get the security model right while there's only one
   intended client, not after other clicks are already depending on it.
4. Only then: port the actual `libpebble3`/`WatchManager` core-service logic out of `coreapp`'s JVM
   process into whatever the long-term daemon shape turns out to be, using the `$COREAPP_HEADLESS`
   spike as proof the split works, not as the literal shipped mechanism.
