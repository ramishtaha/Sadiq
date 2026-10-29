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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.ramish.sadiq.R
import com.ramish.sadiq.core.model.CalcMethod
import com.ramish.sadiq.core.model.Madhab
import com.ramish.sadiq.ui.components.ChoiceChips
import com.ramish.sadiq.ui.components.SadiqCard
import com.ramish.sadiq.ui.components.SadiqTopBar
import com.ramish.sadiq.ui.components.SectionHeader
import com.ramish.sadiq.ui.components.SettingRow
import com.ramish.sadiq.ui.components.TonalPillButton
import com.ramish.sadiq.ui.theme.SadiqTheme
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor

@Composable
fun PrayerSettingsScreen(navController: NavController, viewModel: PrayerSettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // ChoiceChips takes a plain `(T) -> String`, so labels are resolved here rather than in a
    // composable lambda.
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SadiqTopBar(
            title = stringResource(R.string.prayer_settings_title),
            onBack = { navController.popBackStack() },
            backContentDescription = stringResource(R.string.confirm_cancel)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            item {
                SadiqCard(modifier = Modifier.padding(horizontal = Spacing.xl)) {
                    Column {
                        Text(text = uiState.place, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(Spacing.md))
                        TonalPillButton(
                            text = stringResource(R.string.prayer_settings_use_my_location_once),
                            onClick = viewModel::useCurrentLocation
                        )
                        if (uiState.locationError) {
                            Spacer(Modifier.height(Spacing.md))
                            Text(
                                text = stringResource(R.string.prayer_settings_location_fail, uiState.place),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(text = stringResource(R.string.prayer_settings_method_section_header))
                ChoiceChips(
                    options = CalcMethod.entries.toList(),
                    selected = uiState.method,
                    onSelect = viewModel::setMethod,
                    label = { it.name },
                    modifier = Modifier.padding(horizontal = Spacing.xl)
                )
            }

            item {
                SectionHeader(text = stringResource(R.string.prayer_settings_madhab_section_header))
                ChoiceChips(
                    options = Madhab.entries.toList(),
                    selected = uiState.madhab,
                    onSelect = viewModel::setMadhab,
                    label = { it.name },
                    modifier = Modifier.padding(horizontal = Spacing.xl)
                )
            }

            item {
                SectionHeader(text = stringResource(R.string.prayer_settings_prayer_window_section_header))
                ChoiceChips(
                    options = listOf(15, 20, 25, 30, 35, 40),
                    selected = uiState.windowMin,
                    onSelect = viewModel::setWindowMin,
                    label = { minutes -> context.getString(R.string.duration_minutes, minutes) },
                    modifier = Modifier.padding(horizontal = Spacing.xl)
                )
            }

            item {
                SettingRow(
                    title = stringResource(R.string.prayer_settings_jumuah_window_title),
                    subtitle = stringResource(R.string.prayer_settings_jumuah_window_subtitle),
                    checked = uiState.jumuahEnabled,
                    onCheckedChange = viewModel::setJumuahEnabled,
                    modifier = Modifier.padding(horizontal = Spacing.xl)
                )
            }

            item {
                SectionHeader(text = stringResource(R.string.prayer_settings_today_prayer_times_section_header))
            }
            items(uiState.todayPrayerTimes) { prayerRow ->
                PrayerTimeRow(prayerRow = prayerRow)
            }

            item {
                Text(
                    text = stringResource(R.string.prayer_settings_copy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.lg)
                )
            }
        }
    }
}

@Composable
private fun PrayerTimeRow(prayerRow: PrayerRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.md)
            .clickable { /* No action */ },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = prayerRow.name.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (prayerRow.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = prayerRow.time,
            style = MaterialTheme.typography.bodyLarge,
            color = if (prayerRow.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Preview(name = "Prayer Settings Screen · Light", widthDp = 360, showBackground = true)
@Composable
private fun PrayerSettingsScreenLightPreview() {
    SadiqTheme(flavor = ThemeFlavor.LIGHT) {
        PrayerSettingsScreen(rememberNavController())
    }
}

@Preview(name = "Prayer Settings Screen · Dark", widthDp = 360, showBackground = true)
@Composable
private fun PrayerSettingsScreenDarkPreview() {
    SadiqTheme(flavor = ThemeFlavor.DARK) {
        PrayerSettingsScreen(rememberNavController())
    }
}

@Preview(name = "Prayer Settings Screen · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun PrayerSettingsScreenSepiaPreview() {
    SadiqTheme(flavor = ThemeFlavor.SEPIA) {
        PrayerSettingsScreen(rememberNavController())
    }
}
