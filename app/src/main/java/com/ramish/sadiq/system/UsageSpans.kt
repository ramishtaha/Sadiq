package com.ramish.sadiq.system

import com.ramish.sadiq.core.engine.UsageSpan

/**
 * What one usage event meant, in Sadiq's terms.
 *
 * `UsageStatsManager` reports a dozen event types; only these matter for reconstructing foreground
 * time. [OTHER] exists so a new platform event can never be mistaken for a resume or a pause.
 */
enum class UsageEventKind {
    /** An activity of the package came to the front. */
    RESUMED,

    /** An activity of the package went to the background. */
    PAUSED,

    /** The package's activity was stopped. Treated as a pause. */
    STOPPED,

    /** The screen turned off; whatever was in front stopped being used. */
    SCREEN_OFF,

    /** The screen turned on. Not a boundary: nothing is in front until something resumes. */
    SCREEN_ON,

    /** The device shut down; treated like the screen going off. */
    SHUTDOWN,

    /** Any event Sadiq does not act on. */
    OTHER,
}

/**
 * One usage event, flattened out of `UsageEvents.Event`.
 *
 * @property packageName the package the event belongs to, or null for a device-level event.
 * @property kind what happened.
 * @property wallMs when it happened, in wall milliseconds.
 */
data class UsageEvent(
    val packageName: String?,
    val kind: UsageEventKind,
    val wallMs: Long,
)

/**
 * Rebuilds foreground sessions from a stream of usage events.
 *
 * Reconciliation after the process was killed needs sessions, not events: the engine charges
 * `UsageSpan`s against the budget and marks spans inside protected windows. This fold is pure and
 * lives here rather than in the Android reader so its three hard cases can be tested without a
 * phone:
 *
 * 1. **A resume with a pause.** The normal pair, closed at the pause.
 * 2. **A resume with no pause.** The session was still running when the query ended, so it is
 *    closed at [fold]'s `toWall`. Without this, the last session of every query would vanish — and
 *    it is exactly the session that was in progress when the process died.
 * 3. **Screen off.** Android does not always deliver a pause for the app that was in front when the
 *    screen went off, and a session that runs through the night would otherwise be charged as hours
 *    of use. The screen-off event is the authoritative end of the session.
 *
 * A pause with no matching resume is ignored: the session began before the query window, and
 * charging it would invent usage Sadiq never saw.
 */
object UsageSpans {

    /**
     * Folds [events] into ordered, non-overlapping sessions.
     *
     * Events may arrive unsorted (two queries concatenated, an OEM that reorders), so they are
     * sorted by time first; the sort is stable, which keeps a resume/pause pair at the same
     * millisecond in the order the platform reported it.
     *
     * @param events the raw events, in any order.
     * @param toWall the instant the query ended; an unclosed session is closed here.
     * @param minSpanMs sessions shorter than this are dropped as noise, e.g. a launcher blip
     *   between two apps. Zero keeps everything with a positive length.
     * @return sessions oldest first, each clipped to `[start, toWall]`.
     */
    fun fold(events: List<UsageEvent>, toWall: Long, minSpanMs: Long = 0L): List<UsageSpan> {
        if (events.isEmpty()) return emptyList()

        val spans = ArrayList<UsageSpan>(events.size / 2 + 1)
        val threshold = minSpanMs.coerceAtLeast(0L)

        fun close(packageName: String?, startWall: Long, endWall: Long) {
            if (packageName == null) return
            val end = minOf(endWall, toWall)
            if (end <= startWall) return
            if (end - startWall < threshold) return
            spans.add(UsageSpan(packageName = packageName, startWall = startWall, endWall = end))
        }

        var openPackage: String? = null
        var openStart: Long = 0L

        for (event in events.sortedBy { it.wallMs }) {
            when (event.kind) {
                UsageEventKind.RESUMED -> {
                    val packageName = event.packageName ?: continue
                    // A repeat resume of the same app is the platform being noisy; the session is
                    // continuous and its start is the first resume.
                    if (packageName == openPackage) continue
                    close(openPackage, openStart, event.wallMs)
                    openPackage = packageName
                    openStart = event.wallMs
                }

                UsageEventKind.PAUSED, UsageEventKind.STOPPED -> {
                    if (openPackage != null && event.packageName == openPackage) {
                        close(openPackage, openStart, event.wallMs)
                        openPackage = null
                    }
                }

                UsageEventKind.SCREEN_OFF, UsageEventKind.SHUTDOWN -> {
                    close(openPackage, openStart, event.wallMs)
                    openPackage = null
                }

                UsageEventKind.SCREEN_ON, UsageEventKind.OTHER -> Unit
            }
        }

        // Still in front when the query ended: the session is real and open at the end.
        if (openPackage != null) close(openPackage, openStart, maxOf(toWall, openStart))

        return spans
    }
}
