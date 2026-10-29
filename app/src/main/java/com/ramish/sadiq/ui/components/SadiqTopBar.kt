package com.ramish.sadiq.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.ramish.sadiq.ui.theme.ThemeFlavor

/**
 * The page title bar. Centred, on the page colour, with the back arrow on a sub-screen and actions
 * on the right. There is deliberately no navigation drawer: every destination is a tab, a card, or
 * a back away.
 *
 * The container is `background`, not `surface`, so the bar belongs to the page and cards keep the
 * only raised surface on the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SadiqTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backContentDescription: String = "Back",
    actions: @Composable RowScope.() -> Unit = {}
) {
    CenterAlignedTopAppBar(
        title = {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
        },
        modifier = modifier,
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = backContentDescription
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Preview(name = "SadiqTopBar · Light", widthDp = 360, showBackground = true)
@Composable
private fun SadiqTopBarLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        SadiqTopBar(title = "Sadiq")
    }
}

@Preview(name = "SadiqTopBar · Dark", widthDp = 360, showBackground = true)
@Composable
private fun SadiqTopBarDarkPreview() {
    ComponentPreview(ThemeFlavor.DARK) {
        SadiqTopBar(title = "Sadiq")
    }
}

@Preview(name = "SadiqTopBar · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun SadiqTopBarSepiaPreview() {
    ComponentPreview(ThemeFlavor.SEPIA) {
        SadiqTopBar(
            title = "Settings",
            onBack = {},
            actions = {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings"
                    )
                }
            }
        )
    }
}
