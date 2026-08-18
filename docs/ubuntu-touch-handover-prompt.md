# Handover prompt — paste this as the first message in a fresh session

I'm continuing Ubuntu Touch work on the Pebble/Core companion app (`coreapp.thomasredstone`).
This file is the immediate "what's next" briefing. For deep history read (in this order, as
needed): `docs/ubuntu-touch-reliability-review.md` (rev 3 + 2026-08-18 addenda — the BLE
reliability fix series, its own audit, and this session's two big findings: the pairing-agent fix
and the forward-path GATT-server root cause), `docs/ubuntu-touch-phase6-handover.md` (deploy
ritual, AppArmor patch mechanics, architecture), `~/own/ut/ut-telemetry-broker.md` (the OTLP
telemetry contract, if touching that again). Session summary lives in the transcript if you need
exact command history — this doc only carries what matters for picking the work back up.

**Version currently deployed and running on-device: 0.1.67.** Watch connects fully and reliably —
confirmed live: `ConnectedPebbleDevice` with full `WatchInfo`, health data syncing, BlobDB sync,
data-logging, music control, all working. `bluetoothctl` shows `Connected: yes` holding steady.
This is the best state the watch connection has been in across the whole Ubuntu Touch port so far.

## Where things actually stand

### The watch connection — both major blockers from prior sessions are now fixed

**1. Pairing dialog (root-caused, fixed in 0.1.66).** Previous session's blocker: pairing from the
app showed the confirmation prompt on the watch but nothing on the phone, `Device1.Pair()` failing
after ~32s with `AuthenticationCanceled`. Root cause: `indicator-bluetooth`'s registered BlueZ
agent silently never answers `RequestConfirmation`. Fixed by having `coreapp` register its own
temporary `org.bluez.Agent1` (`PairingAgent.jvm.kt`, wired into `Pairing.jvm.kt`'s `createBond()`)
— auto-confirms only the device path currently being paired, registered as system default only for
the duration of one `Pair()` call. Live-verified working, no `bluetoothctl` workaround needed.

**2. Forward-path GATT server never actually served to the watch (root-caused, fixed in 0.1.67).**
This watch's firmware (`v4.23.0`) predates the reversed-PPoG-V2 service, so it's forced onto the
forward path (phone hosts a GATT server, watch reads/writes it) — which was flagged in the
original reliability review as "the fallback path, still needs hardening" but never actually
worked end-to-end. Root cause, confirmed via a live `btmon -t` ATT-protocol capture: `bluetoothd`
was never serving `coreapp`'s `RegisterApplication`'d PPOGATT service to the watch at all — the
watch's own paginated service discovery hit `Error Response: Attribute Not Found` exactly where
our custom service should continue, despite registration succeeding from our own process's
perspective. Firmware source (`~/own/PebbleOS`) confirmed the watch does full, unconditional,
fresh discovery on every reconnect — not a watch-side caching issue. Leading theory: `bluetoothd`
only merges a locally-registered GATT database into what it serves to a peer while the adapter is
also in an active peripheral/advertising role — a state this app never entered, since every
connection is phone-initiated (`Device1.Connect()`, central role only, no advertisement ever
registered). **Fix**: `GattServer.jvm.kt` now also calls
`LEAdvertisingManager1.RegisterAdvertisement()` with a minimal, content-irrelevant advertisement
alongside the existing `GattManager1.RegisterApplication()` — confirmed live, this alone unblocked
the entire forward path. No new AppArmor grant needed (reuses the existing GATT-server object-path
prefix).

### Also fixed this session

- **Firmware-update-check 400** (`cohorts.rebble.io`): desktop's `CoreAppVersion` was sending a
  raw git hash (`"8e488bd-dirty"`) instead of a real version string. Fixed via a new
  `CommonBuildKonfig.UT_CLICK_VERSION` (`util/build.gradle.kts`), sourced from
  `ubuntuTouchApp/manifest.json`'s `version` field at build time. **Not fully resolved** — the API
  still 400s even with a real version string now (`mobileVersion=0.1.67`); something else about
  the request isn't accepted server-side (worth checking whether `mobilePlatform=desktop` itself
  is an unrecognized value to that API). Low priority, doesn't block anything.
- **`LockerAppScreen` freeze** ("Add to watch" appeared to hang the app): `AppstoreService
  .isLoggedIn()` called `Firebase.auth.currentUser` unguarded, directly from composition. Firebase
  never initializes on this platform by design (dummy `google-services.json`), so the resulting
  `IllegalStateException` re-fired on every single recomposition frame instead of once — looked
  like a full freeze from the outside. Fixed with a targeted try/catch at that one call site.
  **Known, not fixed**: the identical unguarded-`Firebase.auth` pattern exists in several other
  screens (`BatterySettingsScreen`, `ContactDeveloperScreen`, `WatchSettingsScreen`,
  `BugReportScreen`, others) — any of them would freeze identically if visited on this platform.
  Worth a dedicated cleanup pass across all of them, not attempted this session (kept the fix
  scoped to the actual reported freeze).
- **New reliability gap found and mitigated (not root-caused)**: after ~30+ minutes of unattended
  reconnect churn, `bluetoothd` started refusing to even attempt the HCI-level connect
  (`le-connection-abort-by-local`, confirmed via `btmon` — no `LE Create Connection` ever issued).
  `sudo systemctl restart bluetooth` reliably unstuck it (confirmed live — the very next reconnect
  attempt after a restart connected cleanly). Full writeup and ruled-out theories (adapter-poll
  cascade, connection leak) are in the reliability doc's first 2026-08-18 addendum. If this
  recurs, the restart is a known-working immediate unblock — but the actual root cause (why
  `bluetoothd` gets into this state) is still open.

### Carried over from before, still relevant, still unverified

- **MPRIS music control** (0.1.62, `LinuxSystemMusicControl`) — the `ListNames`/`NameOwnerChanged`
  AppArmor gap was fixed and deployed two sessions ago, but never tested live end-to-end because
  the watch connection wasn't stable long enough until now. **This is the first session where the
  connection has actually been stable enough to test this** — do it early next session.
- **Device telemetry** (0.1.63–0.1.65) — `app.start` verified in ClickHouse previously; the
  error-forwarding and BLE connect-duration event paths are still unverified against a real
  connect+PPoG success. **Also now testable for the first time** — a full successful connect just
  happened live this session, worth checking ClickHouse for the `ble.connect.success` duration
  event that `DbusGattClient.jvm.kt`'s `connect()` wrapper should have fired.

## Rules that mattered this session — don't relearn them the hard way

- **GH Actions native ARM64 build workflow** (`.github/workflows/ubuntu-touch-build.yml`) — use
  this for every build:
  ```
  gh workflow run ubuntu-touch-build.yml --ref ubuntu-touch-poc
  gh run watch <run-id> --exit-status
  gh run download <run-id> --name coreapp-distributable --dir /tmp/coreapp-ci-artifact
  ```
  Then restage: `rm -rf ubuntuTouchApp/coreapp && cp -r /tmp/coreapp-ci-artifact
  ubuntuTouchApp/coreapp`, bump `ubuntuTouchApp/manifest.json`'s version, `cd ubuntuTouchApp &&
  clickable build --arch arm64 --accept-review-errors`.
- **Local Gradle builds on this workstation run under `qemu-aarch64` emulation regardless of
  architecture match** — even native-aarch64 JDK binaries get wrapped in it. This is why local
  verification builds are slow (a single-module incremental compile can take 10–50+ minutes) and
  why the GH Actions workflow above exists. If you need to verify a Kotlin change compiles before
  a full CI round-trip, run it backgrounded (`run_in_background: true`, redirect to a log file, no
  `timeout` wrapper piped through `tail` — that buffers all output until exit and looks like a
  silent hang) and be patient; check `ps aux | grep qemu-aarch64` for real CPU activity rather than
  assuming a stalled log means a stalled build. Use `JAVA_HOME=/home/tom/.jdks/jdk-21.0.12+8` +
  `PATH="/home/tom/.jdks/aarch64-tools:$PATH"` (matches this doc's own documented local-build
  recipe) — not the shell's default `java` (JDK 26, wrong target) and not JDK 17 (works, but no
  more "correct" than 21 and equally emulated either way).
- **`coreapp-apparmor-patch`'s version must bump alongside every `coreapp.thomasredstone` version**
  in `phone-fleet/manifest.yaml`, even when the patch's own content hasn't changed — the profile
  file is regenerated fresh (new filename) on every click install.
- **`pm sync`'s post-commit hook can time out** under a 2-minute tool timeout, especially with
  several payloads to reconcile. If cut off mid-flight: `git log` to confirm the commit landed (it
  does), then check `pm --ssh <ip> status` or just re-run the commit's sync. For a single stuck
  payload specifically, `pm --ssh <ip> apply <payload-id>` re-applies just that one directly,
  bypassing the whole sync loop — faster and confirms the exact gap. Used live this session when a
  full sync stalled with `coreapp-apparmor-patch` mid-application.
- **After every relaunch**: `export XDG_RUNTIME_DIR=/run/user/32011;
  export DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/32011/bus` before running
  `~/ut-notify-install/bridge-ctl allow coreapp.thomasredstone_coreapp_<version>` and
  `systemctl --user restart notification-bridged.service` over a raw SSH session (no session bus
  env by default) — easy to forget, silently breaks generic notification forwarding until done.
- **`lomiri-app-launch` printing "Started: ..." is not confirmation the app is running** — always
  follow up with `ps -o pid,etimes,stat,cmd -C coreapp` a few seconds later. The
  `terminate called ... Lost our connection with the registry` abort right after is itself
  harmless client-side noise, not a real crash signal — check the process table, not this message.
- **`btmon` needs root** (`sudo btmon -t | tee /tmp/btmon.log`) — no passwordless sudo over SSH,
  needs the user to run it themselves. Add `-t` for absolute timestamps if correlating against app
  logs precisely. Readable afterward without further sudo (owned by `phablet`, `tee` was the
  writer). This was the single most valuable diagnostic tool this session — both major root causes
  were found by reading a live capture, not by reasoning from code alone.
- **When AppArmor confinement isn't the answer**, check dmesg for `DENIED` lines first to rule it
  out fast, then go straight to a `btmon` capture rather than reasoning from source alone — both of
  this session's real findings needed the live capture to actually confirm (source reading alone
  produced two wrong theories before the captures corrected them: watch-side GATT caching, and
  general BlueZ-role speculation without the concrete `Attribute Not Found` evidence).

## The actual next step

1. **Test MPRIS music control and device telemetry end-to-end** — both have been blocked by watch
   connection instability across multiple sessions and now have a stable connection to test
   against for the first time. Quick wins if they already work; real bugs to find if they don't.
2. **Root-cause the `bluetoothd` connect-abort-after-churn issue** properly (see reliability doc
   addendum) if it recurs — the restart mitigation works but isn't a real fix.
3. **Dedicated cleanup pass on the unguarded `Firebase.auth` pattern** across
   `BatterySettingsScreen`, `ContactDeveloperScreen`, `WatchSettingsScreen`, `BugReportScreen`, and
   any others — same crash-on-composition risk as the `LockerAppScreen` freeze fixed this session,
   just not yet triggered by a user visiting those specific screens.
4. Minor: `cohorts.rebble.io` still 400s despite a real version string now — check whether
   `mobilePlatform=desktop` itself is unrecognized by that API.
