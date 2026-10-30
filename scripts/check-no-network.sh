#!/usr/bin/env bash
# Enforces the product promise: this app cannot talk to the network.
#
# A missing APK is not a pass. A classpath check that never ran is not a pass either — that is how
# AUDIT-5 A5-12 found this script printing OK while checking nothing.
set -uo pipefail
cd "$(dirname "$0")/.."
FAIL=0
AAPT2="/opt/android-sdk/build-tools/37.0.0/aapt2"
BAD_PERMS="android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE com.google.android.gms.permission.AD_ID"

# Every APK the build has produced, debug and release. At least one must exist.
mapfile -t APKS < <(ls app/build/outputs/apk/*/*.apk 2>/dev/null || true)
if [ "${#APKS[@]}" -eq 0 ]; then
  echo "NO-NETWORK: FAIL — no APK built, so nothing was verified. Build one first."
  exit 1
fi

if [ ! -x "$AAPT2" ]; then
  echo "NO-NETWORK: FAIL — aapt2 not found at $AAPT2, so no manifest could be read"
  exit 1
fi

for APK in "${APKS[@]}"; do
  PERMS="$("$AAPT2" dump permissions "$APK" 2>/dev/null || true)"
  if [ -z "$PERMS" ]; then
    echo "NO-NETWORK: FAIL — $APK: aapt2 returned no permissions; an empty result is not a pass"
    FAIL=1
    continue
  fi
  for bad in $BAD_PERMS; do
    if echo "$PERMS" | grep -q "$bad"; then
      echo "NO-NETWORK: FAIL — $APK contains $bad"
      FAIL=1
    fi
  done
  [ "$FAIL" -eq 0 ] && echo "NO-NETWORK: $APK clean"
done

# Source-level backstop: no sockets, no HTTP clients.
if grep -rnE "java\.net\.|HttpURLConnection|openConnection|Socket\(" \
     app/src core/src --include=*.kt --include=*.java 2>/dev/null | grep -v "// no-network-allow"; then
  echo "NO-NETWORK: FAIL — network API referenced in source"
  FAIL=1
fi

# Dependency backstop: no HTTP/analytics artefacts on the runtime classpath. This file is written by
# scripts/verify.sh; if it is missing the check did not run, which is a failure, not a warning.
CPFILE="build/classpath-debug.txt"
if [ -f "$CPFILE" ] && [ -s "$CPFILE" ]; then
  if grep -iE "okhttp|retrofit|firebase|play-services|analytics" "$CPFILE"; then
    echo "NO-NETWORK: FAIL — network library on the runtime classpath"
    FAIL=1
  fi
else
  echo "NO-NETWORK: FAIL — classpath check did not run: run scripts/verify.sh, which writes $CPFILE"
  FAIL=1
fi

[ "$FAIL" -eq 0 ] && echo "NO-NETWORK: OK"
exit "$FAIL"
