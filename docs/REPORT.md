# Sadiq — overnight build report

_2026-10-30, ~03:50 IST. Built from an Opus 5.5 plan, implemented by parallel agents, audited three times by Opus 5.5._

## What it is

An Android version of the iOS app **StarPause — Stop Scrolling**, renamed **Sadiq (صادق)** and built in the visual
language of Rafiq. When you open an app you choose to guard, Sadiq asks one question — *"Honestly, do you need this
right now?"* — and either answer is accepted as true. It is offline by construction (no `INTERNET` permission), it never
shames, and it knows your prayer times.

## Verified on a real device (not simulated)

Emulator `rafiq_test` (Android 36, x86_64) on Ramish's laptop, driven over adb.

| What | Evidence |
|---|---|
| Installs and launches | `adb install` → app runs, no crash |
| Onboarding | Welcome → Apps → Mode → Times → Try → Finish walked end to end |
| The question appears over a guarded app | `topResumedActivity=com.ramish.sadiq/.intercept.InterceptActivity` |
| The daytime surface | *"Honestly, do you need this right now?"* / *"No, take me home"* (★) / *"Yes, I need it"* / *"Free scroll · 2 min"* / *"Steady · … min before rest"* |
| The bedtime surface | *"It's 2:33 AM. Time to sleep."* / *"Fajr is at 5:23 AM. That's 2 h 49 min from now."* / *"Go to sleep"* / *"Wind down with rain · 20 min"* / *"One minute · 3 left"* |
| The prayer surface | *"It's Jumu'ah."* / *"Pray first? Chrome will still be here."* / *"I'll pray first"* / *"Yes, I need it"* / *"At prayer time, the choice is always yours."* |
| **The star economy** | Tap "No, take me home" → `kindles: [pending]` → 3 minutes later `kindles: []`, `litLeave: 1`, `litTotal: 3` |
| The Sky | dark starfield, "YOUR SKY 3 +3 tonight", four tabs (Sky · Focus · Guards · Week) |
| No network | `aapt2 dump permissions` → no INTERNET / ACCESS_NETWORK_STATE / AD_ID |

Screenshots: `/root/starpause-brain/shots/final-*.png`.

## Numbers

- **652 unit tests, 0 failures** (core engine + economy + schedule, data codecs, FGS policy, forbidden-word scan).
- **3 Opus 5.5 audits**: AUDIT-1 (1 P0 + 18 P1), AUDIT-2 (4 P0 + 9 P1), AUDIT-3 (7 release blockers, no crash and no
  privacy leak; blockers B-1, B-2, B-3, B-6 fixed in this build).
- APK (final, commit `36f1184`): `/root/starpause-brain/delivery/sadiq-debug.apk` (73 MB, debug) —
  also served at https://learn.ramishtaha.tech/sadiq-debug.apk. Signed with
  `CN=Sadiq Debug, O=Ramish`, SHA-256 `DA:AA:E7:AB:…:A2:32:47:34`.

## What is in the build

**M1** — interception (accessibility service + full-screen question), Leave/Stay/free scroll/one minute, three modes
(Gentle 15/15 · Steady 10/30 · Firm 5/60), prayer-aware soft pauses, Bedtime until Fajr, the morning window, focus
sessions (5–90 min, procedural rain/brown/night-pad sound), the star sky, onboarding with the HyperOS wizard, settings,
a health check, and a zero-network guarantee.

**M2 (landed tonight, newer and less tested)** — the Week tab (weekly review), "Think it over" (strict mode with a
10-minute delay on loosening; now reachable inside the Guards tab), and a Ramadan card. The Guard and Settings screens
now render their contributed sections, so those features are actually reachable.

## What is NOT done — read this before relying on it

1. **The overlay fallback does not exist.** If HyperOS refuses the background activity launch, no question appears.
   The engine then sits in `PROMPTING` and the next app switch can pay a star. Audit-3 B-1 (fixed) makes the onboarding
   show the pop-ups step unless it *knows* the permission is on — but the underlying gap remains.
2. **HyperOS is unverified.** The OEM wizard, autostart, battery-saver and "lock in recents" steps are written from
   Xiaomi's documented menus; none has been tapped on the Poco F7.
3. **Focus does not visibly end** (audit-3 B-4) — the timer is computed from engine inputs, so it can look frozen, and
   the shade can keep a dead "End" button. The session itself is correct.
4. **"Save the rest"** (B-5) saves grant time that the engine will not give back on reopen; the notification promises
   more than the engine delivers.
5. **The Week tab totals 0** if usage access was skipped (B-7).
6. **Onboarding completion** now writes through `NonCancellable`, so finishing the wizard sticks — verified by code, and
   the wizard itself was walked end to end; the last write was not re-tested after the fix (the cron will).
7. Eleven AUDIT-2 P1/P2 items were fixed tonight; G-10…G-13 were addressed, but AUDIT-3 lists the ones that were not.

## Install

```bash
adb install -r sadiq-debug.apk
```
Then **Settings → Accessibility → Downloaded apps → Sadiq → Use Sadiq**, allow "Display pop-up windows while running in
the background", set battery saver to **No restrictions**, enable **Autostart**, and lock the app in Recents.

## Where everything is

- Code: `/root/saduq` (branch `main`, 9 commits)
- Plan + voice: `/root/starpause-brain/plans/PLAN-v2.md`, `NAME-ADDENDUM-v1.md`
- Audits: `/root/starpause-brain/audits/AUDIT-1.md`, `AUDIT-2.md`, `AUDIT-3.md`
- Runbook / cursor: `/root/starpause-brain/OVERNIGHT.md`, `STATE.md`
- Screenshots: `/root/starpause-brain/shots/`
