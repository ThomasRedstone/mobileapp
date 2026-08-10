# Ubuntu Touch PoC: Deep Dive into Bluetooth Confinement

The proposed Phase 6 architecture (X11-packaged-as-Click) relies on splitting the app into two parts: a confined Click package for the UI, and a system-level `.deb` helper daemon for Bluetooth (BLE) access. This split exists because of the assumption that Click app confinement strictly blocks access to the system D-Bus and `bluez5`.

However, there is a much cleaner path to solve the BLE confinement issues without breaking the app apart.

## The Solution: The `"bluetooth"` AppArmor Policy Group

Ubuntu Touch features a built-in, reserved AppArmor policy group specifically for Bluetooth access: **`"bluetooth"`**. 

By simply declaring `"bluetooth"` in the `policy_groups` array of the `.click` package's `apparmor.json` manifest, the OS automatically generates an AppArmor profile that grants the confined app direct, unrestricted D-Bus access to `bluez5`.

### Architectural Impact
With this policy enabled:
1. **Direct D-Bus Access:** The JVM app can use `dbus-java` to talk directly to `org.bluez` over the system bus, entirely from within the confined Click package.
2. **No Helper Daemon:** You can completely scrap the idea of shipping a separate `.deb` system service.
3. **No File Polling:** You avoid the high-latency `linux-auto` IPC pattern (file-polling), which is highly inefficient for constant BLE data streams.
4. **Maintained Confinement:** The app remains fully confined and secure regarding the filesystem, network, and sensors; it is simply granted the specific BlueZ privilege it needs.

## OpenStore Review vs. Direct Distribution

### The Catch: Manual App Store Review
The `"bluetooth"` policy group is classified as a **reserved policy** by UBports. This means:
* Unlike apps using only common policies (like `networking` or `audio`), your app will not be automatically approved when submitted to the OpenStore.
* It will trigger a **manual review** by the OpenStore maintainers.

Because raw BlueZ access is powerful, maintainers want to ensure malicious apps aren't scraping Bluetooth data. However, since you are building a companion app for a smartwatch and smart ring—a use case that fundamentally *requires* raw BLE access to function—the maintainers are highly likely to approve it. 

### Direct Distribution (Sideloading)
There are **zero technical limitations** when sideloading the app yourself. 

The "reserved" status of the `"bluetooth"` policy group is strictly a policy of the OpenStore review process, not an OS-level restriction. When you (or anyone suitably brave) sideloads the `.click` package directly (e.g., via `clickable install` or `pkcon install-local --allow-untrusted`), the system parses the `apparmor.json`, sees the `"bluetooth"` group, and automatically enforces an AppArmor profile granting the requested BlueZ access. 

This means you can build, test, and distribute the single-package Click architecture immediately via GitHub releases or direct downloads, and it will work perfectly on any UT device without users needing to hack their root filesystem or install separate daemon packages.
