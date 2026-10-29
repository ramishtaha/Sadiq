package com.ramish.sadiq.core.testing

import com.ramish.sadiq.core.insight.NextActionSuggester
import com.ramish.sadiq.core.insight.Suggestion
import com.ramish.sadiq.core.insight.SuggestionContext
import com.ramish.sadiq.core.insight.SuggestionKey

/**
 * A [NextActionSuggester] that always returns [suggestion] and records the context it was given, so a
 * surface test can assert exactly when a line was chosen and for which window.
 *
 * @property suggestion the line to return.
 */
class FakeSuggester(
    var suggestion: Suggestion = Suggestion(SuggestionKey.GENERIC_WATER)
) : NextActionSuggester {

    /** The context the engine last passed in, if any. */
    var lastContext: SuggestionContext? = null

    /** Every context passed in, in call order. */
    val contexts: MutableList<SuggestionContext> = mutableListOf()

    override fun suggest(ctx: SuggestionContext): Suggestion {
        lastContext = ctx
        contexts += ctx
        return suggestion
    }
}
