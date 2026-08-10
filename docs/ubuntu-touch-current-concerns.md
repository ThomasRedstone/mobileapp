# Ubuntu Touch PoC: Current Concerns and Risks

While the present direction (Phase 6) successfully resolves the technical roadblocks of the proof-of-concept, there are absolutely still some problematic areas and significant risks in the chosen direction for an actual production release. 

Here are the main concerns with the current position and planned direction:

## 1. The UX of a Two-Part Installation (Click + System `.deb`)
The new architecture requires shipping the UI as a standard OpenStore `.click` package, but the BLE background service as a system-level `.deb` package. 
* **The Problem:** Ubuntu Touch is designed around confined Click packages. Forcing users to drop to the terminal to install a `.deb` package with `sudo` or via a script breaks the seamless app store experience. Furthermore, OTA system updates on Ubuntu Touch often wipe out custom `.deb` installs on the rootfs, meaning users might have to manually reinstall the BLE daemon every time their phone updates.

## 2. IPC Latency (The `linux-auto` File-Polling Pattern)
Because the confined Click app cannot talk to the system D-Bus, the plan is to use the `linux-auto` pattern: the app writes commands to a file in its directory, and the privileged background daemon watches that file (via `systemd` path units or `inotify`).
* **The Problem:** This is fine for simple toggles (like "turn on VPN"), but for a continuous, high-throughput BLE connection (forwarding notifications, syncing watchfaces, and potentially passing raw audio data from the Pebble Index ring), file-based polling is incredibly inefficient. It will introduce latency and battery drain that wouldn't exist with a proper socket or D-Bus connection. (See the Bluetooth Confinement deep-dive doc for a cleaner solution to this).

## 3. GPU Acceleration under Click Confinement (Unverified)
The team verified that Compose Desktop (Skiko) renders under Xwayland *inside Libertine*, but explicitly noted that they haven't verified it under *Click confinement*.
* **The Problem:** X11 apps packaged as Clicks often suffer from lack of hardware graphics acceleration on libhybris-based ports (which translates Android GPU drivers to Linux). If Compose Desktop is forced to use software rendering (`llvmpipe` / `ZINK`), the app will likely be sluggish, battery-heavy, and feel very out-of-place on a mobile device. 

## 4. Package Size and Memory Footprint
To run Compose Desktop, you have to bundle a full Java Runtime Environment (JRE 17 or 21) inside the `.click` package.
* **The Problem:** This will inflate the app size enormously (likely 100MB+ just for the runtime) and consume a massive amount of RAM on launch. On older or lower-end Ubuntu Touch devices, the OS will frequently kill the app in the background due to memory pressure. 

## Conclusion
The technical puzzle of "can we make this talk to the watch" is solved, but the current direction sacrifices a lot of **user experience, performance, and battery life** to get there. The decision to avoid a native QML rewrite makes sense from a cost perspective, but they are trading development time for a highly unconventional, brittle deployment model. 
