# Handover prompt — paste this as the first message in a fresh session

I'm continuing Ubuntu Touch work on the Pebble/Core companion app (`coreapp.thomasredstone`).
This file is the immediate "what's next" briefing. For deep history read (in this order, as
needed): `docs/ubuntu-touch-reliability-review.md` (rev 3 — the BLE reliability fix series and
its own audit of itself), `docs/ubuntu-touch-phase6-handover.md` (deploy ritual, AppArmor patch
mechanics, architecture), `~/own/ut/ut-telemetry-broker.md` (the OTLP telemetry contract, if
touching that again). Session summary lives in the transcript if you need exact command history —
this doc only carries what matters for picking the work back up.

**Version currently deployed and running on-device: 0.1.65.** Stable, no crashes, clean startup.

## Where things actually stand

### The watch connection — root-caused, not fixed, needs a human

The watch (`Pebble Time 2 / Obelix PVT`, MAC `DF:07:0A:D4:70:B8`, serial `C1131411010W`, firmware
`v4.23.0`) has been stuck in a `FailedToConnect` retry loop for most of this session, having
connected successfully exactly once (after a from-scratch forget+re-pair with a human present).
**Confirmed via a live `btmon` HCI capture** (captured this session, not guessed): the BLE link
connects fine, then the encryption handshake fails outright —
```
Reason: Authentication Failure (0x05)
Reason: Connection terminated due to authentication failure (0x04)
```
This is a genuine LTK/bond-key mismatch between what the phone has stored and what the watch has
stored — **not** an app bug, confirmed by reproducing the identical failure with a completely raw
`bluetoothctl connect`, no app involved at all. `bluetoothctl info DF:07:0A:D4:70:B8` still shows
`Paired: yes` / `Bonded: yes` on the phone throughout — the phone's own bond record was never
touched (no `RemoveDevice`/unpair call was made after the last successful pairing). Leading
theory: the sheer volume of connect/disconnect churn since that pairing (≈8 app-restart deploy
cycles, plus several manual `bluetoothctl connect`/`disconnect` probes run for diagnostics) is
suspected to have caused the watch's own NimBLE bond store to evict or corrupt its side —
unconfirmed, since there's no way to inspect the watch's own flash bond store remotely.

**The only real fix**: forget the pairing on both sides again and redo it fresh, human present to
confirm both the watch-face prompt and the phone's own pairing dialog — same ritual as before.
No retry-logic/backoff change in the app can fix a genuine key mismatch; every attempt fails
identically forever until re-paired. **Do this first**, before any of the items below, since
nothing else in this session can be live-verified against a watch that won't stay connected long
enough to run the endpoint managers.

One live loose end on this: partway through, the user noted the watch shows up under the phone's
Settings app "**Available devices**" section rather than "Paired devices" (checked: these are
genuinely separate model lists in `PageComponent.qml`, not just a display quirk) — and separately
raised that "Available" may just mean "currently in pairing/discoverable mode," which would apply
to *any* device (known or not) that happens to be discoverable/advertising right now, not
specifically evidence of an unpaired state. This wasn't resolved before the session ended — worth
a quick look if it's a fast check, but don't let it block the re-pair above; the `btmon` capture is
the solid evidence, this Settings-app detail is a secondary curiosity at best.

### Everything else shipped this session (all deployed, all except telemetry's `app.start`
### unverified against a live watch connection — see "needs verification" below)

- **0.1.51–0.1.60**: the full reliability-review fix series (MTU handling, lifecycle exemption,
  StartNotify propagation, re-pair path, forward-path GATT server hardening, connection params,
  disconnect detection, PPoG retransmit timing, hardcoded adapter path, then a rev-3 self-audit
  that found and fixed 4 new bugs in the series itself). Doc: `ubuntu-touch-reliability-review.md`.
- **New GH Actions workflow** (`.github/workflows/ubuntu-touch-build.yml`) — builds the ARM64
  distributable on a **native** `ubuntu-24.04-arm` GitHub runner instead of local qemu
  cross-compilation. Minutes instead of 20–45+, and sidesteps this machine's recurring
  wedged-daemon/memory-pressure flakiness entirely. **Use this for every future build**:
  ```
  gh workflow run ubuntu-touch-build.yml --ref ubuntu-touch-poc
  gh run watch <run-id> --exit-status
  gh run download <run-id> --name coreapp-distributable --dir /tmp/coreapp-ci-artifact
  ```
  Then the normal `clickable build --arch arm64 --accept-review-errors` restage from that
  artifact. Note: workflow-dispatch workflows must exist **on the default branch (`master`)** to
  be dispatchable at all, even when run with `--ref ubuntu-touch-poc` — already handled (pushed a
  registration-only copy to `master` via a throwaway `git worktree`), don't need to repeat that,
  just keep the two copies in sync if the workflow file changes again.
- **0.1.61**: locker apps (watchfaces/apps) now prefetch to disk as soon as added to the locker,
  instead of only downloading reactively when the watch requests them over BLE — installing
  something already in the library no longer needs a live connection at that moment.
  `cleanupCache()` no longer evicts owned apps past a size budget, only genuinely orphaned entries.
- **0.1.62**: real MPRIS-backed music control (`LinuxSystemMusicControl`,
  `libpebble3/jvmMain/.../telemetry` sibling package `.../di/MprisDbus.jvm.kt` +
  `LinuxPlatformServices.kt`) — was a pure no-op before. Discovers whichever
  `org.mpris.MediaPlayer2.*` player is live (prefers `org.mpris.MediaPlayer2.sonicplayer` — Sonic
  Player, this fleet's own music app — falls back to any other found, e.g. `media-hub-server`'s
  own `org.mpris.MediaPlayer2.MediaHub`). **Known unfixed gap**: confirmed live via dmesg audit
  log this session — `org.freedesktop.DBus.ListNames` (how player discovery finds Sonic Player's
  bus name at all) is AppArmor-**DENIED**:
  ```
  apparmor="DENIED" operation="dbus_method_call" path="/org/freedesktop/DBus"
  interface="org.freedesktop.DBus" member="ListNames" mask="send"
  label="coreapp.thomasredstone_coreapp_0.1.62" peer_label="unconfined"
  ```
  This was found late, while investigating an unrelated app-not-running gap, and **no
  supplementary AppArmor rule was ever added for it** — the MPRIS `send`/`receive` rule that *was*
  added (`coreapp-apparmor-patch/install.sh`, scoped to
  `peer=(name=org.mpris.MediaPlayer2.sonicplayer)` on `/org/mpris/MediaPlayer2`) only covers
  talking to the player once found; the bus-driver `ListNames` call needed to find it in the first
  place is still blocked. Needs a rule like:
  ```
  dbus (send)
      bus=session
      path=/org/freedesktop/DBus
      interface=org.freedesktop.DBus
      member=ListNames
      peer=(name=org.freedesktop.DBus,label=unconfined),
  ```
  plausibly also needed for `NameOwnerChanged` (the signal `LinuxSystemMusicControl` subscribes to
  for live player-appear/disappear detection) — **add both, redeploy, and re-check dmesg for
  further DENIED lines** before considering MPRIS actually working. This is on top of the watch
  connection being down — MPRIS itself has never had a chance to prove out end-to-end yet.
- **0.1.63–0.1.65**: device telemetry via the phone's local OTLP broker
  (`libpebble3/jvmMain/.../telemetry/DeviceTelemetry.kt`) — `app.start` (verified end-to-end,
  the record landed in ClickHouse for real, confirmed via the query in
  `~/own/ut/ut-telemetry-broker.md`), every `UserFacingError` forwarded via the existing
  `ErrorTracker`, uncaught-exception hooks, and BLE connect-handshake `duration.ms`. Two real
  startup-crash bugs found and fixed in the process (both instructive if touching this file
  again): (1) `ErrorTracker` only lives inside `LibPebble3.create()`'s own **separate internal
  Koin instance** — not reachable from the app-level `koinApp` `Main.kt` builds; route through
  `LibPebble` instead (it implements `Errors` and *is* exposed at the top level, via
  `watchModule`'s binding). (2) `java.net.http.HttpClient` isn't in this app's jlink-trimmed
  runtime image (`composeApp/build.gradle.kts`'s `nativeDistributions.modules(...)` doesn't
  include `java.net.http`) — `NoClassDefFoundError` at launch. Used `java.net.HttpURLConnection`
  (`java.base`, always present) instead. **Only `app.start` has been verified with a real
  ClickHouse record** — the error-forwarding and BLE-duration paths compile and are wired, but
  never fired for real (no errors occurred; the watch never held a connection long enough to
  finish a handshake either way). Worth a real check once the watch is reconnecting again.
- **Explicitly not done, deferred by the user's own choice**: the Android volume-notification
  spam (`AudioManager.adjustVolume(..., FLAG_SHOW_UI)` popping a system toast per step) —
  Android-only, scoped as a separate small follow-up, never started this session.

## Rules that mattered this session — don't relearn them the hard way

- **Use the GH Actions workflow for builds now**, not local qemu cross-compilation — see above.
  Local builds still work as a fallback if GH Actions is unavailable, but expect the
  wedged-daemon/OOM flakiness documented at length earlier in this session's history if you do.
- **`pkill -f` matches its own invoking command line** — a remote command like
  `ssh host "pkill -f 'coreapp/bin/coreapp'; ..."` kills the SSH session running it too, since the
  full command string is visible in `ps` output and matches the pattern. Use `pkill -x coreapp`
  (exact process-name match) instead.
- **The `phone-fleet` sync's post-commit hook reliably times out** under a 2-minute default
  tool timeout, especially when the phone's Tailscale connectivity is flaky (which it was,
  repeatedly, this session — outages of several minutes each). If a commit's sync output is cut
  off mid-flight, check `git log` to confirm the commit landed (it does — the hook failing is a
  sync-step problem, not a commit problem), then re-run `nohup ./sync.sh > logfile 2>&1 & disown`
  and poll the log for `converged; manifest recorded as applied` vs a `FAIL ... context deadline
  exceeded` / `EOF` (means connectivity dropped mid-sync, safe to just retry once it's back).
- **Every `coreapp.thomasredstone` version bump needs `coreapp-apparmor-patch`'s version bumped in
  lockstep** in `phone-fleet/manifest.yaml`, even when the patch's *content* hasn't changed — the
  AppArmor profile file itself is regenerated fresh (new filename) on every click install, so the
  payload must re-apply against the new file every time or the grants silently don't exist.
- **After every relaunch, re-run**: `bridge-ctl allow coreapp.thomasredstone_coreapp_<version>`
  and restart `notification-bridged.service` — easy to forget, silently breaks generic
  notification forwarding until done.
- **`lomiri-app-launch` printing "Started: ..." is not confirmation the app is actually running**
  — it can crash immediately after (seen repeatedly this session, both from real bugs and from a
  harmless `lomiri-app-launch` client-side "Lost our connection with the registry" abort that
  doesn't reflect the app's own state). Always follow up with a `ps -o pid,etimes,stat,cmd -C
  coreapp` a few seconds later, and check `journalctl --user -n N` for exceptions if the PID is
  missing.
- **Kernel/AppArmor audit lines** (`dmesg | grep DENIED`) are the ground truth for confinement
  problems — they showed up for the MPRIS `ListNames` gap above and would have shown up for the
  earlier GATT-server work too. Check them **before** assuming a D-Bus call's own thrown exception
  tells the whole story (a fire-and-forget signal send has no exception path at all, and even a
  method-call denial's Kotlin-side exception message doesn't always make clear it was AppArmor,
  not the peer, that refused it).
- **`btmon` needs root** (`sudo btmon | tee /tmp/btmon.log`) — no passwordless sudo over this SSH
  setup, so this needs the user to run it themselves in their own terminal on-device (or provide a
  password). Once captured, the log file was readable afterward without further sudo (owned by
  `phablet`, not root, since `tee` was the writer).

## The actual next step

1. Get a human physically present with both devices. Forget the watch's pairing on the watch's
   own settings; `bluetoothctl remove DF:07:0A:D4:70:B8` on the phone (**not yet done** — checked
   at session end, the phone's bond is still present: `Paired: yes`, `Bonded: yes`, `Connected:
   no`. Nothing was removed this session; that's the actual next action, not something to just
   verify). Re-pair fresh, confirming both prompts.
2. Once a stable connection holds for more than a few minutes, that's the first real chance to
   verify: MPRIS music control (after adding the `ListNames`/`NameOwnerChanged` AppArmor rules
   above — do that *before* testing, or expect it to silently do nothing), the offline locker
   prefetch (add something to the locker, confirm it's cached before ever asking the watch for
   it), and telemetry's error/duration event paths (trigger a deliberate failure or watch a real
   connect succeed, then check ClickHouse).
3. If the connection breaks again after a deploy-restart cycle, that's expected per the churn
   theory above — but if it happens **without** any restart, on a connection that was genuinely
   stable, that would be new information worth a fresh `btmon` capture to see if it's the same
   `Authentication Failure` or something different.
