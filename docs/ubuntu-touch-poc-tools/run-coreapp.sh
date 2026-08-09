#!/bin/bash
# Real launcher for the composeApp desktop build, registered as a Libertine app
# (docs/ubuntu-touch-poc-plan.md, Phase 5). Sets up the D-Bus proxy inside this
# same sandbox instance (each libertine-launch/lomiri-app-launch invocation gets
# its own private /run tmpfs) before starting the app.
set -e
mkdir -p /run/dbus
python3 /home/phablet/dbus_relay_in_sandbox.py > /home/phablet/dbus_relay.log 2>&1 &
sleep 1
CP=$(cat /home/phablet/desktop_cp.txt):/home/phablet/mobileapp/composeApp/build/processedResources/desktop/main
cd /home/phablet/mobileapp
exec /usr/lib/jvm/java-21-openjdk-arm64/bin/java -cp "$CP" coredevices.coreapp.MainKt
