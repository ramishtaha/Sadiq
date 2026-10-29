package com.ramish.sadiq.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.ThemeMode
import com.ramish.sadiq.nav.Routes
import com.ramish.sadiq.ui.components.ChoiceChips
import com.ramish.sadiq.ui.components.ConfirmSheet
import com.ramish.sadiq.ui.components.SadiqCard
import com.ramish.sadiq.ui.components.SadiqTopBar
import com.ramish.sadiq.ui.components.SectionHeader
import com.ramish.sadiq.ui.components.SettingRow
import com.ramish.sadiq.ui.components.TextPillButton
import com.ramish.sadiq.ui.theme.Spacing

/**
 * S5: Settings.
 *
 * Every row either changes something or opens the screen that does. There is no row here that only
 * looks like a setting: the notice about a permission lives in Health, the Bedtime switch is the same
 * edit the Guard tab makes, and "Delete all history" asks first.
 *
 * The screen is registered as `Routes.SETTINGS`; before WP-21 it did not exist, and the gear on the
 * Sky screen crashed the app when it was tapped.
 *
 * @param nav the host's controller, for the sub-screens.
 * @param viewModel the screen's state and intents.
 */
@Composable
fun SettingsScreen(nav: NavController, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        SadiqTopBar(
            title = stringResource(R.string.settings_title),
            onBack = { nav.popBackStack() },
        )

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
        ) {
            // ── Appearance ───────────────────────────────────────────────────────────────────
            SectionHeader(text = stringResource(R.string.settings_section_appearance))
            SadiqCard {
                Text(
                    text = stringResource(R.string.settings_theme_label),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(Spacing.md))
                val themeLabels = mapOf(
                    ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                    ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                    ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
                    ThemeMode.SEPIA to stringResource(R.string.settings_theme_sepia)
                )
                ChoiceChips(
                    options = ThemeMode.entries,
                    selected = state.theme,
                    onSelect = viewModel::setTheme,
                    label = { themeLabels.getValue(it) }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.md))
                SettingRow(
                    title = stringResource(R.string.settings_reduce_motion),
                    subtitle = stringResource(R.string.settings_reduce_motion_hint),
                    checked = state.reduceMotion,
                    onCheckedChange = viewModel::setReduceMotion
                )
            }

            // ── Notifications ────────────────────────────────────────────────────────────────
            SectionHeader(text = stringResource(R.string.settings_section_notifications))
            SadiqCard {
                SettingRow(
                    title = stringResource(R.string.settings_weekly_review),
                    subtitle = stringResource(R.string.settings_weekly_review_hint),
                    checked = state.weeklyNotif,
                    onCheckedChange = viewModel::setWeeklyReview
                )
                if (state.showLiveUpdates) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.md))
                    SettingRow(
                        title = stringResource(R.string.settings_live_updates),
                        subtitle = stringResource(R.string.settings_live_updates_hint),
                        showChevron = true,
                        onClick = viewModel::openLiveUpdates
                    )
                }
            }

            // ── Reliability ──────────────────────────────────────────────────────────────────
            SectionHeader(text = stringResource(R.string.settings_section_reliability))
            SadiqCard {
                SettingRow(
                    title = stringResource(R.string.settings_health),
                    subtitle = stringResource(R.string.settings_health_hint),
                    showChevron = true,
                    onClick = { nav.navigate(Routes.HEALTH) }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.md))
                SettingRow(
                    title = stringResource(R.string.settings_keep_alive),
                    subtitle = stringResource(R.string.settings_keep_alive_hint),
                    checked = state.keepAlive,
                    onCheckedChange = viewModel::setKeepAlive
                )
            }

            // ── Protected times ──────────────────────────────────────────────────────────────
            SectionHeader(text = stringResource(R.string.settings_section_protected_times))
            SadiqCard {
                SettingRow(
                    title = stringResource(R.string.settings_bedtime),
                    subtitle = stringResource(
                        R.string.settings_bedtime_value,
                        formatMinuteOfDay(state.sleepStartMinuteOfDay)
                    ),
                    checked = state.sleepEnabled,
                    onCheckedChange = viewModel::setBedtimeEnabled,
                    onClick = { nav.navigate(Routes.BEDTIME) }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.md))
                SettingRow(
                    title = stringResource(R.string.settings_morning),
                    subtitle = stringResource(
                        R.string.settings_morning_value,
                        formatMinuteOfDay(state.morningEndMinuteOfDay)
                    ),
                    checked = state.morningEnabled,
                    onCheckedChange = viewModel::setMorningEnabled,
                    onClick = { nav.navigate(Routes.MORNING) }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.md))
                SettingRow(
                    title = stringResource(R.string.settings_prayer_times),
                    subtitle = state.prayerSummary,
                    showChevron = true,
                    onClick = { nav.navigate(Routes.PRAYER) }
                )
            }

            // ── Privacy ──────────────────────────────────────────────────────────────────────
            SectionHeader(text = stringResource(R.string.settings_section_privacy))
            SadiqCard {
                Text(
                    text = stringResource(R.string.settings_privacy_statement),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.md))
                TextPillButton(
                    text = stringResource(R.string.settings_delete_history),
                    onClick = viewModel::requestDeleteHistory
                )
                if (state.historyDeleted) {
                    Text(
                        text = stringResource(R.string.settings_deleted),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── About ────────────────────────────────────────────────────────────────────────
            SectionHeader(text = stringResource(R.string.settings_section_about))
            SadiqCard {
                Text(
                    text = stringResource(R.string.settings_about_name),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.settings_about_version, state.versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.settings_about_credit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.settings_about_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(Spacing.xxl))
        }
    }

    if (state.confirmDelete) {
        ConfirmSheet(
            title = stringResource(R.string.settings_delete_title),
            body = stringResource(R.string.settings_delete_body),
            confirmLabel = stringResource(R.string.settings_delete_confirm),
            onConfirm = viewModel::confirmDeleteHistory,
            onDismiss = viewModel::dismissDeleteHistory,
            dismissLabel = stringResource(R.string.settings_delete_cancel),
            destructive = true
        )
    }
}
