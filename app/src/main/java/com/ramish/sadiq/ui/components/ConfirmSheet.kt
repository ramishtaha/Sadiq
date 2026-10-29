package com.ramish.sadiq.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor
import kotlinx.coroutines.launch

/**
 * The one confirmation the app ever asks: a title, one true line, and two answers.
 *
 * Confirming runs [onConfirm] and then hides the sheet before [onDismiss], so a screen never has to
 * remember to close it. [destructive] swaps the confirm pill to the `errorContainer` pair; that is
 * the only place red is allowed outside a genuine error ("Delete all history").
 *
 * @param dismissLabel the calmer answer. Screens pass their own ("Keep going", "Let it go").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "Not now",
    destructive: Boolean = false,
    sheetState: SheetState = rememberModalBottomSheetState()
) {
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        ConfirmSheetContent(
            title = title,
            body = body,
            confirmLabel = confirmLabel,
            dismissLabel = dismissLabel,
            destructive = destructive,
            onConfirm = {
                onConfirm()
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            },
            onDismiss = onDismiss
        )
    }
}

/**
 * The sheet body, split out because a `ModalBottomSheet` lives in its own window and Android Studio
 * cannot render that window in a preview. Screens use [ConfirmSheet]; this exists so the layout has
 * real LIGHT / DARK / SEPIA previews.
 */
@Composable
private fun ConfirmSheetContent(
    title: String,
    body: String,
    confirmLabel: String,
    dismissLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl)
            .padding(top = Spacing.sm, bottom = Spacing.xxl)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onSurface
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.xl))
        PrimaryPillButton(
            text = confirmLabel,
            onClick = onConfirm,
            containerColor = if (destructive) scheme.errorContainer else scheme.primary,
            contentColor = if (destructive) scheme.onErrorContainer else scheme.onPrimary
        )
        Spacer(Modifier.height(Spacing.md))
        TonalPillButton(text = dismissLabel, onClick = onDismiss)
    }
}

@Preview(name = "ConfirmSheet · Light", widthDp = 360, showBackground = true)
@Composable
private fun ConfirmSheetLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        ConfirmSheetContent(
            title = "End now?",
            body = "You've earned 2 stars so far.",
            confirmLabel = "End session",
            dismissLabel = "Keep going",
            destructive = false,
            onConfirm = {},
            onDismiss = {}
        )
    }
}

@Preview(name = "ConfirmSheet · Dark", widthDp = 360, showBackground = true)
@Composable
private fun ConfirmSheetDarkPreview() {
    ComponentPreview(ThemeFlavor.DARK) {
        ConfirmSheetContent(
            title = "End now?",
            body = "You've earned 2 stars so far.",
            confirmLabel = "End session",
            dismissLabel = "Keep going",
            destructive = false,
            onConfirm = {},
            onDismiss = {}
        )
    }
}

@Preview(name = "ConfirmSheet · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun ConfirmSheetSepiaPreview() {
    ComponentPreview(ThemeFlavor.SEPIA) {
        ConfirmSheetContent(
            title = "Delete all history?",
            body = "Stars, opens and reflections are deleted. Your settings stay.",
            confirmLabel = "Delete all history",
            dismissLabel = "Let it go",
            destructive = true,
            onConfirm = {},
            onDismiss = {}
        )
    }
}
