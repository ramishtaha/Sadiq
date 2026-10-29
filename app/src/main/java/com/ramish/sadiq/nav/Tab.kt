package com.ramish.sadiq.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.ui.graphics.vector.ImageVector

/** The four destinations of the bottom bar, in order. Order is the only thing that positions them. */
enum class Tab(val order: Int, val labelRes: Int, val icon: ImageVector) {
    SKY(0, com.ramish.sadiq.R.string.tab_sky, Icons.Outlined.Spa),
    FOCUS(1, com.ramish.sadiq.R.string.tab_focus, Icons.Outlined.Timeline),
    GUARD(2, com.ramish.sadiq.R.string.tab_guard, Icons.Outlined.Tune),
    WEEK(3, com.ramish.sadiq.R.string.tab_week, Icons.Outlined.CalendarMonth)
}
