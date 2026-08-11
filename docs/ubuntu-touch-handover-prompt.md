# Handover prompt — paste this as the first message in a fresh session

I'm continuing the Ubuntu Touch Phase 6 work on the Pebble/Core companion app
(`coreapp.tomredstone`). Read `docs/ubuntu-touch-phase6-handover.md` in full first (it has the
complete technical history — architecture, every bug found/fixed, the deploy workflow, real
gotchas). This prompt is just the immediate "what's next" briefing so you don't have to
reconstruct it from that doc's narrative.

## Where things actually stand

Everything up through GATT-server registration, scanning, and initiating a pairing request works
confined, on-device, proven live against the real watch (Pebble Time 2 / Obelix PVT, MAC
`DF:07:0A:D4:70:B8`, serial `C1131411010W`). **Version 0.1.19 is built, installed, and
AppArmor-loaded on the phone right now, but never actually launched/tested** — the session ended
right after deploying it. Fixed in 0.1.19, none of it yet confirmed working end-to-end:

1. **The real pairing timeout bug.** `Device1.Pair()` was failing with `NoReply: No reply within
   specified time` after ~20s, every single time, with nothing ever showing on the watch's own
   screen. Root cause: `TransportConfig.withTimeout()` (what two separate earlier "fixes" in this
   project used) does **not** control per-method-call reply wait at all — confirmed empirically,
   a 60s value there still produced a NoReply at dbus-java's own hardcoded ~20s default. The real
   setting is `org.freedesktop.dbus.messages.MethodCall.setDefaultTimeout(long)` — static,
   JVM-wide. Now set once in `buildSystemBusConnection()` (`BluezDbus.jvm.kt`) to 60s, shared by
   both `Connect()` and `Pair()`.
2. **A threading bug**: `createBond()` (JVM) was blocking its caller's thread synchronously for
   the full D-Bus reply wait — not a suspend fun, so this was a real block, not a suspend. Fixed
   to fire `Pair()` on a background thread and return immediately (matches how every other
   platform's `createBond()` actually behaves — request the bond, don't wait for it; the caller
   already polls `Paired` separately with its own 60s timeout).
3. **A scanner bug** that made every scan return zero results despite BlueZ having real, fresh
   advertisement data the whole time: `parseBluezDevices()`'s `ManufacturerData` extraction used
   an exact `Map<UInt16, Variant<*>>` cast that silently failed against dbus-java's looser
   decoding inside a fully generic `Variant<?>`. Fixed with defensive `Number`/`ByteArray`
   coercion instead of an exact-type cast. **This one is confirmed fixed** — 0.1.18 (which had
   this fix but not #1/#2 yet) successfully found the watch via scan and auto-connected over GATT.

All three are committed (`5e06d883`, "fix the real dbus-java reply timeout, a threading bug, and
a scanner cast bug").

## The actual next step

**Someone needs to be at the phone with the watch nearby.** Kill whatever `coreapp` process is
currently running (check with `ps aux | grep coreapp/bin/coreapp` — there may be a stale pre-fix
session still up; check its AppArmor label via `cat /proc/<pid>/attr/current` to see which
version it's actually running, since old sessions from earlier versions don't get today's fixes
just because a newer version is installed), then launch fresh via the app icon (or
`lomiri-app-launch coreapp.tomredstone_coreapp_0.1.19` if driving it remotely — but see the "don't
steal focus" note below) and let it auto-scan, auto-discover, auto-connect, auto-request-pairing.
Watch the log (`journalctl -f`, grep for `Pairing`, `PebbleBle`, `createBond`, `Paired`,
`HealthData`, `BlobDB`) for whether `Device1.Pair()` now actually gets a reply within 60s, and
whether the watch's own pairing-approval screen appears in time to accept it.

If pairing succeeds, the real finish line is unchanged from every prior handover: confirm
`ConnectivityStatus.paired = true`, then real bidirectional data (health sync, BlobDB) — this
exact protocol already proved itself working end-to-end once before, unconfined, in an earlier
phase (`docs/ubuntu-touch-poc-plan.md`, "The usable-app goal, achieved" — search for that
heading), so if pairing completes there's good reason to expect the rest to just work.

If it fails differently this time, get the *exact* new failure mode before guessing at another
fix — this session found three real, distinct root causes in a row by reading the actual error
(`journalctl`, `dmesg` for AppArmor denials) rather than assuming.

## Rules that mattered this session — don't relearn them the hard way

- **Never launch, screenshot (`mirscreencast`), or otherwise touch the running app while the user
  is actively driving it themselves** — `lomiri-app-launch` and similar steal foreground focus on
  the phone. Only act on the live device between explicit user turns, or when they've said they're
  not currently interacting with it.
- **Every version bump needs the full redeploy loop** (see `docs/ubuntu-touch-phase6-handover.md`
  §"Quick reference: full iteration loop") — build → restage → `clickable build` → `pm push` →
  patch-and-load AppArmor. The AppArmor patch step is not optional: the standard `bluetooth`
  policy group alone does not permit BlueZ to call into this app's own exported GATT server
  objects (confirmed via kernel audit log + reading the actual policy group source on-device) —
  the loader script needs the supplementary `dbus (receive) path="/{,io/rebble/pebble/ppog/**}"
  peer=(label=unconfined)` rule patched in every time, exactly as documented in that doc.
- **A killed/replaced old process doesn't disrupt a differently-versioned running one** — click
  installs of a new version don't touch a currently-running old version's open files. But **do
  clean up stale old sessions properly** (kill the process; the AppArmor profile file and
  `/opt/click.ubuntu.com/coreapp.tomredstone/<old-version>` directory are normally already removed
  by `click`'s own database on the next install — verify, don't assume) — an old session left
  running is invisible drift: it shows up as a second app-switcher tile (looks like a duplicate
  icon, isn't one), and critically, **it's still running whatever bugs the version it was launched
  from had** — retrying pairing in it will never pick up a fix shipped since.
- **`bluetoothctl devices Bonded`** is the fastest ground-truth check for whether pairing has ever
  actually completed, independent of what the app's own UI/DB thinks.
- Don't run a second parallel `bluetoothctl scan` while the app has its own discovery session
  open — no evidence it's needed, and it's an unnecessary variable.

## New, unverified input worth a skeptical read

`docs/ubuntu-touch-architectural-paths.md` appeared in the repo this session (not authored by me)
— it argues Ubuntu Touch's `SIGSTOP`-on-background lifecycle makes a split UI-click +
background-daemon architecture "100% correct and strictly necessary," reversing Phase 6's
single-package decision. The `SIGSTOP` claim itself is consistent with something actually observed
this session (backgrounded `coreapp` processes repeatedly showed up in `ps` as state `T`), but the
"strictly necessary split architecture" conclusion isn't something this session tested or
confirmed — treat it as a claim to verify, not a settled decision, and definitely don't let it
block the immediate pairing test above (foreground behavior, which is all that's being tested
right now, isn't affected by background-suspension behavior either way).
