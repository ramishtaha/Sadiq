# Sadiq (صادق) — Android

A quiet interruption layer for one person: when you open an app you chose to guard, Sadiq asks **"Honestly, do you
need this right now?"**, and either answer is accepted as true. It never blocks what Android cannot block, it never
shames, and it has **no internet permission** — enforced in the manifest, checked by a script, and asserted by a test.

Built from a design by Claude Opus 5.5 (plan, architecture, two audits) and implemented by Hermes with parallel
subagents. Visual language derived from Ramish's Rafiq app.

## What it does today (M1)

| Area | Behaviour |
|---|---|
| Interception | An `AccessibilityService` sees the foreground app; `InterceptActivity` shows the question over it, or the engine falls back to the accessibility overlay. |
| The four answers | Leave (earns a star after 3 quiet minutes) · Stay for 2/5/10/15 min · free scroll (1 star = 1 minute today) · one minute (3/day) |
| Guardian modes | Gentle 15/15 · Steady 10/30 · Firm 5/60 — one shared budget across every guarded app |
| Prayer awareness | Soft pause at each prayer (Stay always offered, ≤5 min); **Bedtime** from 22:30 (or Isha+30) until Fajr; your **morning** protected until 09:30 |
| Focus | 5/15/25/45/60/90-minute sessions with a star formula, optional procedural sound (rain / brown / night pad), foreground-service timer |
| Sky | A canvas starfield that only grows; a star means "a moment you did what you meant" |
| Onboarding | One action per screen, including the HyperOS/Hyper Island permission wizard |
| Privacy | No `INTERNET`, no `ACCESS_NETWORK_STATE`, no analytics, `allowBackup=false` |

Planned but **not** in this build: the Week tab (weekly review), Shorts/Reels-only guarding, browser-domain guarding,
the home-screen widget, strict mode and Ramadan mode (WP-23…WP-27 in the plan).

## Install

```bash
adb install -r sadiq-debug.apk
```
Then **Settings → Accessibility → Downloaded apps → Sadiq → Use Sadiq** (on HyperOS: also allow
"Display pop-up windows while running in the background", set battery saver to "No restrictions", enable Autostart,
and lock the app in Recents).

The APK on this machine is signed by the shared debug cert:
`/root/.sadiq/keys/sadiq-debug.jks` (alias `sadiq`), SHA-256
`DA:AA:E7:AB:90:67:82:EA:1A:F1:7C:65:91:62:AF:04:E9:3D:8E:77:F0:35:06:B8:94:54:A5:19:A2:32:47:34`.
Keep that keystore: installing over this package with a different cert forces an uninstall, which erases the sky.

## Build / test

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-arm64 ANDROID_HOME=/opt/android-sdk
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
scripts/check-no-network.sh        # proves the app cannot reach the network
scripts/device-test.sh             # installs on the adb device and launches
scripts/device-e2e.sh              # drives the real interception path
```

## Layout

- `:core` — pure Kotlin, zero Android imports: the guard state machine (`GuardEngine`, 35 transitions), the star
  economy, the prayer/sleep-gate schedule (Adhan), insights, the starfield layout, sound synthesis, contract types.
- `:app` — Compose UI, the accessibility service, the interception activity, notifications, Room + DataStore, the
  HyperOS permission helpers, and the Hilt graph.

Design documents live outside the repo: `/root/starpause-brain/plans/PLAN-v2.md` (the contract),
`NAME-ADDENDUM-v1.md` (voice and naming), and `audits/AUDIT-1.md` + `audits/AUDIT-2.md` (Opus 5.5's findings).

## The voice rule

Sadiq never says *lie*, *promise*, *cheat*, *sin*, *locked* (outside the OS's own menu labels), or anything that
accuses. `ForbiddenWordsTest` fails the build if any user-facing string does.
