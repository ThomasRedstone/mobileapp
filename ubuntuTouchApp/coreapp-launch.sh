#!/bin/sh
# Runs from the click's own install dir (lomiri-app-launch sets cwd there).
export COREAPP_DIR_NAME=coreapp.tomredstone
# libpebble3's Room DB defaults to plain /tmp - not writable under Click confinement.
# jpackage's native launcher bakes its JVM args into a static .cfg file (doesn't read
# JDK_JAVA_OPTIONS at runtime), so a real env var is the only reliable override here.
export TMPDIR="$HOME/.cache/coreapp.tomredstone/tmp"
exec coreapp/bin/coreapp "$@"
