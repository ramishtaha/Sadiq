# Sadiq — overnight build report

_2026-10-30, 06:10 IST. Built from an Opus 5.5 plan, implemented by parallel agents, audited four times by Opus 5.5._
_Build `80d2609` · 728 unit tests · APK below._

## What it is

An Android version of the iOS app **StarPause — Stop Scrolling**, renamed **Sadiq (صادق)** and built in the visual
language of Rafiq. When you open an app you choose to guard, Sadiq asks one question — *"Honestly, do you need this
right now?"* — and either answer is accepted as true. It is offline by construction (no `INTERNET` permission), it never
shames, and it knows your prayer times.

## Install it

`https://learn.ramishtaha.tech/sadiq-80d2609.apk` (73.9 MB, debug-signed). The older bare
`…/sadiq-debug.apk` is Cloudflare-cached to a 03:33 build — use the versioned link.

```bash
adb install -r sadiq-b39e35b.apk
```

Then **Settings → Accessibility → Downloaded apps → Sadiq → Use Sadiq**, allow "Display pop-up windows while running in
the background", battery saver **No restrictions**, **Autostart** on, and lock Sadiq's card in Recents.

## Verified on the emulator tonight (not simulated, not the Poco)

Emulator `rafiq_test` (Android 16 / SDK 36, x86_64) on the laptop, driven over adb.

| What | Evidence |
|---|---|
| Installs and launches, no crash | `adb install` → Sky renders, 6 initializers up |
| The question appears over a guarded app | `Prompts surface via InterceptActivity`; screenshot `shots/fixed-quest.png` |
| **Bedtime copy is true** | At 04:43: *"Fajr is at 5:23 AM. That's 39 min from now."* — it used to say **24 h 39 min** (AUDIT-4 A4-08, fixed tonight) |
| Answering works | tap "Go to sleep" → `answer: Leave` → `surface gone, finishing` → home |
| **Focus visibly ends** | 5-min focus: tab timer ticked 4:54 → 1:56 with no engine input, then *"5 minutes, done. … +1 stars"* and the shade cleared (B-4, device-proven) |
| No network | `scripts/check-no-network.sh` → `NO-NETWORK: OK`; `aapt2 dump permissions` → no INTERNET / ACCESS_NETWORK_STATE / AD_ID |

## Numbers

- **728 unit tests, 0 failures** (core engine + economy + schedule, data, FGS policy, picker and focus view-models,
  forbidden-word scan).
- **4 Opus 5.5 audits.** AUDIT-4 found 3 P0 + a dozen P1 on the code that landed after AUDIT-3, including
  the overlay crash it proved with the emulator's own log; its P1s were then fixed in three rounds (`6432c48`,
  `b39e35b`, `ce86de6`, `80d2609`). A fifth pass was started at 06:10 for the close-out.
- APK `80d2609`, sha256 `a9715042a64c7a61…`, 73,931,732 B, signed `CN=Sadiq Debug, O=Ramish`.

## Fixed tonight, in this build

- **B-4** — a finished focus now emits `Effect.Live`, so the shade drops the dead countdown and its dead "End";
  the Focus tab ticks from a wall clock, and DONE keeps the length that ran instead of "0 minutes".
- **B-5** — "Save the rest" is gone from the notification until a Resume button exists; nothing promises saved time.
- **B-6 / A4-11** — the app picker renders un-ticks, saves the *full* selection (not the filtered rows), waits for the
  write before leaving the screen, keeps ticks made mid-edit, preserves each app's surface mode, and can un-guard the
  last app.
- **A4-04** — a TIME_UP that lands inside a prayer window keeps a Stay, capped at the 5-minute prayer limit (third audit
  asked; rule zero).
- **A4-08** — the bedtime line names the *next* Fajr instead of tomorrow's, so it no longer says "24 h 44 min" at 4 AM.
- **A4-09** — Back, gesture and screen-off on a focus guard no longer light a hidden LEAVE star.
- **A4-13** — the accessibility deep link now opens Sadiq's own toggle page, not the list.
- **A4-10** — "Focus complete" is posted only with the screen off and at least one star lit; no more "+0 stars" and no
  morning interruption while he is holding the phone.
- **A4-14** — after HyperOS kills the process, "connected" stops assuming true: the first 10 s are a grace window, then
  an unreported binding reads false, and `onDestroy` reports itself.
- **A focus ending while its own question is open** now rewrites that question instead of leaving "You're focusing" up
  with a dead button; `endFocus` keeps a running grant's countdown.
- **A4-12** — a queued "Think it over" app-list change now carries the removals it meant, and is rebased onto the
  current list when it lands, so an app he added while it waited is no longer silently un-guarded; a row with no
  record merges instead of replacing.
- **A4-15** — the wizard hides the tab bar, keeps both O5b flags in `UiPrefs` so a HyperOS kill mid-Settings does not
  drop the step, renders the exact HyperOS menu paths, and gives O5 a way into O5b without Back.
- **A4-16 (B-7)** — the Sunday note is now an invitation ("Your week is ready. Tap to see it.") with no usage total,
  and is not posted at all for an approximate week; the Week screen withholds the measured headline when the numbers
  come from Sadiq's own records.
- **`check-no-network.sh` fails loudly** when aapt2 is missing or returns nothing — an empty permission dump used to
  read as a pass. (The classpath backstop still never runs; see below.)
- **Copy truth** — no screen claims a "backup layer" that is switched off.

## What is NOT done — read this before relying on it

1. **The overlay fallback is implemented but OFF** (AUDIT-3 G-13 / AUDIT-4 A4-01). It drew on its first real device test
   and crashed the process on its first frame; the cause is fixed (owners on the window root) but it is **not re-tested**,
   and its other findings (A4-02 home gesture, A4-03 incoming calls, A4-05 Back, A4-06 double-draw) are still open.
   So if HyperOS refuses the background activity start, **no question appears** — the gap that existed before it.
   With the host off, the presenter no longer asks for it, counts it, or promises it in copy.
2. **HyperOS is unverified.** Autostart, battery saver, "lock in Recents" and the MIUI pop-up permission are written
   from Xiaomi's documented menus; none has been tapped on the Poco F7. The onboarding now shows the pop-ups step unless
   it *knows* the permission is on (B-1), so you can see what to fix.
3. **Still open in AUDIT-4:** A4-02…A4-07 (overlay behaviour — home gesture, incoming call, Back, double-draw, latency),
   the debug receiver's missing permission, the O9 fallback order, the health-row tones, the notifications runtime
   request, and the dependency backstop in `check-no-network.sh` (it prints a loud WARN today because
   `build/classpath-debug.txt` is never generated). Each has a concrete fix in `audits/AUDIT-4.md`.
4. **Fixed code-only this cycle, not device-tested:** A4-12 (rebased queued app list), A4-15 (wizard tab bar, persisted
   flags, OEM paths), A4-16 (Sunday copy + approximate headline), plus the earlier A4-10, the stale FOCUS_GUARD
   surface, `endFocus`'s countdown, and A4-14.
5. **Not tested on your phone.** The accessibility service, the shade, the star timing and everything above were proven
   on the emulator only.

## Where everything is

- Code: `/root/saduq` (branch `main`, HEAD `80d2609`)
- Plan + voice: `/root/starpause-brain/plans/PLAN-v2.md`, `NAME-ADDENDUM-v1.md`
- Audits: `/root/starpause-brain/audits/AUDIT-1.md` … `AUDIT-4.md` (`AUDIT-5.md` if the close-out pass finished)
- Runbook / cursor: `/root/starpause-brain/OVERNIGHT.md`, `STATE.md`
- Screenshots: `/root/starpause-brain/shots/`
- Delivery: `/root/starpause-brain/delivery/sadiq-80d2609.apk`
