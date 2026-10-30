# Sadiq — overnight build report

_2026-10-30, 06:45 IST. Built from an Opus 5.5 plan, implemented by parallel agents, audited five times by Opus 5.5._
_Deliverable: build `c0c6a76` · 728 unit tests · debug APK below._

## What it is

An Android version of the iOS app **StarPause — Stop Scrolling**, renamed **Sadiq (صادق)** and built in the visual
language of Rafiq. When you open an app you have chosen to guard, Sadiq asks one question — *"Honestly, do you need
this right now?"* — and either answer is accepted as true. It is offline by construction (no `INTERNET` permission),
it never shames, and it knows your prayer times.

## Install it

`https://learn.ramishtaha.tech/sadiq-c0c6a76.apk` (73.9 MB, debug-signed). The bare `…/sadiq-debug.apk` is
Cloudflare-cached; use the versioned link.

```bash
adb install -r sadiq-c0c6a76.apk
```

Then **Settings → Accessibility → Downloaded apps → Sadiq → Use Sadiq**, allow "Display pop-up windows while running
in the background", battery saver **No restrictions**, **Autostart** on, and lock Sadiq's card in Recents.

A **release** APK also exists at `app/build/outputs/apk/release/app-release.apk` (3.99 MB, minified). It has been
built but **not installed or run** — do not rely on it until someone does.

## Verified on the emulator tonight (not simulated, not the Poco)

Emulator `rafiq_test` (Android 16 / SDK 36, x86_64) on the laptop, driven over adb.

| What | Evidence |
|---|---|
| Installs and launches, no crash | `adb install` → Sky renders, 6 initializers up |
| Installs and launches **this build** | `c0c6a76` installed on `emulator-5554`; MainActivity resumed; logcat has no `FATAL` |
| The question appears over a guarded app | `Prompts surface via InterceptActivity`; screenshot `shots/fixed-quest.png` |
| **Bedtime copy is true** | at 04:43: *"Fajr is at 5:23 AM. That's 39 min from now."* — it used to say **24 h 39 min** (A4-08) |
| Answering works | tap "Go to sleep" → `answer: Leave` → `surface gone, finishing` → home |
| **Focus visibly ends** | 5-min focus: tab timer ticked 4:54 → 1:56 with no engine input, then *"5 minutes, done. … +1 stars"* and the shade cleared (B-4) |
| Guards renders | 2 apps, three modes, protected times, Think it over row (UI dump, no error) |
| Week renders | first-week empty state; the approximate state now says it cannot see app time instead of "no time recorded" |
| No network | `scripts/check-no-network.sh` → `NO-NETWORK: OK`; `aapt2` → no INTERNET / ACCESS_NETWORK_STATE / AD_ID |

## Numbers

- **728 unit tests, 0 failures** (core engine + economy + schedule, data, FGS policy, picker, focus and week
  view-models, forbidden-word scan). 62 XML reports, all from the final run.
- **5 Opus 5.5 audits.** AUDIT-4 (04:50) found 3 P0 + 12 P1; its P1s were fixed in four rounds (`6432c48`, `b39e35b`,
  `ce86de6`, `80d2609`). AUDIT-5 (06:28) confirmed every AUDIT-4 P0 is closed, found **no new P0**, and left the P1s
  listed below.
- Debug APK `c0c6a76`, sha256 `0e5dd5cf948d19fa…`, 73,931,732 B, signed `CN=Sadiq Debug, O=Ramish`.

## Fixed tonight, in this build

- **B-4** — a finished focus emits `Effect.Live`, so the shade drops the dead countdown and its dead "End"; the Focus
  tab ticks from a wall clock; DONE keeps the length that ran instead of "0 minutes". Device-proven.
- **B-5** — "Save the rest" removed from the notification until a Resume exists; nothing promises saved time.
- **B-6 / A4-11** — the picker renders un-ticks, saves the *full* selection, waits for the write before leaving, keeps
  ticks made mid-edit, preserves each app's surface mode, and can un-guard the last app.
- **A4-04** — a TIME_UP inside a prayer window keeps a Stay, capped at the 5-minute prayer limit (rule zero).
- **A4-08** — the bedtime line names the *next* Fajr, so it no longer says "24 h 39 min" at 4 AM.
- **A4-09** — Back, gesture and screen-off on a focus guard no longer light a hidden LEAVE star.
- **A4-10** — "Focus complete" is posted only with the screen off and at least one star lit; no "+0 stars".
- **A4-12** — a queued "Think it over" app-list change carries the removals it meant and is rebased onto the current
  list when it lands, so an app added while it waited is not silently un-guarded; a legacy row merges, never replaces.
- **A4-13** — the accessibility deep link opens Sadiq's own toggle page.
- **A4-14** — accessibility "connected" no longer defaults true after a kill: 10 s startup grace, then unreported reads
  false; `onDestroy` reports itself.
- **A4-15** — the wizard hides the tab bar, keeps both O5b flags in `UiPrefs` (survives a HyperOS kill mid-Settings),
  renders the exact HyperOS menu paths, and gives O5 a way into O5b without Back.
- **A4-16 (B-7)** — the Sunday note is an invitation ("Your week is ready. Tap to see it.") with no usage total, and is
  not posted for an approximate week; the Week screen withholds the measured headline when the numbers are records.
- **A5-06** — an approximate Week no longer says "No guarded app time was recorded"; it says Sadiq can't see app time
  without usage access, and the hour band is hidden.
- **A5-08 (partial)** — TalkBack labels no longer claim Sadiq *closes* apps ("Takes you home."), and the suggestion
  that invented "the message you have been avoiding" is now "Write one short reply."
- **`check-no-network.sh`** fails when aapt2 is missing or returns an empty permission dump, and the source grep covers
  `.java`. The dependency backstop prints a loud WARN because `build/classpath-debug.txt` is never generated.
- **Copy truth** — no screen claims a "backup layer" that is switched off.

## What is NOT done — read this before relying on it

1. **The overlay fallback is implemented but OFF** (A4-01/G-13). It crashed the process on its first real draw; the
   cause is fixed but it was never re-tested, and A4-02 (home gesture), A4-03 (incoming call), A4-05 (Back), A4-06
   (double-draw) are still open. So if HyperOS refuses the background activity start, **no question appears** — the
   same gap as before. With the host off, nothing asks for it, counts it, or promises it in copy.
2. **AUDIT-5 P1s still open** (each has file:line and a fix in `audits/AUDIT-5.md`): A5-01/02/03 (two queued app edits
   can still fight over the same package), A5-04 (a refused launch is silent and the O7 copy calls the permission
   cosmetic), A5-05 (O5b has no visible way back to O5), A5-07 ("on but not running" never reaches Sky; `asleep()` has
   no caller), A5-08's remaining copy lines, A5-09 (Room schema not exported, the silent machine-key fallback,
   `ForbiddenWordsTest` incomplete).
3. **HyperOS is unverified.** Autostart, battery saver, "lock in Recents" and the MIUI pop-up permission are written
   from Xiaomi's documented menus; none has been tapped on the Poco F7. The onboarding shows the pop-ups step unless it
   *knows* the permission is on (B-1), so you can see what to fix.
4. **Release build unrun.** `isMinifyEnabled = true` is now in the tree and a 3.99 MB release APK was produced, but it
   has not been installed, and `versionCode` is still 1.
5. **Not tested on your phone.** The accessibility service, the shade, star timing and everything above were proven on
   the emulator only.

## Where everything is

- Code: `/root/saduq` (branch `main`, HEAD `c0c6a76`)
- Plan + voice: `/root/starpause-brain/plans/PLAN-v2.md`, `NAME-ADDENDUM-v1.md`
- Audits: `/root/starpause-brain/audits/AUDIT-1.md` … `AUDIT-5.md`
- Runbook / cursor: `/root/starpause-brain/OVERNIGHT.md`, `STATE.md`
- Screenshots + UI dumps: `/root/starpause-brain/shots/`
- Delivery: `/root/starpause-brain/delivery/sadiq-c0c6a76.apk`
