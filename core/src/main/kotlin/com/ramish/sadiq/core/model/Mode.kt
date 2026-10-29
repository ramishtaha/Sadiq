package com.ramish.sadiq.core.model

/**
 * How much guarded time a day allows, and how long the feeds rest once it is spent.
 *
 * The two numbers always move together: a smaller budget buys a longer cooldown, so a stricter
 * mode never becomes a tighter loop of questions.
 *
 * @property budgetMin minutes of guarded screen time available per logical day.
 * @property cooldownMin minutes without guarded use before an exhausted budget refills.
 */
enum class Mode(val budgetMin: Int, val cooldownMin: Int) {
    GENTLE(15, 15),
    BALANCED(10, 30),
    HARDCORE(5, 60)
}

/** Whether a guarded app is covered everywhere or only in its short-video surface. */
enum class SurfaceMode {
    WHOLE_APP,
    SHORTS_ONLY
}

/** The prayer-time calculation method. One value per Adhan `CalculationMethod`. */
enum class CalcMethod {
    KARACHI,
    MUSLIM_WORLD_LEAGUE,
    EGYPTIAN,
    UMM_AL_QURA,
    DUBAI,
    QATAR,
    KUWAIT,
    SINGAPORE,
    NORTH_AMERICA
}

/** The Asr shadow rule. */
enum class Madhab {
    HANAFI,
    SHAFI
}

/** How Ramadan mode is decided. [AUTO_SUGGEST] only offers it from a home card; it never turns itself on. */
enum class RamadanMode {
    OFF,
    ON,
    AUTO_SUGGEST
}
