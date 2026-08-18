# Ubuntu Touch port: reliability review against the actual firmware (2026-08-16, rev 3)

> **Rev 3 status:** the fix series 0.1.51–0.1.59 (`00990d36..a9be35f4`) has been re-reviewed
> commit by commit — see "Rev 3: verification of the fix series" at the end. Verdict: all major
> findings correctly implemented; four new, smaller issues found in the fixes themselves, one
> process concern (a common-code timing change that affects Android/iOS), and two known gaps
> deliberately left open. The findings below are kept as originally written for reference.

Scope: the current shipped shape (single confined Click, Compose Desktop JVM app,
`dbus-java`→BlueZ transport in `libpebble3/src/jvmMain`), reviewed against firmware sources.
Rev 2: re-reviewed against **`~/own/PebbleOS`** (coredevices, `main` @ 2026-08-14, tags through
v4.29.0) — the production firmware for the actual watch — plus `~/own/hardware` and
`~/own/pebble-tool`. Rev 1 had only `~/own/pebble-firmware` (the Rebble fork, stale @ 2026-06-05,
pre-reversed-PPoG). Findings ranked by likelihood of causing the reliability problems seen now.

## Which firmware, which transport — this decides which failures are even possible

- **`~/own/PebbleOS` is the production tree for the Pebble Time 2 / Obelix** (SF32LB52 SoC per
  the hardware repo's block diagram — BLE-only, no BT Classic, so `supportsBtClassic=false` is
  correct). It contains reversed PPoG V2 (`src/bluetooth-fw/nimble/ppog_reversed_service.c`,
  commit `164be9e4d`, shipped v4.24.0; tags now reach v4.29.0). The Rebble fork does **not** —
  it's ~2.5 months behind and forward-PPoG-only. Don't use it as the reference for this watch.
- **Against production firmware (≥v4.24) the app takes the reversed-V2 path**: the watch hosts
  service `0x40000000` (notify `0x40000001`, write-no-response `0x40000003` — exactly matching
  the app's `PPOGATT_WATCH_SERVER_V2_*` UUIDs), the handshake is **phone-initiated**
  (`ppogatt.c: StateConnectedClosedAwaitingResetRequest` ↔ the app's `initWithResetRequest()`),
  and the JVM `defaultBleConfig()` flips `useReversedPpogV2`/`legacyReversedPPoG` on
  (`watchModule.jvm.kt:18-19`). The phone is a plain BlueZ GATT client here — no local GATT
  server, no AppArmor receive rule needed.
- **The forward path (phone-hosted GATT server) is now the fallback**, hit when the watch runs
  pre-v4.24 firmware, for older watches, or if reversed setup throws. The reversed service is
  registered unconditionally in the NimBLE driver init (`init.c:151`), so PRF/recovery on Obelix
  should expose it too — i.e. even firmware-update reboots shouldn't force the forward path on
  this watch (worth confirming once in logs during an update).
- **First triage step for any failure: log/record which path the session used.** The two paths
  have almost disjoint failure modes. `PpogPacketSenderProxy.isReversed` is the ground truth.

Firmware constants defining the phone's real-time obligations — **identical in PebbleOS and the
Rebble fork** (`ppogatt_internal.h`, `include/bluetooth/mtu.h`):

- ATT MTU: watch negotiates 256 (`ATT_MAX_SUPPORTED_MTU`, NimBLE `BLE_ATT_PREFERRED_MTU (256)`)
- Ack timeout 5–6 s; 2 timeouts → session reset; 5 failed resets → BLE disconnect
  (`PPOGATT_TIMEOUT_*`, `PPOGATT_RESET_COUNT_MAX`)
- Watch acks phone data within ≤200 ms (`PPOGATT_MAX_DATA_ACK_LATENCY_MS`)

Any phone-side stall >~12 s escalates to a watch-initiated reset, and `PPoG.kt:294-302` treats
an in-session reset as fatal → full teardown + reconnect (~22 s+ against a watch that doesn't
advertise to general discovery when disconnected).

---

## 1. MTU handling is broken twice over (HIGH — both paths; intermittent connect crash + 13× throughput loss)

`DbusConnectedGattClient.requestMtu(mtu)` **echoes back the requested value as if negotiation
succeeded** (`DbusGattClient.jvm.kt:336`), while `getMtu()` returns the hardcoded floor of 23
(`:338`). `Mtu.update()` (`Mtu.kt:21-32`) runs with `useNativeMtu = true` on Linux (never
overridden in `LibPebbleModule.jvm.kt:98`), so during every connect the MTU StateFlow transitions
**23 → 339 → 23**.

Two consequences:

- **Race-dependent crash at connection setup.** `PebbleBle.connect()` launches
  `mtuParam.mtu.collect { ppog.updateMtu(it) }` *before* calling `mtuParam.update()`
  (`PebbleBle.kt:115-121`), and `PPoG.updateMtu()` **throws** on any decrease
  (`PPoG.kt:333` — `"Can't reduce MTU"`). Whenever the collector observes the transient 339, the
  subsequent 23 throws inside the connection scope — an intermittent, timing-dependent
  connection failure with no obvious cause in the logs.
- **When it survives, phone→watch runs at MTU 23 → 19-byte PPoG payloads.** The real link MTU is
  256 (watch's preferred; BlueZ auto-negotiates its own 517 at connect, min wins). The firmware
  already sizes *its* packets from the negotiated MTU (`prv_get_max_payload_size()` — both
  roles), so watch→phone data arrives in 252-byte chunks while phone→watch dribbles out in
  19-byte chunks, each one a D-Bus round trip, each individually acked (no coalescing,
  `PPoG.kt:286`). App installs, notification bursts and BlobDB syncs are ~13× slower than the
  link allows, stretching the wall-clock exposure of every other failure mode below.

**Fix:**
1. Immediate: set `useNativeMtu = false` in the Linux `BlePlatformConfig`, and make
   `requestMtu()` return `getMtu()` instead of echoing the request — removes the 339→23 crash.
2. Real fix: report the true MTU. BlueZ ≥5.62 exposes an `MTU` property on
   `org.bluez.GattCharacteristic1` (UT 24.04 ships well past that) — read it in `getMtu()`
   after `ServicesResolved`. That fixes the primary (reversed/client) path with a property read;
   the forward/server path gets it via `AcquireNotify`/`AcquireWrite` (finding 5).

## 2. Lomiri suspends the app in background; the firmware gives it ~12 s (HIGH — the day-to-day disconnect driver, both paths)

Backgrounded confined Clicks are SIGSTOPped — already observed live (`coreapp` in `ps` state `T`,
`ubuntu-touch-handover-prompt.md:94-95`), and the PoC plan's original reason for a split daemon
(`ubuntu-touch-poc-plan.md:31-32`). The shipped single-Click shape puts the BLE session inside
the UI process with **no lifecycle exemption anywhere in the package** — so the moment the user
switches apps: JVM frozen → acks stop → watch times out (~12 s), resets (×5), disconnects. On
refocus the app thaws into a dead session with stale D-Bus state and starts a full reconnect —
experienced as "disconnected every time I open the app", plus no notification forwarding while
backgrounded. The transport role changes nothing here; the timeout state machine
(`prv_enter_awaiting_reset_complete` etc.) is shared by both roles.

**Fix:**
- Now: exempt the app from lifecycle suspension —
  `gsettings set com.lomiri.qtmir lifecycle-exempt-appids "['coreapp.thomasredstone_coreapp']"`
  (verify exact schema/appid form on-device; some UT builds still use the legacy
  `com.canonical.qtmir` schema). Same class of on-device tweak as the AppArmor patch; battery
  cost is real (JVM stays scheduled).
- Properly: the already-designed split (background service owning the BLE session), per
  `ubuntu-touch-architectural-paths.md`.

## 3. On the primary (reversed) path, a failed `StartNotify` is reported as success (HIGH — new in rev 2)

`PpogClient.init()` goes to real lengths to not return until the CCCD write has landed
(`PpogClient.kt` — the `cccdWritten` deferred, precisely to avoid the phone's first
`ResetRequest` racing ahead of the subscribe). The BlueZ transport defeats all of it:
`subscribeToCharacteristic()` wraps `StartNotify()` in `runCatching { }.onFailure { log }` and
then **invokes `onSubscription` unconditionally** (`DbusGattClient.jvm.kt:272-277`). So when the
CCCD write fails — encryption not yet elevated, a stale bond after a watch factory reset,
bluetoothd hiccup — the app believes it's subscribed and fires `ResetRequest` into the void.

Firmware side, that's guaranteed dead air: the reversed client is only created on a real
subscribe event over an encrypted link (`ppogatt_reversed_handle_subscribed` — "connection not
encrypted; ignoring"; CCCD is `READ_ENC`-gated in `ppog_reversed_service.c:69`), and writes with
no client are dropped. Result: a generic 12 s `TimeoutInitializingPpog`, teardown, reconnect,
repeat — with the actual cause (`StartNotify failed: ...`) visible only as one earlier log line.

**Fix:** propagate the failure — on `StartNotify` error, don't invoke `onSubscription`; fail the
flow (`close(e)`) so `PpogClient`'s `cccdWritten.completeExceptionally` path fires. But note the
interplay: `PebbleBle` reacts to a `configureReversed` throw by **falling back to forward PPoG**
(`PebbleBle.kt:185-206`) — correct on iOS (stale GATT cache), wrong on UT where forward is the
fragile path and the watch demonstrably hosts the service. On Linux the better reaction is
retry-reversed (or fail the connect and let the normal retry loop re-run it).

## 4. The re-pair path can't actually re-pair on BlueZ (MEDIUM→HIGH on the reversed path)

When the watch reports paired but the link won't encrypt within the 5 s
`ENCRYPTION_RESTORE_GRACE` (`PebbleBle.kt:147-157`), the app decides to re-pair → `createBond()`
→ `Device1.Pair()` — which on an already-`Paired` BlueZ device throws `AlreadyExists`, gets
logged on the fire-and-forget pairing thread (`Pairing.jvm.kt`), and the flow then times out
after 60 s. On the reversed path this matters doubly, because encryption is a hard prerequisite
for the PPoG subscribe (finding 3) — a stale/mismatched bond (e.g. watch factory-reset) becomes
a permanent connect-fail loop that only `bluetoothctl remove` breaks.

**Fix:** on BlueZ, re-pair = `Adapter1.RemoveDevice()` first, then `Pair()`. Cheap to add to
`createBond()` when `Paired == true` already.

## 5. Forward-path (fallback) hazards — real, but now secondary (was HIGH, now MEDIUM for this watch)

These only bite when a session lands on forward PPoG: pre-v4.24 firmware, older watches, the
reversed flags off, or finding 3's fallback misfiring. They were rev 1's headline; on production
Obelix firmware they're the fallback path — still worth fixing since fallback is exactly when
you least want silent breakage:

- **Non-self-healing D-Bus connection under the GATT server** (`GattServer.jvm.kt:172-267`):
  when it dies (documented happening live, twice, in `BluezDbus.jvm.kt`'s own comment), BlueZ
  unregisters the whole GATT application; nothing re-registers it; every reconnect fails until
  app restart. Fix: `IDisconnectCallback` → rebuild + re-`RegisterApplication`, and return
  `SendResult.RestartRequired`. Better: move the data characteristic to
  `AcquireNotify`/`AcquireWrite` fds — real MTU, kernel backpressure, no notify-before-subscribe
  drop window, no per-packet D-Bus signal.
- **Notifies sent regardless of subscription state** (`GattServer.jvm.kt:315` logs "will likely
  drop this notify silently" — and sends anyway): lost `ResetComplete`/acks during session
  establishment → watch-side 5–6 s timeout storms.
- **The out-of-band AppArmor receive rule** (`dbus (receive) path="/{,io/rebble/pebble/ppog/**}"`)
  silently reverts on every install, and its absence is absorbed: failed `RegisterApplication`
  still lets `registerDevice()` return true (`GattServer.jvm.kt:269-272`,
  `GattServer.kt:79-95`) — symptom is a generic 12 s PPoG-init timeout. Also: the package rename
  `coreapp.tomredstone` → `coreapp.thomasredstone` changed the profile path the documented patch
  loop targets. Fix: fail loudly with `ConnectionFailureReason.RegisterGattServer` + an explicit
  "AppArmor rule missing?" log.

## 6. The watch is told "the phone manages connection parameters" — and then nobody does (MEDIUM, both paths)

`ConnectionParams.subscribeAndConfigure()` writes `[0x00, 0x01]` (`ConnectionParams.kt:24`) =
`SetRemoteParamMgmtSettings` with `is_remote_device_managing_connection_parameters = true`.
PebbleOS honours it the same way the old tree did (`pebble_pairing_service.c:37-40`,
`gap_le_connect_params.c:189` — the watch then never requests a param change; the NimBLE
`responsiveness.c` machinery sits idle). On Android the phone then actually manages priority; on
Linux nothing ever writes `SetRemoteDesiredState` (zero call sites) and BlueZ has no
`requestConnectionPriority`. The link stays at whatever the kernel picked: no fast mode for bulk
transfers (compounding finding 1), no low-power idle mode (watch battery).

**Fix:** on Linux write `[0x00, 0x00]` (or skip the write) — the watch's own ResponseTime state
machine takes over and issues standard L2CAP param-update requests, which the BlueZ central
honours by default. One-line, platform-flagged.

## 7. Disconnect detection holes + mislabelled reasons (MEDIUM)

- Only `PropertiesChanged Connected=false` on the device object is watched
  (`DbusGattClient.jvm.kt:87-98`); no `InterfacesRemoved` handling (adapter power cycle,
  `RemoveDevice`, bluetoothd restart) → zombie session until the next write fails.
- `BluetoothState.jvm.kt` polls `Powered` every 3 s; 2 consecutive D-Bus failures → `Disabled` →
  (default `closeGattServerWhenBtDisabled = true`) tears down the GATT server mid-session.
  Subscribe to the adapter's `PropertiesChanged` instead of polling.
- Every `_disconnected` completion says `FailedToConnect`, even for a clean mid-session drop —
  reason-sensitive retry logic and the logs can't tell "never connected" from "dropped after an
  hour".

## 8. PPoG recovery timing is tuned for lossless transports (LOW–MEDIUM)

Phone-side retransmit fires after 10 s (`PPoG.kt: RESET_REQUEST_TIMEOUT`) — after the watch's
5–6 s timeout, so the watch always wins the race, and its recovery move is either a duplicate
ack (handled) or an in-session reset (fatal, `PPoG.kt:294-302`). On the reversed path with
findings 1+3 fixed, packet loss should be rare (BlueZ client writes/notifies are reliable ATT);
this mostly matters on the forward path's lossy notify sends. If flakiness remains after the
fixes above: drop the retransmit below 5 s and consider handling an in-session `ResetRequest` by
re-running the handshake in place, as the firmware does.

## 9. Smaller, real, worth a line each

- **Address pinning is a non-issue on this watch** (downgraded from rev 1): NimBLE's
  `bt_driver_set_local_address()` is a **no-op** (`id.c:26`) — Obelix never cycles its address,
  so the app's MAC-keyed BlueZ device paths are safe. The pinning-trigger flags still matter for
  older Dialog-based watches only; on NimBLE the trigger write only controls security-request
  behaviour (`prv_access_trigger_pairing`).
- **Pairing-state flows never terminate**: `getBluetoothDevicePairEvents` polls `Paired` every
  1 s in an infinite loop per attempt (`Pairing.jvm.kt:84-105`) — collector leak.
- **Scanner one-shot semantics**: `StartDiscovery` failure (e.g. `InProgress`) silently aborts
  the scan flow; retry on `InProgress`. (Correctly moot for reconnects: Obelix doesn't advertise
  to general discovery when disconnected — `advertisesWhenNotConnected()` and the plan's 36 s
  empirical test agree.)
- **`/org/bluez/hci0` hardcoded** in five files; resolve once via `ObjectManager`.

## Suggested order of attack (updated)

1. `useNativeMtu = false` on Linux + `requestMtu()` returns `getMtu()` — removes the
   intermittent setup crash (finding 1a). One-liner.
2. Propagate `StartNotify` failure in `subscribeToCharacteristic` and make the Linux fallback
   retry-reversed instead of forward (finding 3) — de-flakes the *primary* path's setup.
3. Lifecycle exemption via gsettings (finding 2) — the biggest day-to-day win.
4. `[0x00, 0x00]` conn-param write on Linux (finding 6) — one-liner, battery + latency.
5. Read the real MTU from BlueZ's `GattCharacteristic1.MTU` property (finding 1b) — ~13×
   phone→watch throughput on the reversed path for a property read.
6. `RemoveDevice`-then-`Pair` re-pair (finding 4); adapter/device signal subscriptions instead
   of polling + distinct disconnect reasons (finding 7).
7. Forward-path hardening (finding 5) — when there's time; it's the fallback now, but a silent
   fallback that doesn't work is worse than none.

## Testing levers now available

- `~/own/pebble-tool` speaks the developer connection (WebSocket) that
  `DevConnectionTransport.jvm.kt` already implements — `pebble install --phone <ip>` /
  `pebble logs` give a repeatable way to drive sustained PPoG traffic (app installs are the
  worst-case bulk transfer) for before/after throughput and soak testing of the fixes above,
  without touching the UI.
- `PpogPacketSenderProxy.isReversed` + the negotiated MTU are the two facts worth printing at
  the top of every connection log line during this phase.

Longer term, the split-service architecture remains the right production shape — but nothing
above is blocked on it, and findings 1, 3, 4, 6 carry over into a daemon unchanged if left
unfixed.

---

# Rev 3: verification of the fix series (0.1.51–0.1.59, `00990d36..a9be35f4`)

Every commit in the series was diffed and checked against both the original findings and the
firmware. Summary first, details after.

## Verified correct

| Finding | Fix | Status |
|---|---|---|
| 1a MTU race/crash | `useNativeMtu=false` on Linux; `requestMtu()` returns `getMtu()` | ✅ |
| 1b real MTU | `getMtu()` reads BlueZ `GattCharacteristic1.MTU` property, fallback 23 | ✅ feeds PPoG 252-byte payloads on both paths |
| 2 lifecycle exemption | Applied **on-device** via `com.canonical.qtmir lifecycle-exempt-appids` (per 0.1.59 commit body; correctly not in the repo) | ✅ but see gaps |
| 3 StartNotify lie | Failure now `close(e)`s the flow → `PpogClient.cccdWritten` fails properly | ✅ |
| 3b fallback policy | New `fallbackToForwardPpogOnReversedSetupFailure=false` on Linux → fail connect, retry reversed | ✅ |
| 4 re-pair no-op | `createBond()`: if already `Paired` → `RemoveDevice` → rediscover → `Pair()` | ✅ with a turbulence caveat below |
| 5 server self-heal | `NotConnected` in `sendData` → `RestartRequired` → manager close/rebuild; `closeServer()` hardened | ✅ |
| 5b register fail-loud | `addServices(): Boolean`, `registerDevice` propagates, explicit AppArmor hint log | ✅ |
| 5c notify gate | `sendData` awaits real `StartNotify` (4 s bound) instead of firing into the void | ✅ with a one-shot flaw below |
| 6 conn params | `phoneManagesConnectionParams=false` on Linux → writes `[0x00,0x00]` | ✅ (no-op-safe: firmware default is already false) |
| 7 disconnect holes | `InterfacesRemoved` handler; `Disconnected` vs `FailedToConnect` reasons; adapter signal+poll hybrid for `BluetoothState` | ✅ |
| 8 retransmit timing | `RESET_REQUEST_TIMEOUT` 10 s → 4 s (under firmware's 5–6 s) | ✅ direction, ⚠ scope — see below |
| 9 hci0 hardcode | `resolveAdapterPath()` everywhere, cached where hot | ✅ |

Also checked: the backoff-counter change in `WatchManager.updateFailureReason` (counts all
consecutive failures regardless of reason) is sound — the reset-on-success path still fires on
every connect because `newProps` always differs via its `lastConnected` timestamp.

## New issues found in the fixes

1. **`awaitNotifying()` is one-shot** (`GattServer.jvm.kt`): `firstSubscribe` is a
   `CompletableDeferred` completed on the *first ever* `StartNotify`. The `ExportedCharacteristic`
   objects live as long as the GATT server (services are intentionally never removed), so after
   any unsubscribe/resubscribe cycle — i.e. every reconnect — the gate is already-completed and
   `sendData` degrades back to the old silent-drop behaviour during the re-subscribe window.
   Fix: replace with a `MutableStateFlow(false)` toggled by StartNotify/StopNotify and
   `first { it }` under the same 4 s timeout.
2. **`RemoveDevice` runs against a live connection** (`Pairing.jvm.kt`): the re-pair branch fires
   while `PebblePairing` is mid-connection. `Adapter1.RemoveDevice` force-disconnects the device
   and deletes the object → the new `InterfacesRemoved` handler tears the session down while the
   daemon pairing thread carries on independently, and `Pair()` then connects on its own, outside
   the app's connection management, racing the reconnect loop (expect BlueZ `InProgress` noise
   for an attempt or two before it converges). It should converge — the watch ends up bonded and
   a later retry finds `Paired=true` — but if re-pair proves flaky in practice, the cleaner shape
   is: mark the device "needs fresh pair", disconnect deliberately, do RemoveDevice+Pair *before*
   the next connect attempt rather than during one.
3. **Signal-handler leak on the StartNotify failure path** (`DbusGattClient.jvm.kt`): the early
   `close(e); return@callbackFlow` skips `awaitClose`, so the `PropertiesChanged` handler added
   just above never gets removed. Bounded (the per-connection `DBusConnection` is dropped on
   disconnect) but repeated failed subscribes on one connection accumulate handlers + server-side
   match rules.
4. **`BluetoothState` shared-state races**: `currentState()`/`checkAndEmit()` now run from both
   the dbus-java signal-dispatch thread and the poll coroutine, mutating `connection`/`props`/
   `last`/`consecutiveFailures` unsynchronized. Worst case is a stale read or a doubled rebuild,
   not a crash — but a small `synchronized` block would close it.

## Process concern

**The 10 s → 4 s retransmit change is common code** — it changes PPoG timing for the shipping
Android and iOS apps, not just this port. 4 s is protocol-safe (firmware re-acks duplicate SNs)
and better matched to the firmware's 5–6 s window, but it deserves either a
`BlePlatformConfig` knob (4 s Linux, 10 s mobile until soak-tested there) or an explicit
sign-off that mobile regression channels will watch for retransmit-rate changes.

## Known gaps, deliberately open

- **RegisterApplication is attempted exactly once per GattServer instance** (`initServer()`).
  If that one attempt fails (e.g. bluetoothd restarting at the wrong moment), `servicesAdded`
  never completes and every subsequent `registerDevice` waits 10 s and fails — loudly now, but
  with no re-attempt until Bluetooth toggles (which rebuilds the server). Forward-path only.
- **The gsettings lifecycle exemption lives only in a commit message.** It won't survive a
  device reflash and isn't in the deploy docs — add it to the iteration-loop checklist in
  `ubuntu-touch-phase6-handover.md` next to the AppArmor patch step.
- Pairing-event poll flows still never terminate (minor leak); in-session PPoG reset is still
  fatal rather than renegotiated (acceptable — revisit only if resets remain visible in logs
  after this series soaks).
- Cosmetic: `LibPebbleModule.jvm.kt`'s `useNativeMtu` comment still claims the MTU is only
  available via AcquireWrite/AcquireNotify — 0.1.55 made that stale.

---

# Addendum (2026-08-18): `le-connection-abort-by-local` after prolonged reconnect churn — new, not yet root-caused

Found live this session, after the pairing-agent fix (`PairingAgent.jvm.kt`, 0.1.66) let a fresh
pair complete cleanly. Once the connection dropped and the app was left to its own reconnect
loop unattended for ~35 minutes (26 consecutive `WatchManager` failures against this same watch,
firmware v4.23.0 — forward-path, per finding 5 above), the failure mode **changed underneath the
existing PPoG/MTU findings** to something none of them describe:

- `Device1.Connect()` (`DbusGattClient.jvm.kt: doConnect()`) throws almost immediately with
  `org.freedesktop.dbus.exceptions.DBusExecutionException: le-connection-abort-by-local` — BlueZ
  itself aborting the connect, not the watch refusing it.
- **Confirmed via a live `btmon` HCI capture spanning several of these attempts**: `bluetoothd`
  never once issues `LE Create Connection` (extended or legacy) for any of them. Each attempt
  only cycles `LE Set Extended Scan Enable/Disable` and `LE Add/Remove Device From Filter Accept
  List`, then aborts — the radio is never actually asked to connect. This is a different failure
  point than every finding above, all of which assume the HCI connection at least gets attempted.
- Ruled out, not just assumed: `BluetoothState`'s adapter-`Powered` poll does intermittently fail
  ("Couldn't poll adapter Powered state (failure 1)") at times that loosely correlate with these
  connect attempts, but every occurrence self-recovered before a second consecutive failure — the
  known "2 failures → Disabled → GATT server torn down" gap (finding 9 above / rev-3's "known
  gaps") is **not** what's happening here; `close gatt server` only logged once, at the app's own
  startup.
- Checked and ruled out: `DbusGattConnector` isn't leaking a D-Bus connection per failed attempt
  (the plausible first guess) — `doConnect()` opens a fresh `buildSystemBusConnection()` per
  attempt, but `WatchManager`'s cleanup path does reliably call `disconnect()` → `conn.disconnect()`
  after every observed failure in the captured logs, confirmed by log correlation, not assumed.

**Not root-caused tonight** — this needs either a longer/annotated `btmon` capture correlated
precisely against app-side timestamps (this session's capture used relative time only; add `-t`
next time), or `bluetoothd` itself run with verbose debug logging, to see *why* it's declining to
even attempt the HCI connect after this many prior attempts against the same device. Worth
checking first next time: whether this is reproducible from a clean bluetoothd restart (rules out
"resource exhaustion after N attempts" vs. "something about this specific device's BlueZ-side
state"), and whether `hcitool con`/`bluetoothctl info` show a phantom/stuck connection state for
this device's object at the point of failure.
