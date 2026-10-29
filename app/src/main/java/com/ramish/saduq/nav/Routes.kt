package com.ramish.saduq.nav

/**
 * Every route string in one place. Features register their own [FeatureEntry] with one of these.
 * Adding a route here does not add a screen; it only reserves the name.
 */
object Routes {
    const val SKY = "sky"
    const val FOCUS = "focus"
    const val GUARD = "guard"
    const val WEEK = "week"

    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"
    const val HEALTH = "health"
    const val MODES = "modes"
    const val APPS = "apps"
    const val DOMAINS = "domains"
    const val PRAYER = "prayer"
    const val BEDTIME = "bedtime"
    const val MORNING = "morning"
    const val STRICT = "strict"
    const val RAMADAN = "ramadan"
    const val ABOUT = "about"
    const val TEST = "test"
}
