#!/bin/sh
# Runs from the click's own install dir (lomiri-app-launch sets cwd there).
export COREAPP_DIR_NAME=coreapp.tomredstone
# libpebble3's Room DB defaults to plain /tmp - not writable under Click confinement.
# jpackage's native launcher bakes its JVM args into a static .cfg file (doesn't read
# JDK_JAVA_OPTIONS at runtime), so a real env var is the only reliable override here.
export TMPDIR="$HOME/.cache/coreapp.tomredstone/tmp"
# Compose Desktop (Skiko/AWT) needs a real X11 display - lomiri-app-launch doesn't set one for
# Click apps (they're expected to be Wayland-native). The live session already runs a rootless
# Xwayland instance for exactly this (same one Libertine/X11 apps use); confirmed real, unconfined
# X11 socket access under Click confinement, no AppArmor policy group needed. Real device is
# single-user with one session, so a fixed display number is fine here - same class of hardcode as
# $HOME elsewhere in this package.
export DISPLAY=":1"
exec coreapp/bin/coreapp "$@"
