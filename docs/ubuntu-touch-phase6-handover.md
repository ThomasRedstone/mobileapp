# Phase 6 Handover: Confined GUI + real D-Bus BLE, state as of 2026-08-10 (session 4)

## Session 4 update: the AppArmor wall is real, but it's not a dead end — it's patchable

Session 3 (below) concluded local GATT server hosting was architecturally impossible under
confinement, based on the standard `bluetooth` policy group's dbus rules only granting `send` to
`org.bluez`, never `receive` on objects this app exports. **That's still true for the standard
policy group alone** — but the compiled AppArmor profile on-device
(`/var/lib/apparmor/profiles/click_<pkg>`) is plain, human-editable policy source, not a sealed
binary, and this device already has a sanctioned root-action mechanism (`pm payload`) used
throughout this whole PoC for exactly this kind of AppArmor profile work. Patched in one
additional rule after the bluetooth policy group's block and reloaded with `apparmor_parser -r`:
```
dbus (receive)
    bus=system
    path="/{,io/rebble/pebble/ppog/**}"
    peer=(label=unconfined),
```
(scoped to just this app's own exported object tree — not a blanket grant). **Confirmed working**:
`RegisterApplication` now succeeds confined, `GetManagedObjects()` gets called by bluetoothd and
returns all 5 exported objects, no AppArmor denials, no error. Local GATT server hosting *is*
possible under Click confinement after all — the `bluetooth` policy group alone just doesn't cover
it, and needs this one supplementary rule applied on-device.

**This isn't expressible in the click's own `coreapp.apparmor` manifest** (that only supports
`policy_groups`/`policy_version`, not raw custom dbus rules), so it doesn't survive a normal
`clickable build`+install — it has to be reapplied after every version bump, same as the AppArmor
profile *load* step already was. Folded it into that same step (see the updated iteration loop
below) rather than treating it as a separate thing. A real production shape would want either a
proper UBports policy-group feature request (a "ble-gatt-server" or similar policy group) or
confirmation that a click can ship a custom local AppArmor abstraction file that survives normal
installs — neither investigated further this session, out of scope for a PoC.

**Also investigated (and initially got wrong) the "does the actual watch support reversed PPoG"
question from the open-source firmware side.** First pass wrongly assumed this watch was the
original 2016 "Robert" hardware — it isn't. `docs/ubuntu-touch-poc-plan.md:972` has the real,
already-logged answer from an earlier successful connection in this PoC: `watchType=obelix_pvt
runningFwVersion=v4.23.0`. This *is* the new, post-2024 Core Devices hardware (`CORE_OBELIX_PVT`),
which `boards/obelix` in `coredevices/PebbleOS` confirms builds against the `nimble` Bluetooth
backend — the only real (non-qemu/stub) backend in the repo, so obelix has no other option.
`src/bluetooth-fw/nimble/ppog_reversed_service.c` implements reversed PPoG V2
(`0x40000000`, matching the app's `PPOGATT_WATCH_SERVER_V2_SERVICE`) — but `git log` shows it was
added in commit `164be9e` ("bluetooth: add reversed PPoG transport"), first released in **tag
v4.24.0** — one version *after* the `v4.23.0` this watch was running at last connection. So:
**whether this watch supports reversed PPoG today depends entirely on whether it's auto-updated
past v4.24.0 since then** — plausible (normal watches pull OTA firmware updates automatically) but
not confirmed. (Pebble's July 2026 blog post about reverse PPoGATT rollout status describes a
*different* migration — upgrading *recovery* firmware on the installed base of *older*,
pre-Obelix watches for iOS AccessorySetupKit support — not relevant to Obelix's normal firmware,
which got this natively in a straightforward release.) Either way it no longer blocks progress:
forward PPoG (the local GATT server path) now works confined via the AppArmor patch above,
regardless of which the watch ends up using. The `useReversedPpogV2`/`legacyReversedPPoG` flags
flipped on for desktop last session are worth keeping either way — harmless if unused, and reversed
is genuinely simpler when available.

**Still not proven: an actual end-to-end connection.** GATT server registration succeeding is real
progress but is necessary, not sufficient — `bluetoothctl devices Bonded` still shows zero Pebble
watches bonded to this phone. Next real step is unchanged from session 3: pair the watch through
the app's UI (confirmed rendering/running confined via `lomiri-app-launch`) with physical access to
both phone and watch, then watch the log for `PebbleBle`'s forward-PPoG path
(`gattServerManager.registerDevice()` should now return `true`) and a real connected session.

Current staged version: **0.1.15**. AppArmor patch payload IDs on-device:
`aapatch-gatt-recv-2` (the one actually applied; `aapatch-gatt-recv-1` was the broader first draft,
superseded).

---

# Original Phase 6 Handover (session 3, kept for context — see session 4 update above for the
# corrected conclusion)

## Session 3 update: local GATT server hosting is architecturally blocked

Session 2 (below) found that pairing/connecting a real watch actually requires the local GATT
server (`GattServer.jvm.kt`) to register successfully — `PebbleBle.kt` calls
`gattServerManager.registerDevice()` *before* attempting pairing whenever `useReversedPpogV2`/
`legacyReversedPPoG` are both off (the desktop defaults), and fails the whole connection with
`RegisterGattServer` if that returns false.

**Rewrote `GattServer.jvm.kt` from the ground up in pure `dbus-java`** (exporting
`org.bluez.GattManager1`/`GattService1`/`GattCharacteristic1`/`ObjectManager` object paths
directly, no subprocess), replacing the old Python/`dbus-python` companion script (deleted:
`gatt_server_companion.py`), since subprocess exec is denied under confinement the same way
`busctl` was. Two real bugs found and fixed along the way:
- `AbstractConnection.exportObject` requires exported interfaces to be **public** — Kotlin
  `private interface` compiles to non-public bytecode; had to be `internal` (still public at the
  JVM level, unlike `private`).
- `dbus-java`'s inherited `AbstractConnectionBase.sendMessage(Message)` (not `AbstractConnection`
  itself) is how you emit a `Properties.PropertiesChanged` signal from an exported object —
  findable by introspecting the class hierarchy, not obvious from `AbstractConnection`'s own
  method list.

**With those fixed, registration still fails — with a hard wall, not a bug.** Enabled
`bluetoothd -d` via a temporary systemd drop-in (reverted after) and read the kernel audit log
directly:
```
apparmor="DENIED" operation="dbus_method_call" path="/" interface="org.freedesktop.DBus.ObjectManager"
member="GetManagedObjects" mask="receive" peer_pid=<bluetoothd> peer_label="unconfined"
```
Checked the actual `bluetooth` policy group text on-device
(`/usr/share/apparmor/easyprof/policygroups/ubuntu/2404.1/bluetooth`): its dbus rules only grant
`send`+`receive` to peer name `org.bluez*`, `receive` from `/org/bluez/**` paths, and `receive` of
exactly `InterfacesAdded`/`InterfacesRemoved` signals at `/`. **There is no rule letting BlueZ call
method calls into an object path this app hosts.** The policy group is built for being a GATT
*client* only. This means **hosting a local BlueZ GATT server from a confined Click app is not
possible on Ubuntu Touch**, in any implementation language — the original Python approach would
have hit the identical wall had it ever been tested confined (it was seemingly only proven working
unconfined).

**Pivoted to reversed PPoG as the only viable path** (the watch hosts the GATT server; this app
only subscribes as a client — no local server needed at all). Added a proper per-platform DI seam
for this rather than a one-off hack: `defaultBleConfig()` is now `expect`/`actual` in
`pebble/src/{common,android,ios,jvm}Main/kotlin/coredevices/pebble/watchModule*.kt`
(`watchModule.kt`'s `factory { BleConfig() }` → `factory { defaultBleConfig() }`), with the JVM
actual flipping `legacyReversedPPoG`/`useReversedPpogV2` both to `true`. Android/iOS unaffected
(same `BleConfig()` defaults as before). `GattServer.jvm.kt` is left in place (still eagerly opened
per existing architecture, still fails confined, still non-fatal/caught) rather than ripped out —
it's real, correct, working code, just unusable *under confinement specifically*; useful again for
any future unconfined/system-service deployment shape, and for any watch that turns out to need
forward PPoG.

**Not yet known: whether the actual watch (Pebble Time 2 / `WatchType.EMERY`) supports reversed
PPoG at all.** `reversedConfig` in `PebbleBle.kt` is chosen from the watch's *actually discovered*
GATT services at connect time (`PPOGATT_WATCH_SERVER_V2_SERVICE` / `PPOGATT_DEVICE_SERVICE_UUID_CLIENT`
UUIDs), not from watch type — this can only be answered by a real connection attempt. That needs a
human: `bluetoothctl devices Bonded` shows zero Pebble watches currently bonded to this phone at
all, so pairing has to happen through the app's UI (confirmed rendering/running confined via
`lomiri-app-launch` in session 2) with physical access to both phone and watch. **This is the
actual next step, and it's not something to fake or force autonomously.**

Current staged version: **0.1.15**.

---

# Original Phase 6 Handover (session 2, kept for full context)

Written to reset context and pick straight back up. For the full narrative/investigation history
(why decisions were made, dead ends, evidence), see `docs/ubuntu-touch-poc-plan.md` (long; search
it by section header, don't read start to end). This doc supersedes the previous handover — that
one's "what's next" items 1 and 2 are now substantially done; see below.

## Where things stand

**Both of the previous handover's top-priority items are done:**

1. **X11/Xwayland GUI rendering under Click confinement — confirmed working, real device.**
   Launched via the actual sanctioned mechanism (`lomiri-app-launch`, not manual `aa-exec`), fully
   confined (`coreapp.tomredstone_coreapp_0.1.10 (enforce)` per `/proc/<pid>/attr/current`), and a
   `mirscreencast` capture confirmed real Compose UI rendering on the phone's actual screen at its
   real resolution (1080x2340) — the onboarding route with its pager dots. **Key finding: X11
   socket access needs no extra AppArmor policy group at all** — it's already unconfined-reachable
   under the existing `bluetooth`/`networking` policy groups. GLX hardware acceleration fails
   (`Cannot create Linux GL context`) but Skiko silently falls back and renders correctly anyway —
   not investigated further, not blocking.
2. **Live confined D-Bus BlueZ access — confirmed working.** `BondedWatchSeeder`'s
   `ObjectManager.GetManagedObjects()` call over `dbus-java` runs successfully confined, no
   exceptions, proving the `dbus-java`-over-`bluetooth`-policy-group mechanism that
   `DbusGattConnector` already relies on for GATT connects is real and works live, not just in
   theory.

**Getting here required finding and fixing three real, non-obvious bugs** (see "What was fixed"
below) — none of this worked out of the box; the previous handover's "zero failures" headless
result was accurate only because that run never reached these code paths.

**Not done, and not fakeable:** an actual live `Device1.Connect()` end-to-end GATT connection to a
real watch. `bluetoothctl devices Bonded` shows **zero Pebble/Core watches currently bonded to
this phone** — only unrelated accessories (headphones, a car, a keyboard, a scale). The app's UI
now genuinely runs confined and on-screen, so pairing is possible through it, but actually pairing
a watch needs a human with physical access to both the phone screen and the watch (BLE pairing
prompts, watch-side button presses). **This is the one remaining step for a real end-to-end test**
— see "What's actually next" below.

## What was fixed this session (all real root causes, not workarounds)

### 1. `busctl` subprocess shell-outs are denied under Click confinement

The confined headless test (previous handover's "clean" baseline) had never actually exercised
scanning, pairing, or adapter-state polling — once it did, every one of them threw
`Cannot run program "busctl": Permission denied`, matching the already-documented gotcha ("exec of
arbitrary system binaries is denied under confinement"). `nativeBluetoothStateFlow` polls the
adapter's `Powered` property to decide whether `WatchManager` may even start scanning
(`bluetoothStateProvider.state.first { it == BluetoothState.Enabled }`) — so this wasn't cosmetic,
it silently wedged the whole connection pipeline forever under confinement.

**Fix:** rewrote all four `BusctlDbus`-dependent files to use `dbus-java` directly, mirroring the
pattern `DbusGattConnector` (formerly `DbusGattClient.jvm.kt`) already used successfully for GATT
connects:
- `BluetoothState.jvm.kt` — adapter `Powered` polling.
- `Pairing.jvm.kt` — `Device1.Paired`/`Pair()`.
- `bt/ble/transport/impl/LinuxBleScanner.jvm.kt` — `Adapter1.StartDiscovery`/`StopDiscovery` +
  `ObjectManager.GetManagedObjects()` (replaces the old `BluezObjectParser` regex text-parsing of
  `busctl`'s plain-text output with real structured D-Bus data).
- `BondedWatchSeeder.jvm.kt` — same `GetManagedObjects()` migration.

Shared plumbing (SASL UID workaround, `Adapter1`/`Device1` interfaces, `BluezDevice` parsing)
extracted to a new `bt/ble/transport/impl/BluezDbus.jvm.kt`, reused by `DbusGattConnector` too
(removed its private duplicate). `BusctlDbus.jvm.kt` is deleted — nothing references it anymore.

### 2. `jdk.security.auth` was trimmed from the jlink runtime image

Real, was-always-there bug newly surfaced now that `dbus-java` code paths actually run confined:
`dbus-java`'s SASL handshake calls `SASL.getUserId()` unconditionally — `OptionalLong.orElse(x)`
evaluates `x` eagerly in Java even when the optional is present, so the app's own explicit
`withSaslUid(...)` override doesn't skip the call. `getUserId()` needs
`com.sun.security.auth.module.UnixSystem`, which lives in the `jdk.security.auth` JDK module —
absent from the jlink-trimmed runtime `createDistributable` bakes in (jdeps' static analysis
apparently doesn't catch this dependency). Manifested as `NoClassDefFoundError` at the first live
`dbus-java` connection attempt.

**Fix:** `composeApp/build.gradle.kts` → `nativeDistributions { modules("jdk.security.auth") }`.
Verified present in `lib/runtime/release`'s `MODULES=` line after rebuild.

### 3. `coreapp-launch.sh` needs an explicit `$DISPLAY`

`lomiri-app-launch` doesn't set `$DISPLAY` for Click apps (they're expected to be Wayland-native).
The live session already runs a rootless Xwayland instance for exactly this purpose (same one
Libertine/X11 apps used in the earlier PoC phase) — confirmed its socket exists
(`/tmp/.X11-unix/X1`) and hardcoded `export DISPLAY=":1"` in the launch script. Real device is
single-user/single-session, so this is the same class of accepted hardcode as the existing
`$HOME`-based paths elsewhere in this package — **but unlike those, a display number isn't
guaranteed stable across reboots/sessions the way `$HOME` is.** If a future confined GUI launch
fails with `HeadlessException` again after a device reboot, check `ps aux | grep Xwayland` for the
current display number first before assuming a regression.

## New known gotcha (found, not fixed — separate, larger effort)

**`GattServer.jvm.kt` still shells out to `python3`** (`gatt_server_companion.py`, a bundled
resource) to host the local BlueZ GATT *server* (peripheral role — the phone exposing services for
something else to connect to, as opposed to `DbusGattConnector`'s GATT *client* role connecting
out to a watch). Denied under confinement the same way `busctl` was:
`Cannot run program "python3": Permission denied`. **This failure is caught and logged, not
fatal** — `GattServerManager.openIfNeeded()` just gets `null` back and the app keeps running
normally; confirmed the confined GUI run reaches the onboarding screen fine despite it. Not fixed
this session: unlike the `busctl` case, `dbus-java` can't easily replace this — exporting D-Bus
object paths (what a GATT *server* needs) is a fundamentally different, harder capability than the
method-calling `dbus-java` already does well for the client side. Real future work if the app ever
needs to act as a BLE peripheral under Click confinement (check whether current features actually
depend on this before investing time here).

## Versions / current build state

Current staged version is **0.1.10** (`ubuntuTouchApp/manifest.json`). The full iteration loop
(build → restage → package → push → load AppArmor → test) is unchanged from the previous
handover — see below, still accurate.

## What's actually next, in priority order

1. **Pair a real watch to this phone, then re-run the confined GUI launch.** This needs a human:
   open the app (now confirmed to render on-screen under `lomiri-app-launch`), trigger pairing
   from its UI, accept the prompt on the watch. Once bonded, `BondedWatchSeeder` will pick it up
   automatically on next launch (confirmed working confined already) and `WatchManager` should
   attempt a real `DbusGattConnector.connect()` — watch the log for a real GATT connection
   completing. This is the actual finish line for Phase 6.
2. `google-services.json` sourcing — deferred, not started, lower priority than the above (was
   already low priority last handover; unchanged).
3. If BLE peripheral mode (`GattServer`'s python3 companion) turns out to be needed for any
   current feature, it needs its own real design work — not a quick fix, see above.

## Quick reference: full iteration loop

```bash
# 1. Edit code, commit as usual.
# 2. Build (workstation):
cd /home/tom/own/mobileapp
export JAVA_HOME=/home/tom/.jdks/jdk-21.0.12+8
export PATH="/home/tom/.jdks/aarch64-tools:$PATH"
./gradlew :composeApp:createDistributable --no-configuration-cache

# 3. Restage + repackage:
rm -rf ubuntuTouchApp/coreapp
cp -r composeApp/build/compose/binaries/main/app/coreapp ubuntuTouchApp/coreapp
# bump version in ubuntuTouchApp/manifest.json
cd ubuntuTouchApp && clickable build --arch arm64 --accept-review-errors

# 4. Install:
/home/tom/own/phone-manager/dist/pm --ssh 100.87.156.48 push \
  build/aarch64-linux-gnu/app/coreapp.tomredstone_<version>_arm64.click

# 5. Load AppArmor profile, WITH the supplementary GATT-server receive rule patched in
# (pm payload, needed every version bump - this rule isn't expressible in coreapp.apparmor's
# policy_groups, see "session 4 update" above):
mkdir -p /tmp/aaload && cat > /tmp/aaload/install.sh <<'EOF'
#!/bin/sh
set -e
PROFILE=/var/lib/apparmor/profiles/click_coreapp.tomredstone_coreapp_<version>
python3 - "$PROFILE" <<'PYEOF'
import sys
path = sys.argv[1]
with open(path) as f:
    content = f.read()
marker = '      peer=(label=unconfined),\n\n  # Description: Can access the network'
insert = (
    '      peer=(label=unconfined),\n\n'
    '  # Allow bluetoothd to call into our own exported GATT server objects\n'
    '  # (RegisterApplication walks these via GetManagedObjects, then\n'
    '  # ReadValue/WriteValue/StartNotify/StopNotify per characteristic) - scoped\n'
    '  # to just our own exported object tree, not a blanket grant.\n'
    '  dbus (receive)\n'
    '      bus=system\n'
    '      path="/{,io/rebble/pebble/ppog/**}"\n'
    '      peer=(label=unconfined),\n\n'
    '  # Description: Can access the network'
)
if marker not in content:
    sys.exit("marker not found - has the bluetooth policy group block moved/changed?")
content = content.replace(marker, insert, 1)
with open(path, 'w') as f:
    f.write(content)
PYEOF
apparmor_parser -r "$PROFILE"
EOF
chmod +x /tmp/aaload/install.sh
tar czf /tmp/aaload.tar.gz -C /tmp/aaload install.sh
/home/tom/own/phone-manager/dist/pm --ssh 100.87.156.48 payload --id aaload-vX \
  --script install.sh /tmp/aaload.tar.gz

# 6a. Test headless/confined (quick sanity check, no GUI):
ssh 100.87.156.48 "cd /opt/click.ubuntu.com/coreapp.tomredstone/<version> && \
  aa-exec -p coreapp.tomredstone_coreapp_<version> ./coreapp-launch.sh"

# 6b. Test with real GUI, the sanctioned way (needs the phablet session's D-Bus env,
# not available over a plain ssh shell - pull it from any live session process):
ssh 100.87.156.48 "export XDG_RUNTIME_DIR=/run/user/32011 \
  DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/32011/bus DISPLAY=:1; \
  lomiri-app-launch coreapp.tomredstone_coreapp_<version>"
# check it's really confined: cat /proc/<pid>/attr/current (should say "... (enforce)")
# to see the screen: mirscreencast -n 1 -f /tmp/screen.rgba --query   (prints WxH)
#                     mirscreencast -n 1 -f /tmp/screen.rgba          (captures one frame)
#                     scp it off and decode raw RGBA at the reported WxH (e.g. via Pillow)
# to stop: find its pid (ps aux | grep coreapp) and kill it directly - no
# lomiri-app-launch-tool stop on this device (binary doesn't exist here)
```

Useful before each fresh test: `rm -rf ~/.cache/coreapp.tomredstone ~/.local/share/coreapp.tomredstone /tmp/libpebble3.db*`
on the phone, to rule out stale state from a previous version's run.

## Real gotchas worth not re-discovering (carried over, still accurate)

- **Exec of arbitrary system binaries is denied under confinement** — `busctl`, `python3`,
  `mkdir`, `id`, `dirname` all fail with `Permission denied` even though the *paths* they'd touch
  are writable. Only in-process syscalls (a JVM's own `File.mkdirs()`,
  `dbus-java`'s direct D-Bus calls) work. Any fix that shells out to a subprocess needs rethinking
  for this environment — this bit twice this session alone (`busctl` and `python3`).
- **`java.io.tmpdir` cannot be reliably overridden at runtime** for JDK-internal code — the only
  thing that reliably works is `compose.desktop.application.nativeDistributions.jvmArgs`.
- **A plain environment variable is fine** for code that reads it directly — the restriction above
  is specifically about JVM system properties and third-party/JDK code that doesn't consult env
  vars.
- **jlink's trimmed runtime can silently drop JDK modules that reflection/eager-eval code paths
  need**, even when static analysis (jdeps) is what Compose's plugin uses to decide what to keep.
  If a `NoClassDefFoundError` mentions a `com.sun.*`/`sun.*`/`jdk.internal.*` class, check
  `lib/runtime/release`'s `MODULES=` line before assuming it's an app bug.
- **`dbus-java`'s `RemoteObject` addresses every call by the literal string passed to
  `getRemoteObject(...)`** — no resolve-to-unique-connection-name caching like `python-dbus` does.
- **`lomiri-app-launch` (and its sibling CLI tools) need the phablet session's live
  `DBUS_SESSION_BUS_ADDRESS`/`XDG_RUNTIME_DIR`** — a plain `ssh` shell doesn't have them; pull them
  from any long-running session process's `/proc/<pid>/environ` (e.g. one of the
  `ayatana-indicator-*` services).
- **Real device is single-user**, home always `/home/phablet` — hardcoding that path is a
  deliberate, accepted call, not an oversight. The Xwayland `:1` hardcode in `coreapp-launch.sh` is
  the same idea but *less* durable (survives one boot, not guaranteed across reboots) — see gotcha
  #3 above.
