package com.ramish.sadiq.ui.onboarding.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.ramish.sadiq.R
import com.ramish.sadiq.ui.onboarding.OnboardingViewModel
import com.ramish.sadiq.ui.onboarding.OemStepScaffold
import com.ramish.sadiq.ui.theme.Spacing

/** O1: Welcome. */
@Composable
fun WelcomeStep(vm: OnboardingViewModel) {
    OemStepScaffold(
        icon = Icons.Filled.Info,
        headline = stringResource(R.string.onboarding_welcome_headline),
        body = stringResource(R.string.onboarding_welcome_body),
        actionLabel = stringResource(R.string.onboarding_welcome_action),
        onAction = { vm.next() }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.onboarding_welcome_headline_arabic),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.onboarding_welcome_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
