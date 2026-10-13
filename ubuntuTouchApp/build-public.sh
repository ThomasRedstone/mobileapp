#!/bin/bash
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
[[ $(uname -m) == aarch64 ]] || { echo 'Run on native Linux ARM64 with JDK 21 and Android SDK (Compose jpackage is native)' >&2; exit 1; }
command -v clickable >/dev/null
[[ ! -e "$HERE/coreapp" ]] || { echo 'Preserve/move existing ubuntuTouchApp/coreapp before rebuilding' >&2; exit 1; }
UT_BUILD_JOBS=${UT_BUILD_JOBS:-2}
[[ "$UT_BUILD_JOBS" =~ ^[1-9][0-9]*$ ]] || { echo 'UT_BUILD_JOBS must be positive' >&2; exit 1; }
cd "$ROOT"
./gradlew :composeApp:createDistributable --no-configuration-cache --no-daemon --max-workers="$UT_BUILD_JOBS"
IMAGE="$ROOT/composeApp/build/compose/binaries/main/app/coreapp"
file "$IMAGE/bin/coreapp" | grep -q 'ARM aarch64'
file "$IMAGE/lib/runtime/bin/java" | grep -q 'ARM aarch64'
cp -a "$IMAGE" "$HERE/coreapp"
cp "$ROOT/LICENSE" "$HERE/coreapp/PORT-UPSTREAM-LICENSE"
git rev-parse HEAD > "$HERE/coreapp/PORT-SOURCE-REVISION"
cd "$HERE"
clickable build --arch arm64
