# Phase 6 Handover: Click packaging, state as of 2026-08-10

Written to reset context and pick straight back up. This is a **how-to-continue** doc — for the
full narrative/investigation history (why decisions were made, dead ends, evidence), see
`docs/ubuntu-touch-poc-plan.md` (long; search it by section header, don't read start to end).

## Where things stand

**Goal** (`/goal`): "get phase 6 built using clickable." **Substantially achieved**: a real
`.click` builds via the actual `clickable` tool, installs on-device, loads a real AppArmor profile,
and — confined, headless — the app starts up completely (Firebase, Koin DI, Room DB, WatchManager,
GattServerManager, permission checks, calendar sync) with zero failures other than the *expected*
missing `$DISPLAY` (no X11 session was given to the test). That's the one dimension left untested.

**Two other work streams, deliberately parked, not urgent right now:**
- BLE reconnect-after-drop stalls (sometimes minutes, sometimes 20+) — root-caused: this watch
  class (`WatchType.EMERY`, this Pebble Time 2) doesn't do general discoverable advertising while
  disconnected, confirmed by 36s of continuous active scanning seeing zero RSSI updates, and
  matched against `advertisesWhenNotConnected()` in `PebbleBle.kt`, which already encodes this.
  Nothing actionable found on the host side; documented as a known limitation, not chased further.
- Notifications / call accept-reject: tracked in the plan doc's roadmap section, real future work,
  not started, not blocking anything here.

## The build now runs on the workstation, not the phone

**Why**: the phone froze and needed a power cycle once this session, plausibly (not proven) from
the sustained heavy on-device build/AppArmor-reload load. Moved the whole pipeline to the
workstation (64 cores, 256GB RAM) via QEMU user-mode emulation — durable, not a workaround.

**One-time setup already done** (all workstation-local, none of this is in the repo):
- `qemu-aarch64-static` was already registered in `binfmt_misc` on this machine — nothing to do.
- Aarch64 JDKs fetched to `/home/tom/.jdks/`: `jdk-21.0.12+8` (Temurin) and `jdk-17.0.20+8`
  (needed because `:blobannotations`, `:blobdbgen`, `:libpebble3` pin Gradle toolchain 17).
- `/home/tom/.gradle/gradle.properties` registers both:
  ```
  org.gradle.java.installations.paths=/home/tom/.jdks/jdk-21.0.12+8,/home/tom/.jdks/jdk-17.0.20+8
  ```
- A cross-architecture `objcopy` (needed by `jlink --strip-debug`, and this workstation's native
  `objcopy` has no aarch64 BFD support) lives at `/home/tom/.jdks/aarch64-tools/objcopy` (symlink
  to the extracted `binutils-aarch64-linux-gnu` package's `aarch64-linux-gnu-objcopy`).

**To build the distributable app image on the workstation:**
```bash
cd /home/tom/own/mobileapp
export JAVA_HOME=/home/tom/.jdks/jdk-21.0.12+8
export PATH="/home/tom/.jdks/aarch64-tools:$PATH"
./gradlew :composeApp:createDistributable --no-configuration-cache
```
- `--no-configuration-cache` is required — there's an unrelated Gradle config-cache serialization
  bug on a Kotlin/Native toolchain path property, unrelated to anything in this project.
- Takes ~5 min clean, faster incrementally. Output:
  `composeApp/build/compose/binaries/main/app/coreapp/` (an app-image dir: `bin/coreapp` native
  launcher, `lib/runtime/` a jlink-trimmed JRE, `lib/app/` the jars). ~260MB.
- Verify architecture if in doubt: `file composeApp/build/compose/binaries/main/app/coreapp/bin/coreapp`
  should say `ARM aarch64`, not x86-64.

## The Click package: `ubuntuTouchApp/`

Real files, all committed except the two listed as gitignored:
- `clickable.yaml` — `builder: custom`, `framework: ubuntu-touch-24.04-1.x` (**not** `24.04-2.x` —
  that string isn't recognized by this device's `click-apparmor` tooling and silently produces no
  AppArmor profile at all, a real finding, not a guess).
- `manifest.json` — `name: "coreapp.tomredstone"`, references `coreapp.apparmor`/`coreapp.desktop`.
- `coreapp.apparmor` — `policy_groups: ["bluetooth", "networking"]`, `policy_version: 2404.1`.
- `coreapp.desktop` — `Exec=coreapp-launch.sh`.
- `coreapp-launch.sh` — sets `COREAPP_DIR_NAME=coreapp.tomredstone` (matches `@{APP_PKGNAME}`, read
  by `AppDirs`), then execs `coreapp/bin/coreapp`.
- `coreapp/` — **gitignored**, staged locally by copying the build output (see below), *not* part
  of the repo (262MB, regenerated every build).
- `build/` — **gitignored**, `clickable`'s own output dir.

**To refresh the staged app image and rebuild the `.click`:**
```bash
rm -rf /home/tom/own/mobileapp/ubuntuTouchApp/coreapp
cp -r /home/tom/own/mobileapp/composeApp/build/compose/binaries/main/app/coreapp \
      /home/tom/own/mobileapp/ubuntuTouchApp/coreapp
cd /home/tom/own/mobileapp/ubuntuTouchApp
# bump the version in manifest.json first - each install needs a new version or the daemon errors
clickable build --arch arm64 --accept-review-errors
```
- The `FAIL`/`(NEEDS REVIEW) reserved policy group 'bluetooth'` review output is expected and
  harmless — the bundled click-reviewer doesn't know this framework, but the `.click` is still
  produced correctly (same precedent as the separate `ut-sonic-player` project).
- Output: `ubuntuTouchApp/build/aarch64-linux-gnu/app/coreapp.tomredstone_<version>_arm64.click`.

## Installing and testing on the phone

**This device's own `click install`/`pkcon install-local` path is broken** (no `pkcon` binary, no
click plugin in PackageKit). Use `phone-manager` instead — a separate real tool at
`~/own/phone-manager`, already running as a rooted classic snap on the phone (`pmd`), reachable via
its CLI:
```bash
/home/tom/own/phone-manager/dist/pm --ssh 100.87.156.48 push \
  /home/tom/own/mobileapp/ubuntuTouchApp/build/aarch64-linux-gnu/app/coreapp.tomredstone_<version>_arm64.click
```
This genuinely runs `click install` as root and un/re-installs the version.

**The AppArmor profile does NOT load automatically** off this install path (`aa-clickhook` doesn't
fire). Load it manually via a `pm payload` (the sanctioned one-off-root-action mechanism):
```bash
mkdir -p /tmp/aaload && cat > /tmp/aaload/install.sh <<'EOF'
#!/bin/sh
set -e
apparmor_parser -r /var/lib/apparmor/profiles/click_coreapp.tomredstone_coreapp_<version>
EOF
chmod +x /tmp/aaload/install.sh
tar czf /tmp/aaload.tar.gz -C /tmp/aaload install.sh
/home/tom/own/phone-manager/dist/pm --ssh 100.87.156.48 payload --id aaload-vX --script install.sh /tmp/aaload.tar.gz
```
**Careful with the profile name**: the compiled file is named `click_coreapp.tomredstone_coreapp_<version>`,
but the AppArmor profile *declared inside it* is `coreapp.tomredstone_coreapp_<version>` (no `click_`
prefix) — that's the name `aa-exec -p` needs, not the filename.

**Confined test run** (no display — see "what's next" below for the real GUI test):
```bash
ssh 100.87.156.48 "cd /opt/click.ubuntu.com/coreapp.tomredstone/<version> && \
  aa-exec -p coreapp.tomredstone_coreapp_<version> ./coreapp-launch.sh"
```
Expect it to run cleanly through Firebase/Koin/Room/WatchManager/permissions/calendar-sync and then
fail on `HeadlessException: No X11 DISPLAY variable was set` — that's the current, expected,
correct end state. Anything failing *before* that line is a real regression.

Useful before each fresh test: `rm -rf ~/.cache/coreapp.tomredstone ~/.local/share/coreapp.tomredstone /tmp/libpebble3.db*`
on the phone, to rule out stale state from a previous version's run.

## Real gotchas worth not re-discovering

- **Exec of arbitrary system binaries is denied under confinement** — `busctl`, `mkdir`, `id`,
  `dirname` all fail with `Permission denied` even though the *paths* they'd touch are writable.
  Only in-process syscalls (a JVM's own `File.mkdirs()`, Python's `os.makedirs()`) work. Any fix
  that shells out to a subprocess needs rethinking for this environment.
- **`java.io.tmpdir` cannot be reliably overridden at runtime** for JDK-internal code
  (`androidx.sqlite`'s bundled native driver, specifically) — confirmed empirically twice that a
  runtime `System.setProperty` loses whatever race is happening internally, and `JDK_JAVA_OPTIONS`
  is not read by jpackage's native launcher (it bakes static `java-options=` lines into a `.cfg`
  file at package time). The only thing that reliably works: `compose.desktop.application.nativeDistributions.jvmArgs`
  in `composeApp/build.gradle.kts`, which *does* get baked into that `.cfg`.
- **A plain environment variable (`$TMPDIR`, `$COREAPP_DIR_NAME`) is fine** for code that reads it
  directly (`Database.jvm.kt`, `AppDirs`) — the restriction above is specifically about JVM system
  properties and third-party/JDK code that doesn't consult env vars.
- **Directory creation needs the target's *parent* to already permit writes** — AppArmor denies
  creating a brand-new directory whose parent isn't separately writable (hit this with
  `~/.local/share/<pkg>/` on first-ever run; confirmed the parent `~/.local/share/` and `~/.cache/`
  themselves are fine to create *subdirs* under, just needed to go through a real syscall, not a
  shelled-out `mkdir`).
- **`dbus-java`'s `RemoteObject` addresses every call by the literal string passed to
  `getRemoteObject(...)`**, confirmed by disassembling the actual bundled jar — no
  resolve-to-unique-connection-name caching like `python-dbus` does. This is *why* `DbusGattConnector`
  should already work fine under the `bluetooth` policy group's `peer=(name="org.bluez{,.*}")` rule
  — but this hasn't been verified with a live confined `Device1.Connect()` call yet (see below).
- **Real device is single-user**, home always `/home/phablet` — hardcoding that path (as the
  `java.io.tmpdir` jvmArg does) is a deliberate, accepted call, not an oversight.

## What's actually next, in priority order

1. **Verify `DbusGattConnector` really works confined, live.** Strong indirect evidence (bytecode
   disassembly + the `blecheck` low-level-message test both point the same way) but no direct
   confirmation of a real `Device1.Connect()` succeeding from inside *this* package. The blocker
   last time was the jpackage runtime having no general-purpose `java` binary to run an ad hoc test
   class with — either extend `coreapp-launch.sh`-style testing with a purpose-built runnable jar
   (`Main-Class` manifest, run via the runtime's `lib/jexec`), or just get far enough into a real
   app run (next item) that `DbusGattConnector.connect()` fires as part of normal `WatchManager`
   startup and watch the log for a real GATT connection.
2. **Confined GUI launch.** Nothing here has been tested with an actual `$DISPLAY` yet. Real
   open questions: does Xwayland socket access even need a policy group, or is X11 typically
   unconfined-reachable already (check `sonic-player`'s or another real GUI click's compiled
   profile for precedent, the same way the `bluetooth` policy group was found); does the app render
   correctly at this phone's resolution/density under `lomiri-app-launch` (not manual `aa-exec`)
   the way it did under the old Libertine/Xwayland setup.
3. Once (1) and (2) both work: a real end-to-end test — confined Click, real display, real BLE
   connect, watch data flowing. That's the actual finish line for Phase 6.
4. `google-services.json` sourcing — deferred, not started, lower priority than the above.

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

# 5. Load AppArmor profile (pm payload, see above) — needed every version bump.

# 6. Test confined (see above).
```

No phone-side build needed anywhere in this loop — the phone only ever receives a finished
`.click` and runs it. If phone-side *source* also needs to stay in sync for some other reason
(direct on-device debugging, say), that's a separate `git bundle`/`fetch` step, not required for
this loop.
