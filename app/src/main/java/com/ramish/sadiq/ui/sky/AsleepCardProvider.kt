package com.ramish.sadiq.ui.sky

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ramish.sadiq.R
import com.ramish.sadiq.contract.HomeCardIcon
import com.ramish.sadiq.contract.HomeCardModel
import com.ramish.sadiq.contract.HomeCardProvider
import com.ramish.sadiq.contract.PermissionStatusProvider
import com.ramish.sadiq.contract.SettingsTarget
import com.ramish.sadiq.contract.HomeAction
import com.ramish.sadiq.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AsleepCardProvider @Inject constructor(
    private val permissionStatusProvider: PermissionStatusProvider,
    private val homeAction: HomeAction,
) : HomeCardProvider {
    override val priority: Int = 100
    override val card: Flow<HomeCardModel?> = permissionStatusProvider.changes.map {
        if (!it.accessibilityEnabled) {
            HomeCardModel(
                id = "asleep",
                icon = HomeCardIcon.WARNING,
                title = "Sadiq is asleep",
                body = "It can\'t ask you anything until you wake it.",
                actionLabel = "Wake Sadiq",
                action = { homeAction.openSettings(SettingsTarget.ACCESSIBILITY) }
            )
        } else {
            null
        }
    }
}
