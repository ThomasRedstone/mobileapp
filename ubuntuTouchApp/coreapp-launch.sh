#!/bin/sh
# Runs from the click's own install dir (lomiri-app-launch sets cwd there).
export COREAPP_DIR_NAME=coreapp.tomredstone
export COREAPP_TMPDIR="/run/user/$(id -u)/confined/coreapp.tomredstone/tmp"
exec coreapp/bin/coreapp "$@"
