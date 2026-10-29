#!/usr/bin/env bash
# Sadiq end-to-end test on the laptop AVD: the real interception path, not a mock.
# Requires: debug APK built, emulator-5554 online, accessibility enabled.
set -uo pipefail
PKG=com.ramish.sadiq
TARGET=${TARGET_PKG:-com.android.chrome}
SHOTDIR=/root/starpause-brain/shots
mkdir -p "$SHOTDIR"
SSH="ssh -o BatchMode=yes -o ConnectTimeout=10 -p 2224 ramishtaha@127.0.0.1"
ADB='%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe'

r() { timeout 45 $SSH "$@" 2>&1 | tr -d '\r'; }
dbg() { r "$ADB shell am broadcast -a $PKG.DEBUG -e $*" | tail -1; }
shot() {
  r "$ADB exec-out screencap -p > C:\Users\ramishtaha\shot.png"
  scp -P 2224 -o BatchMode=yes ramishtaha@127.0.0.1:C:/Users/ramishtaha/shot.png "$SHOTDIR/$1.png" >/dev/null 2>&1 \
    && echo "shot $1" || echo "shot $1 FAILED"
}
# Tap a Compose element by its text, using the accessibility tree rather than guessed coordinates.
tap_text() {
  r "$ADB shell uiautomator dump /sdcard/ui.xml" >/dev/null
  scp -P 2224 -o BatchMode=yes ramishtaha@127.0.0.1:C:/Users/ramishtaha/../sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  r "$ADB pull /sdcard/ui.xml C:\Users\ramishtaha\ui.xml" >/dev/null
  scp -P 2224 -o BatchMode=yes ramishtaha@127.0.0.1:C:/Users/ramishtaha/ui.xml /tmp/ui.xml >/dev/null 2>&1
  local coords
  coords=$(python3 - "$1" <<'PY'
import re,sys
want=sys.argv[1].lower()
try: xml=open('/tmp/ui.xml',encoding='utf-8',errors='ignore').read()
except Exception: print(""); raise SystemExit
best=None
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    txt=m.group(1).strip().lower()
    if want in txt:
        x1,y1,x2,y2=map(int,m.groups()[1:])
        best=((x1+x2)//2,(y1+y2)//2); break
print(f"{best[0]} {best[1]}" if best else "")
PY
)
  if [ -n "$coords" ]; then
    echo "tap '$1' at $coords"
    r "$ADB shell input tap $coords"
    return 0
  fi
  echo "tap '$1' NOT FOUND"
  return 1
}

say() { echo; echo "== $*"; }

say "preconditions"
r "$ADB shell settings get secure enabled_accessibility_services"
r "$ADB shell settings put secure enabled_accessibility_services $PKG/$PKG.guard.service.GuardAccessibilityService"
r "$ADB shell settings put secure accessibility_enabled 1"

say "arm the guard for $TARGET"
dbg "cmd guard -e pkg $TARGET"
dbg "cmd dump"

say "run the real interception path via selftest"
dbg "cmd selftest -e pkg $TARGET"
sleep 5
shot "e2e-01-question"

say "tap the honest answer (No, take me home)"
tap_text "No, take me home" || r "$ADB shell input keyevent KEYCODE_BACK"
sleep 3
shot "e2e-02-after-leave"

say "engine state after leaving"
r "$ADB logcat -d -t 300" | grep -E "SQ/(Dump|State)|EngineState|stars=" | tail -6

say "wait for the star to light (kindle = 3 min)"
sleep 190
dbg "cmd dump"
sleep 2
r "$ADB logcat -d -t 200" | grep -E "StarLit|stars|Kindle" | tail -8

say "screenshots"
ls -1 "$SHOTDIR"/e2e-*.png 2>/dev/null
