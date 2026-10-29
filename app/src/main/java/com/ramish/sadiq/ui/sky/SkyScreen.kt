package com.ramish.sadiq.ui.sky

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.HomeCardIcon
import com.ramish.sadiq.contract.HomeCardModel
import com.ramish.sadiq.nav.Routes
import com.ramish.sadiq.ui.components.HeroEmptyState
import com.ramish.sadiq.ui.components.PrimaryPillButton
import com.ramish.sadiq.ui.components.SadiqCard
import com.ramish.sadiq.ui.components.SadiqTopBar
import com.ramish.sadiq.ui.components.SectionHeader
import com.ramish.sadiq.ui.components.TextPillButton
import com.ramish.sadiq.ui.theme.SadiqTheme
import com.ramish.sadiq.ui.theme.SadiqTypography
import com.ramish.sadiq.core.time.LogicalDays
import java.time.Instant
import java.time.ZoneId

@Composable
fun SkyScreen(nav: NavController, viewModel: SkyViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (state.needsOnboarding) {
        nav.navigate(Routes.ONBOARDING) {
            popUpTo(Routes.SKY) { inclusive = true }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SadiqTopBar(
            title = stringResource(R.string.app_name),
            actions = {
                IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )

        if (state.loading) {
            // TODO: Loading state
            return
        }

        var selectedStarIndex by remember { mutableStateOf<Int?>(null) }

        // Sky Card
        SadiqCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp) // Aspect ratio 1:1.1 approximation
        ) {
            StarfieldCanvas(
                stars = state.stars,
                kindling = state.kindling,
                seed = state.skySeed,
                todayDay = LogicalDays.of(System.currentTimeMillis(), ZoneId.systemDefault().id),
                reduceMotion = false, // TODO: Get from settings
                onStarTapped = { index -> selectedStarIndex = index }
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = stringResource(R.string.sky_your_sky).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = state.totalLit.toString(),
                        style = SadiqTypography.displaySmall.copy(letterSpacing = (-0.5).sp),
                        color = SkyPalette.dust,
                    )
                    if (state.litToday > 0) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.sky_lit_tonight, state.litToday),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            selectedStarIndex?.let { index ->
                state.stars.getOrNull(index)?.let { star ->
                    StarTooltip(
                        star = star,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(horizontal = 20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Status Line
        SadiqCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                SectionHeader(text = stringResource(R.string.sky_today_section_header))
                state.status?.let { status ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(status.stringRes, *status.formattedArgs.toTypedArray()),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (state.freeMinutesLeft > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_star_glyph),
                            contentDescription = null, // decorative
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.sky_free_minutes_earned, state.freeMinutesLeft),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Primary Pill Button
        PrimaryPillButton(
            text = stringResource(R.string.sky_button_start_focus),
            onClick = { nav.navigate(Routes.FOCUS) },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Contextual Card
        state.card?.let { card ->
            SadiqCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painter = when (card.icon) {
                                HomeCardIcon.WARNING -> painterResource(R.drawable.ic_warning)
                                HomeCardIcon.MOON -> painterResource(R.drawable.ic_moon)
                                HomeCardIcon.CALENDAR -> painterResource(R.drawable.ic_calendar)
                                HomeCardIcon.INFO -> painterResource(R.drawable.ic_info)
                            },
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = card.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        card.actionLabel?.let {
                            TextPillButton(text = it, onClick = card.action)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = card.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Empty Sky State
        if (state.totalLit == 0 && !state.loading && state.status?.stringRes != R.string.sky_status_asleep) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HeroEmptyState(
                    icon = Icons.Default.Star,
                    title = stringResource(R.string.sky_empty_headline),
                    body = stringResource(R.string.sky_empty_subtitle),
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewSkyScreen() {
    SadiqTheme {
        SkyScreen(rememberNavController())
    }
}
