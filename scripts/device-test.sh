#!/usr/bin/env bash
# Sadiq on-device smoke test — runs against the AVD on Ramish's laptop over the SSH tunnel.
# Usage: scripts/device-test.sh [path/to/app-debug.apk]
set -uo pipefail
APK="${1:-/root/saduq/app/build/outputs/apk/debug/app-debug.apk}"
PKG="${SADIQ_PKG:-com.ramish.sadiq}"
SVC="${SADIQ_SVC:-com.ramish.sadiq/com.ramish.sadiq.guard.service.GuardAccessibilityService}"
SHOTDIR=/root/starpause-brain/shots
mkdir -p "$SHOTDIR"
SSH="ssh -o BatchMode=yes -o ConnectTimeout=10 -p 2224 ramishtaha@127.0.0.1"
ADB='%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe'
WIN_APK='C:\Users\ramishtaha\sadiq.apk'

say() { echo "== $*"; }
r() { timeout 40 $SSH "$@" 2>&1 | tr -d '\r'; }

say "device"
r "$ADB devices" | sed -n '2p'

say "push + install"
scp -P 2224 -o BatchMode=yes "$APK" ramishtaha@127.0.0.1:C:/Users/ramishtaha/sadiq.apk >/dev/null 2>&1 || { echo "PUSH FAILED"; exit 1; }
r "$ADB install -r $WIN_APK" | tail -2

say "grant permissions"
r "$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS" || true
r "$ADB shell settings put secure enabled_accessibility_services $SVC"
r "$ADB shell settings put secure accessibility_enabled 1"
r "$ADB shell appops set $PKG android:system_alert_window allow" || true
r "$ADB shell cmd deviceidle whitelist +$PKG" || true
r "$ADB shell appops set $PKG PACKAGE_USAGE_STATS allow" || true

say "launch"
r "$ADB shell am force-stop $PKG"
r "$ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1" | tail -1
sleep 6

shot() { # shot <name>
  r "$ADB exec-out screencap -p > C:\Users\ramishtaha\shot.png"
  scp -P 2224 -o BatchMode=yes ramishtaha@127.0.0.1:C:/Users/ramishtaha/shot.png "$SHOTDIR/$1.png" >/dev/null 2>&1 \
    && echo "shot: $SHOTDIR/$1.png" || echo "shot failed: $1"
}

say "state dump"
r "$ADB shell dumpsys accessibility | grep -iE 'enabled|sadiq|sadiq' | head -8"
r "$ADB shell pidof $PKG" | sed 's/^/pid: /'

shot "01-launch"

say "crashes since launch"
r "$ADB logcat -d -t 400 | grep -E 'FATAL EXCEPTION|AndroidRuntime.*$PKG|ANR in' | tail -20"

say "done — inspect $SHOTDIR"
