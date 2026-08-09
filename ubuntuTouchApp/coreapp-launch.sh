#!/bin/sh
# Runs from the click's own install dir (lomiri-app-launch sets cwd there).
export COREAPP_DIR_NAME=coreapp.tomredstone
# libpebble3's Room DB (and, transitively, Room's bundled SQLite driver, which extracts its
# native lib to a temp file during a static initializer - too early for a runtime
# System.setProperty in Main.kt to reliably win the race) default to plain /tmp, not writable
# under Click confinement. JDK_JAVA_OPTIONS is read by any Java 9+ launcher (including
# jpackage's native one) before the JVM starts running bytecode, so this always wins.
export JDK_JAVA_OPTIONS="-Djava.io.tmpdir=$HOME/.cache/coreapp.tomredstone/tmp"
exec coreapp/bin/coreapp "$@"
