#!/bin/bash
# SPDX-License-Identifier: MIT
# Native-host JVM compilation + target ARM64 JDK packaging; no cross-jpackage.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
: "${UT_OUTPUT:?Choose a new absent absolute candidate directory}"
: "${UT_ARM_PACKAGER_IMAGE:?Build arm-packager.Dockerfile and supply the reviewed image ID/digest}"
[[ "$UT_OUTPUT" = /* && ! -e "$UT_OUTPUT" && ! -L "$UT_OUTPUT" ]] || { echo 'UT_OUTPUT must be absolute and absent' >&2; exit 1; }
[[ ${UT_BUILD_JOBS:-2} =~ ^[1-9][0-9]*$ ]] || exit 1
STAGE=$(mktemp -d "$(dirname "$UT_OUTPUT")/.pebble-build.XXXXXX")
# Preserve failed candidates for diagnosis; never touch an operational app image.
trap 'echo "Preserved build staging: $STAGE" >&2' EXIT
cd "$ROOT"
./gradlew :composeApp:stageUtJvmInput -PutTargetArch=arm64 "-PutJvmOutput=$STAGE/jars" --no-configuration-cache --no-daemon --max-workers="${UT_BUILD_JOBS:-2}"
mkdir "$STAGE/image"
VERSION=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["version"])' "$HERE/manifest.json")
docker run --rm --platform linux/arm64 --network none --cpus "${UT_BUILD_JOBS:-2}" --memory 4g --user "$(id -u):$(id -g)" -v "$STAGE/jars:/inputs:ro" -v "$STAGE/image:/output" -v "$ROOT/util/src/androidMain/ic_launcher-playstore.png:/icon.png:ro" "$UT_ARM_PACKAGER_IMAGE" /usr/lib/jvm/java-21-openjdk-arm64/bin/jpackage --type app-image --name coreapp --input /inputs --main-jar coreapp.jar --main-class coredevices.coreapp.MainKt --dest /output --app-version "$VERSION" --add-modules ALL-MODULE-PATH --icon /icon.png
file "$STAGE/image/coreapp/bin/coreapp" | grep -q 'ARM aarch64'
file "$STAGE/image/coreapp/lib/runtime/lib/server/libjvm.so" | grep -q 'ARM aarch64'
mkdir "$STAGE/payload"
cp -a "$STAGE/image/coreapp" "$STAGE/payload/coreapp"
for name in clickable.yaml manifest.json coreapp.apparmor coreapp.desktop coreapp-launch.sh; do cp "$HERE/$name" "$STAGE/payload/$name"; done
cp "$ROOT/LICENSE" "$STAGE/payload/coreapp/PORT-UPSTREAM-LICENSE"
python3 "$HERE/inventory-package.py" --root "$STAGE/payload/coreapp" --source "$ROOT" --packager "$UT_ARM_PACKAGER_IMAGE"
# renameat2 no-replace keeps a competing/new output intact.
python3 - "$STAGE/payload" "$UT_OUTPUT" <<'PY'
import ctypes,os,sys
libc=ctypes.CDLL(None,use_errno=True)
if libc.renameat2(-100,os.fsencode(sys.argv[1]),-100,os.fsencode(sys.argv[2]),1):raise OSError(ctypes.get_errno(),'atomic publication failed')
PY
cd "$UT_OUTPUT"
clickable build --arch arm64
