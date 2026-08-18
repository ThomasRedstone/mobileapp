# Handover prompt — paste this as the first message in a fresh session

I'm continuing Ubuntu Touch work on the Pebble/Core companion app (`coreapp.thomasredstone`).
This file is the immediate "what's next" briefing. For deep history read (in this order, as
needed): `docs/ubuntu-touch-reliability-review.md` (rev 3 + a 2026-08-18 addendum — the BLE
reliability fix series, its own audit, and tonight's new connect-abort-loop finding),
`docs/ubuntu-touch-phase6-handover.md` (deploy ritual, AppArmor patch mechanics, architecture),
`~/own/ut/ut-telemetry-broker.md` (the OTLP telemetry contract, if touching that again). Session
summary lives in the transcript if you need exact command history — this doc only carries what
matters for picking the work back up.

**Version currently deployed and running on-device: 0.1.66.** Watch is paired and reconnecting
(with caveats below), no app crashes.

## Where things actually stand

### The watch pairing dialog — fixed, root-caused, confirmed working

Previous session's blocker: pairing from the app showed the confirmation prompt on the watch but
**nothing on the phone**, `Device1.Pair()` failing after ~32s with `AuthenticationCanceled`.
Root-caused this session: `indicator-bluetooth`'s registered BlueZ agent silently never answers
`RequestConfirmation` — confirmed via log timing (32s ≈ BlueZ's default agent-reply timeout) and
via `journalctl` showing zero log lines from `indicator-bluetooth-service` during a live attempt.
**Fixed in 0.1.66**: `coreapp` now registers its own temporary `org.bluez.Agent1`
(`PairingAgent.jvm.kt`, wired into `Pairing.jvm.kt`'s `createBond()`) — auto-confirms only the
device path currently being paired, registered as the system default only for the duration of one
`Pair()` call, unregistered after. Exports at `/io/rebble/pebble/ppog/pairing_agent` specifically
to reuse the existing GATT-server AppArmor receive grant rather than needing a new one — **no
AppArmor patch changes were needed for this fix**, only the coreapp click itself moved.
**Live-verified working**: a real pairing completed with a phone-side confirmation (no
`bluetoothctl` workaround needed), and every reconnect since then reaches
`paired = true, encrypted = true` cleanly.

### Also fixed this session: firmware-update-check 400

`cohorts.rebble.io` was rejecting the `mobileVersion`/`pebbleAppVersion` query params
(`CommonBuildKonfig.GIT_HASH`, e.g. `"8e488bd-dirty"` — a git hash, not a version string) with an
HTTP 400. Android/iOS already send their real app version; desktop had no equivalent since there
are no git tags in this repo. Fixed by adding `CommonBuildKonfig.UT_CLICK_VERSION`
(`util/build.gradle.kts`), sourced from `ubuntuTouchApp/manifest.json`'s `version` field (the
click's actual single source of truth), and switching `desktopModule.kt`'s `CoreAppVersion` to use
it. This also fixes the same wrong-version-string problem for analytics, push telemetry, and
`AppUpdateTracker` — anything else that reads `CoreAppVersion`.

### New finding, NOT fixed: `bluetoothd` connect-abort after prolonged reconnect churn

Found live this session, after the pairing fix let a fresh pair complete cleanly. Once left
unattended reconnecting for ~35 minutes (~25+ consecutive failures against the same watch),
`Device1.Connect()` started throwing `le-connection-abort-by-local` almost immediately.
**Confirmed via a live `btmon` HCI capture**: `bluetoothd` never once issued `LE Create
Connection` for any of these attempts — it aborts before ever asking the radio to connect. Ruled
out (not assumed): the already-documented "`BluetoothState` poll failure → GATT server torn down"
gap (poll failures never reached the 2-consecutive threshold); a `DbusGattConnector` connection
leak (cleanup reliably calls `disconnect()` in every observed failure).

**Confirmed live mitigation**: `sudo systemctl restart bluetooth` immediately unstuck it — the
next reconnect attempt after the restart connected cleanly (real service discovery, MTU 256,
encrypted). Supports "`bluetoothd`-side resource/state exhaustion after N attempts" as the leading
theory over "something wrong with this specific device's BlueZ object." Not root-caused — full
writeup, ruled-out theories, and next diagnostic steps (annotated `btmon -t` capture; `bluetoothd`
verbose debug logging; check for a phantom connection state via `hcitool con`) are in
`ubuntu-touch-reliability-review.md`'s 2026-08-18 addendum.

That reconnect then hit the **pre-existing, already-documented forward-path PPoG timeout**
(rev 3's finding 5 — this watch is firmware v4.23.0, pre-v4.24, so it's forced onto the
phone-hosted-GATT-server forward path, which was already flagged as the fallback path needing
hardening). This is old, known, lower-priority work, not something from tonight.

### Everything else from the previous session (unchanged, still relevant)

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
- **`coreapp-apparmor-patch`'s version must bump alongside every `coreapp.thomasredstone` version**
  in `phone-fleet/manifest.yaml`, even when the patch's own content hasn't changed — the profile
  file is regenerated fresh (new filename) on every click install.
- **`pm sync`'s post-commit hook reliably times out** under a 2-minute tool timeout. If cut off
  mid-flight: `git log` to confirm the commit landed (it does), then either re-run
  `nohup ./sync.sh > logfile 2>&1 & disown` and poll the log, or — **faster and more targeted for
  a single stuck payload** — `pm apply <payload-id>` re-applies just that one payload directly
  without the whole sync loop. Used this live tonight when a full sync stalled mid-flight; confirm
  with `pm apply` rather than re-running the whole sync if only one payload is the actual gap.
- **After every relaunch**: `export XDG_RUNTIME_DIR=/run/user/32011;
  export DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/32011/bus` before running
  `~/ut-notify-install/bridge-ctl allow coreapp.thomasredstone_coreapp_<version>` and
  `systemctl --user restart notification-bridged.service` over a raw SSH session (no session bus
  env by default) — easy to forget, silently breaks generic notification forwarding until done.
- **`lomiri-app-launch` printing "Started: ..." is not confirmation the app is running** — always
  follow up with `ps -o pid,etimes,stat,cmd -C coreapp` a few seconds later.
- **`btmon` needs root** (`sudo btmon | tee /tmp/btmon.log`) — no passwordless sudo over SSH, needs
  the user to run it themselves. Readable afterward without further sudo (owned by `phablet`).
- **MPRIS music control** (0.1.62, `LinuxSystemMusicControl`) — the `ListNames`/`NameOwnerChanged`
  AppArmor gap from the previous session was fixed and deployed this session
  (`coreapp-apparmor-patch` bumped to include it), but **never actually tested live** — the watch
  connection wasn't stable long enough during this session either. Worth a real check once PPoG
  forward-path reconnects are more reliable.
- **Device telemetry** (0.1.63–0.1.65) — `app.start` verified in ClickHouse previously; error/
  duration event paths still unverified against a real failure or a full successful connect+PPoG
  handshake (still hasn't happened this session either, due to the forward-path PPoG issue).

## The actual next step

1. **Forward-path PPoG hardening** (rev 3's finding 5, already scoped in the original review) is
   now the main blocker to a fully working watch connection — pairing and the base BLE link both
   work reliably now; it's specifically the PPoG handshake over the phone-hosted GATT server that
   doesn't complete. Worth the investment now that pairing is a solved problem and won't confound
   the diagnosis.
2. If the connect-abort-loop recurs, `sudo systemctl restart bluetooth` is a known-working
   immediate unblock — but chase the real root cause per the addendum's next-steps list rather than
   treating the restart as a permanent fix.
3. Once a connection holds through a full PPoG handshake, that's the first real chance to verify
   MPRIS music control and telemetry's error/duration event paths end-to-end.
