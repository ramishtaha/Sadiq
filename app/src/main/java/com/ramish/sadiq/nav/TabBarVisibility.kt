package com.ramish.sadiq.nav

/**
 * Whether the tab bar is drawn for the current route.
 *
 * The wizard is one uninterrupted task, and the bar used to sit under it. A stray tab tap then left
 * setup mid-flow: the wizard is not a tab, so the bar's own `popUpTo` discarded it, and nothing
 * navigated back. On a phone whose accessibility switch is greyed out, O5 has no Skip, so the bar was
 * the only way past that step and the only way to lose the whole setup. Hidden inside the wizard, the
 * exits are the wizard's own: Back for the previous step, and the last step's Finish.
 *
 * Pure on purpose, so the rule is testable without a `NavController`.
 *
 * @param currentRoute the route of the current back-stack entry. Null for the first frame, before
 *   the graph has settled on a destination.
 * @param tabCount how many registered entries are bottom-bar destinations.
 */
fun showsTabBar(currentRoute: String?, tabCount: Int): Boolean =
    tabCount > 0 && currentRoute != Routes.ONBOARDING
