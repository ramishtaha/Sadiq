package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramish.sadiq.R
import com.ramish.sadiq.ui.components.AppIcon
import com.ramish.sadiq.ui.components.SectionHeader
import com.ramish.sadiq.ui.onboarding.OnboardingViewModel
import com.ramish.sadiq.ui.onboarding.OemStepScaffold
import com.ramish.sadiq.ui.theme.Spacing

/**
 * O2: Pick apps.
 *
 * The rows are a plain column, not a lazy one: the step is already inside the wizard's scrolling
 * column, and a lazy list nested in an unbounded scroll has nothing to measure against — it used to
 * be laid out at zero height, which left the picker invisible.
 */
@Composable
fun AppsStep(vm: OnboardingViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    val suggestedApps = state.apps.filter { it.isSuggestedFeed }
    val allOtherApps = state.apps.filter { !it.isSuggestedFeed }

    OemStepScaffold(
        icon = Icons.Filled.Apps,
        headline = stringResource(R.string.onboarding_apps_headline),
        body = stringResource(R.string.onboarding_apps_body),
        actionLabel = if (state.selected.isNotEmpty()) {
            stringResource(R.string.onboarding_apps_action, state.selected.size)
        } else {
            stringResource(R.string.onboarding_apps_action_zero)
        },
        onAction = { vm.next() },
        actionEnabled = state.selected.isNotEmpty()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (suggestedApps.isNotEmpty()) {
                SectionHeader(stringResource(R.string.onboarding_apps_suggested_header))
                suggestedApps.forEach { app ->
                    AppItem(
                        packageName = app.packageName,
                        label = app.label,
                        selected = state.selected.contains(app.packageName),
                        onToggle = vm::toggleApp
                    )
                }
                Spacer(Modifier.height(Spacing.xl))
            }
            if (allOtherApps.isNotEmpty()) {
                SectionHeader(stringResource(R.string.onboarding_apps_all_apps_header))
                allOtherApps.forEach { app ->
                    AppItem(
                        packageName = app.packageName,
                        label = app.label,
                        selected = state.selected.contains(app.packageName),
                        onToggle = vm::toggleApp
                    )
                }
            }
        }
    }
}

@Composable
private fun AppItem(packageName: String, label: String, selected: Boolean, onToggle: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(packageName = packageName, size = 40.dp)
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Checkbox(checked = selected, onCheckedChange = { onToggle(packageName) })
    }
}
