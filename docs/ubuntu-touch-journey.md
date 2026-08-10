# Ubuntu Touch PoC: The Journey and Harder Roads Taken

This document summarizes the progress made during the Ubuntu Touch Proof of Concept, specifically highlighting the "harder roads" and mis-steps encountered along the way. While the technical feasibility of running the Compose Desktop UI on UT hardware with BLE access has been proven, the path taken was heavily influenced by environmental artifacts and over-engineered workarounds.

## 1. The QEMU / Virtualization Rabbit Hole
A massive amount of effort was spent debugging a `lomiri-full-greeter` crash loop inside a QEMU VM. The team tested various hypotheses (missing `MIR_SOCKET` variables, environment erosion, changing the Mir platform to `mir:wayland`), trying to resolve what appeared to be an architectural blocker.
* **The Reality:** When finally tested on a physical Fairphone 4, the Libertine container and Xwayland worked flawlessly out-of-the-box. The issue was a QEMU-specific lack of Virtual Terminal (VT) switching support blocking DRM master access. Delaying physical hardware testing resulted in a significant time sink.

## 2. Over-engineering D-Bus due to Libertine Sandbox Assumptions
The initial architecture relied on Libertine, assuming it would provide an easy path for X11 desktop apps. However, Libertine's `bwrap` sandbox mounts a `tmpfs` over `/run`, completely blocking access to the system D-Bus (`/run/dbus`).
* **The Hard Road:** To get BLE working, the team built a complex two-hop Python proxy—one script running outside the sandbox and one inside—to bridge the sockets. This introduced file descriptor leak bugs and timeouts.
* **The Pivot:** It became apparent that Libertine didn't actually offer an easier path than a native `.click` package, leading the team to abandon Libertine in "Phase 6" in favor of an X11-packaged-as-Click architecture.

## 3. Fighting the `btleplug` FFI Bridge
When attempting to use Kable's native JVM backend (`btleplug` via Rust FFI) for BLE connections, the team experienced consistent ~600ms connection drops. They spent time chasing red herrings like proxy latency, stale bonding keys, and concurrency issues.
* **The Reality:** By instrumenting the D-Bus proxy, it was discovered that `btleplug` wasn't sending *any* `Device1` method calls over D-Bus at all; the FFI bridge was failing silently in the sandboxed environment.
* **The Fix:** The team dropped the `kable-btleplug-ffi` dependency in favor of writing a direct, much simpler `dbus-java`-based `GattClient`.

## 4. Working around `dbus-java` instead of fixing it
Early on, a SASL authentication bug with `dbus-java` caused it to send the wrong UID (UID 0 instead of the sandbox UID), which BlueZ rejected.
* **The Hard Road:** The team built a fragile workaround using `ProcessBuilder` to shell out to `busctl` for every D-Bus call, which brought its own issues (like discovery scoping to the short-lived subprocess).
* **The Reality:** Much later, they found that the maintained fork of `dbus-java` had a `.withSaslUid()` configuration method to fix this exact problem, allowing them to drop the `busctl` subprocess hacks entirely.

## 5. Underestimating Kotlin Multiplatform JVM target gaps
The assumption was that compiling for desktop would just require stubbing out some `expect`/`actual` calls.
* **The Reality:** Four KMP modules (`:pebble`, `:util`, `:experimental`, `:libindex`) had no `jvm()` target declared at all, and `:util` relied on the Android NDK. This necessitated a massive extraction effort, including stubbing Firebase Crashlytics (which lacks a JVM target) and creating facades to get the project to compile.

## Summary
The project successfully hit its milestones, but the team took a hard road by delaying physical hardware testing, forcing the Libertine architecture too far, and building complex proxies/subprocess wrappers before fully understanding the tools available to them. Phase 6 represented a necessary self-correction toward a cleaner architecture.
