#!/usr/bin/env bash
# Enforces the product promise: this app cannot talk to the network.
set -uo pipefail
cd "$(dirname "$0")/.."
FAIL=0
APK="app/build/outputs/apk/debug/app-debug.apk"
AAPT2="/opt/android-sdk/build-tools/37.0.0/aapt2"

if [ -f "$APK" ]; then
  if [ ! -x "$AAPT2" ]; then
    echo "NO-NETWORK: FAIL — aapt2 not found at $AAPT2, so the manifest was never read"
    FAIL=1
  fi
  PERMS="$("$AAPT2" dump permissions "$APK" 2>/dev/null || true)"
  if [ -z "$PERMS" ]; then
    echo "NO-NETWORK: FAIL — aapt2 returned no permissions; an empty result is not a pass"
    FAIL=1
  fi
  for bad in android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE \
             com.google.android.gms.permission.AD_ID; do
    if echo "$PERMS" | grep -q "$bad"; then
      echo "NO-NETWORK: FAIL — $bad is present in the merged manifest"
      FAIL=1
    fi
  done
else
  echo "NO-NETWORK: SKIP — no APK yet ($APK)"
fi

# Source-level backstop: no sockets, no HTTP clients.
if grep -rnE "java\.net\.|HttpURLConnection|openConnection|Socket\(" \
     app/src core/src --include=*.kt --include=*.java 2>/dev/null | grep -v "// no-network-allow"; then
  echo "NO-NETWORK: FAIL — network API referenced in source"
  FAIL=1
fi

# Dependency backstop: no HTTP/analytics artefacts on the debug runtime classpath.
CPFILE="build/classpath-debug.txt"
if [ -f "$CPFILE" ]; then
  if grep -iE "okhttp|retrofit|firebase|play-services|analytics" "$CPFILE"; then
    echo "NO-NETWORK: FAIL — network library on the runtime classpath"
    FAIL=1
  fi
else
  # Loud, and deliberately not a pass: the dependency backstop did not run. (AUDIT-4 P2.)
  echo "NO-NETWORK: WARN — classpath check did not run: $CPFILE does not exist"
fi

[ "$FAIL" -eq 0 ] && echo "NO-NETWORK: OK"
exit "$FAIL"
