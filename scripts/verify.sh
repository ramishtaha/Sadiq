#!/usr/bin/env bash
# The one verification gate. Usage: scripts/verify.sh [WP-nn]
set -uo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-arm64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export GRADLE_OPTS="${GRADLE_OPTS:--Xmx3g -XX:MaxMetaspaceSize=768m}"
FAIL=0

echo "== gradle: core tests =="
scripts/gradle-slot.sh ./gradlew :core:test --console=plain || FAIL=1

echo "== gradle: app unit tests =="
scripts/gradle-slot.sh ./gradlew :app:testDebugUnitTest --console=plain || FAIL=1

echo "== gradle: assembleDebug =="
scripts/gradle-slot.sh ./gradlew :app:assembleDebug --console=plain || FAIL=1

echo "== no-network guard =="
scripts/check-no-network.sh || FAIL=1

if [ "${1:-}" != "" ]; then
  echo "== ownership: $1 =="
  scripts/check-ownership.sh "$1" || FAIL=1
fi

if [ "$FAIL" -ne 0 ]; then
  echo "VERIFY: FAILED"
  exit 1
fi
echo "VERIFY: OK"
