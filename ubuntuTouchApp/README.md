# Unofficial Ubuntu Touch port

Current entry point for the `ubuntu-touch-poc` branch. Upstream GPL/commercial licensing and CONTRIBUTING CLA/AI disclosure rules remain in force. Historical docs describe several superseded experiments; consult current source and this entry point first.

Build on Linux ARM64 (Ubuntu Noble), JDK 21 plus the repository's JVM17 compile toolchain, the repository's Gradle wrapper, Android SDK required for project configuration, Docker and Clickable >=8.9. `./ubuntuTouchApp/build-public.sh` builds the native Compose app/JRE, verifies ELF architecture and stages/packages the click without overwriting an existing staged app. Use the existing ARM64 workflow's Android SDK setup when reproducing CI. No READ_PACKAGES secrets are required for this build path: the GitHub Maven credential block configures publishing, while dependency resolution uses public repositories. A full cold-cache Gradle build remains an acceptance gate; this change alone is not evidence of anonymous reproducibility. Package/version pins still follow the upstream Gradle files; record the source commit, dependency resolution and notices for release.

The click is not the entire tested installation: BlueZ GATT access requires the reviewed platform permission adapter/profile, background connection needs the reviewed lifecycle exemption, notification forwarding needs explicit ut-notify allow-list permissions. Those system changes require administrator review and versioned AppArmor policy matching the app manifest. Pair through the app/BlueZ agent, verify suspend/reconnect, then independently opt into notifications. Never copy a historical profile or personal fleet payload blindly. Before removal, revoke the notify grant, remove the exact lifecycle exemption/profile change and uninstall the click; preserve account databases unless the user explicitly requests deletion. OTA can reset integration and requires retesting.

Acceptance: fresh install/pair, normal UI typing, background/suspend/reconnect, reboot, notification privacy, upgrade/uninstall, idle RSS/CPU and battery measurement on a named device/image. Existing historical observations do not cover every UT device. JVM/Xwayland costs and cloud/account/telemetry features retain their upstream limitations; do not include account databases or live notification fixtures in a public release.

A fresh emulated Noble ARM64 attempt on 2026-10-13 used public Gradle 9.6.1, JDK 21+17, a separate cache and a read-only Android SDK. Gradle remained CPU-active in bootstrap/configuration; it was deliberately stopped within the bounded review pass before any distributable/compile success. This is an incomplete build, not a dependency failure or anonymous-build success. Use a native ARM64 runner for the full gate. The public script defaults to 2 workers (UT_BUILD_JOBS can set another positive limit) and no persistent Gradle daemon. No older operational app image/cache was overwritten.


## Native-host compilation with target packaging

A clean isolated native amd64 JVM build completed on 2026-10-14 (87 tasks), followed by explicit ARM64 dependency staging (88 tasks). JVM bytecode is platform independent; `-PutTargetArch=arm64` selects Compose's Linux ARM64 native runtime. The staged classpath preserves duplicate Maven JAR basenames under complete SHA256 prefixes and preserves Gradle resolution order. The actual ARM64 JDK21 jpackage step completed in a network-disabled target container; only packaging ran under emulation. This is not unsupported cross-jpackage or a relabelled amd64 binary. Launcher, JVM, AWT/Xwayland and Skiko native code were verified ARM64; all 180 JARs, their available original notices and JRE legal directory are inventoried/preserved. Window/watch/account acceptance and complete matching dependency sources remain required.

For the alternative public route, build the target packager, inspect/record its image digest and package inventory, and choose an absent output:

```sh
docker build --platform linux/arm64 -f ubuntuTouchApp/arm-packager.Dockerfile -t local-pebble-arm-packager .
UT_ARM_PACKAGER_IMAGE=sha256:REVIEWED_IMAGE_ID UT_OUTPUT=/new/candidate ./ubuntuTouchApp/build-hybrid.sh
```

Run Gradle natively with JDK21 and its JVM17 compile toolchain plus an isolated Android SDK/cache. The packager's official Noble base is pinned by digest; apt inputs are signed but package versions must be preserved from its /BUILD-PACKAGES.txt and source archives for release. Binutils is required for jlink's objcopy. The public script defaults to 2 workers, packages all JDK modules including jdk.security.auth and preserves failed staging/operational app images. jpackage removes standalone java commands by default, so native architecture validation checks libjvm.so instead. Default Clickable review is always run and any failure is propagated; never infer store approval from the presence of a click.

The actual new Noble click built successfully, but default review failed on reserved bluetooth and history policy groups (vetted applications only). These grants are needed by the current integration and were retained. Store review and a supported device permission adapter remain explicit gates; compilation/package creation do not authorize distribution or prove Bluetooth/notification behavior.

The newly owned standalone build-hybrid.sh, inventory-package.py and arm-packager.Dockerfile are MIT under TOOLS-LICENSE. Existing application/Gradle sources and original port scripts retain upstream licenses.
