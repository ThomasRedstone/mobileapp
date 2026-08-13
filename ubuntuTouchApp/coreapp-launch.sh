#!/bin/sh
# Runs from the click's own install dir (lomiri-app-launch sets cwd there).
export COREAPP_DIR_NAME=coreapp.thomasredstone
# libpebble3's Room DB defaults to plain /tmp - not writable under Click confinement.
# jpackage's native launcher bakes its JVM args into a static .cfg file (doesn't read
# JDK_JAVA_OPTIONS at runtime), so a real env var is the only reliable override here.
export TMPDIR="$HOME/.cache/coreapp.thomasredstone/tmp"
# Compose Desktop (Skiko/AWT) needs a real X11 display - lomiri-app-launch doesn't set one for
# Click apps (they're expected to be Wayland-native). The live session already runs a rootless
# Xwayland instance for exactly this (same one Libertine/X11 apps use); confirmed real, unconfined
# X11 socket access under Click confinement, no AppArmor policy group needed. Real device is
# single-user with one session, so there's only ever one such socket - but lomiri picks its
# number dynamically (lowest available), so hardcoding it broke on the first clean reboot that
# didn't already have something else holding display 0. Detect it instead - by stat'ing named
# candidates, not globbing/listing the directory: AppArmor confinement denies both exec of
# arbitrary coreutils (ls | head) and open() on the /tmp/.X11-unix/ directory itself for listing
# (confirmed via dmesg: "DENIED ... open ... name=/tmp/.X11-unix/"), but a stat() on a specific,
# named socket path isn't mediated the same way and just works.
X11_DISPLAY=""
for n in 0 1 2 3 4 5 6 7 8 9; do
    if [ -S "/tmp/.X11-unix/X$n" ]; then
        X11_DISPLAY="$n"
        break
    fi
done
if [ -z "$X11_DISPLAY" ]; then
    echo "coreapp-launch.sh: no X11 socket found in /tmp/.X11-unix (checked :0-:9)" >&2
    exit 1
fi
export DISPLAY=":$X11_DISPLAY"
exec coreapp/bin/coreapp "$@"
