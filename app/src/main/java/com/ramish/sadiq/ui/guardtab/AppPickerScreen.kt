package com.ramish.sadiq.ui.guardtab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.ramish.sadiq.R
import com.ramish.sadiq.ui.components.AppIcon
import com.ramish.sadiq.ui.components.LocalAppIconLoader
import com.ramish.sadiq.ui.components.PrimaryPillButton
import com.ramish.sadiq.ui.components.SadiqTopBar
import com.ramish.sadiq.ui.components.SectionHeader
import com.ramish.sadiq.ui.theme.SadiqTheme
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun AppPickerScreen(navController: NavController, viewModel: AppPickerViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val installedAppsSource = uiState.installedAppsSource

    if (installedAppsSource == null) {
        // Handle loading state or error, e.g., show a CircularProgressIndicator
        // For now, return early to prevent crash
        return
    }

    CompositionLocalProvider(LocalAppIconLoader provides installedAppsSource::icon) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SadiqTopBar(
                title = stringResource(R.string.app_picker_title),
                onBack = { navController.popBackStack() },
                backContentDescription = stringResource(R.string.confirm_cancel)
            )

            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text(stringResource(R.string.app_picker_search_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl, vertical = Spacing.md)
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                if (uiState.suggestedApps.isNotEmpty()) {
                    item {
                        SectionHeader(text = stringResource(R.string.app_picker_suggested_section_header))
                    }
                    items(uiState.suggestedApps) {
                        AppRow(it, viewModel::onAppToggle)
                    }
                    item { HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm)) }
                }
                item {
                    SectionHeader(text = stringResource(R.string.app_picker_all_apps_section_header))
                }
                items(uiState.allApps) {
                    AppRow(it, viewModel::onAppToggle)
                }
            }

            Column(modifier = Modifier.padding(Spacing.xl)) {
                PrimaryPillButton(
                    text = stringResource(R.string.app_picker_button_guard_apps, uiState.selectedPackages.size),
                    // Pop only once the write has returned. Popping here used to cancel the save's
                    // coroutine at the end of the exit transition, so a slow Room open rolled the
                    // transaction back and the user landed on an unchanged Guard tab. Zero is
                    // allowed: he must be able to un-guard his last app.
                    onClick = { viewModel.saveGuardedApps { navController.popBackStack() } }
                )
            }
        }
    }
}

@Composable
private fun AppRow(
    appRow: AppRow,
    onAppToggle: (String, String, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAppToggle(appRow.packageName, appRow.label, !appRow.isSelected) }
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(packageName = appRow.packageName, size = 40.dp, label = appRow.label)
        Spacer(Modifier.width(Spacing.md))
        Text(text = appRow.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Checkbox(checked = appRow.isSelected, onCheckedChange = { isChecked -> onAppToggle(appRow.packageName, appRow.label, isChecked) })
    }
}

@Preview(name = "App Picker Screen · Light", widthDp = 360, showBackground = true)
@Composable
private fun AppPickerScreenLightPreview() {
    SadiqTheme(flavor = ThemeFlavor.LIGHT) {
        AppPickerScreen(rememberNavController())
    }
}

@Preview(name = "App Picker Screen · Dark", widthDp = 360, showBackground = true)
@Composable
private fun AppPickerScreenDarkPreview() {
    SadiqTheme(flavor = ThemeFlavor.DARK) {
        AppPickerScreen(rememberNavController())
    }
}

@Preview(name = "App Picker Screen · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun AppPickerScreenSepiaPreview() {
    SadiqTheme(flavor = ThemeFlavor.SEPIA) {
        AppPickerScreen(rememberNavController())
    }
}