package com.ramish.sadiq.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.ramish.sadiq.nav.Routes
import com.ramish.sadiq.ui.components.ComponentPreview
import com.ramish.sadiq.ui.onboarding.steps.AccessibilityStep
import com.ramish.sadiq.ui.onboarding.steps.AppsStep
import com.ramish.sadiq.ui.onboarding.steps.AutostartStep
import com.ramish.sadiq.ui.onboarding.steps.BatteryOptimizerStep
import com.ramish.sadiq.ui.onboarding.steps.BatterySaverStep
import com.ramish.sadiq.ui.onboarding.steps.ModeStep
import com.ramish.sadiq.ui.onboarding.steps.NotificationsStep
import com.ramish.sadiq.ui.onboarding.steps.PopupsStep
import com.ramish.sadiq.ui.onboarding.steps.RecentsStep
import com.ramish.sadiq.ui.onboarding.steps.RestrictedStep
import com.ramish.sadiq.ui.onboarding.steps.TimesStep
import com.ramish.sadiq.ui.onboarding.steps.TryItStep
import com.ramish.sadiq.ui.onboarding.steps.UsageStep
import com.ramish.sadiq.ui.onboarding.steps.WelcomeStep
import com.ramish.sadiq.ui.theme.Motion
import com.ramish.sadiq.ui.theme.Spacing
import com.ramish.sadiq.ui.theme.ThemeFlavor

/** Alpha of the unfilled part of the wizard's progress bar. */
private const val TRACK_ALPHA = 0.24f

/**
 * The wizard: one step at a time, animated between steps.
 *
 * The wizard is always full-screen; the back button returns to the previous step. When [onboardingDone]
 * is true, it navigates to the Sky screen with `popUpTo` so the back stack is clear.
 */
@Composable
fun OnboardingScreen(nav: NavHostController, vm: OnboardingViewModel = hiltViewModel()) {
    val state: OnboardingUiState by vm.uiState.collectAsStateWithLifecycle()

    // Every permission step is granted on a page Android owns, so the wizard only learns about it
    // when the screen comes back to the foreground. Without this the step never moved and the user
    // saw the same button forever.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    // The wizard is over: leave it, and take the back stack with it so Back cannot re-enter setup.
    LaunchedEffect(state.done) {
        if (state.done) {
            nav.navigate(Routes.SKY) {
                popUpTo(nav.graph.startDestinationId) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Back button goes to the previous step, or exits the wizard if it's the first step.
    BackHandler(enabled = state.currentStep != OnboardingPlan.StepId.WELCOME) {
        vm.back()
    }

    if (state.loading) {
        // One blank page while the app list and the settings are read: the wizard has nothing true
        // to say yet, and a spinner over the page colour would only flash.
        Box(modifier = Modifier.fillMaxSize())
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Progress bar
        LinearProgressIndicator(
            progress = {
                if (state.steps.isEmpty()) 0f else (state.index + 1).toFloat() / state.steps.size.toFloat()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp) // Thin segmented progress bar
                .padding(horizontal = Spacing.sm),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = TRACK_ALPHA)
        )

        AnimatedContent(
            targetState = state.currentStep as OnboardingPlan.StepId?,
            label = "onboarding_step_animation",
            transitionSpec = {
                fadeIn(animationSpec = tween(Motion.NORMAL)) togetherWith
                    fadeOut(animationSpec = tween(Motion.NORMAL))
            },
            modifier = Modifier.weight(1f)
        ) {
            when (it) {
                OnboardingPlan.StepId.WELCOME -> WelcomeStep(vm)
                OnboardingPlan.StepId.APPS -> AppsStep(vm)
                OnboardingPlan.StepId.MODE -> ModeStep(vm)
                OnboardingPlan.StepId.TIMES -> TimesStep(vm)
                OnboardingPlan.StepId.ACCESSIBILITY -> AccessibilityStep(vm)
                OnboardingPlan.StepId.RESTRICTED_SETTING -> RestrictedStep(vm)
                OnboardingPlan.StepId.NOTIFICATIONS -> NotificationsStep(vm)
                OnboardingPlan.StepId.POPUPS -> PopupsStep(vm)
                OnboardingPlan.StepId.BATTERY_OPTIMIZATION -> BatteryOptimizerStep(vm)
                OnboardingPlan.StepId.BATTERY_SAVER -> BatterySaverStep(vm)
                OnboardingPlan.StepId.AUTOSTART -> AutostartStep(vm)
                OnboardingPlan.StepId.RECENTS -> RecentsStep(vm)
                OnboardingPlan.StepId.USAGE -> UsageStep(vm)
                OnboardingPlan.StepId.TRY_IT -> TryItStep(vm)
                null -> {
                    // The wizard has no step to draw because it is finished; the effect above is
                    // already leaving for the Sky.
                }
            }
        }
    }
}

@Preview(name = "OnboardingScreen · Light", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun OnboardingScreenLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        // For preview purposes, we'll need a fake NavHostController and ViewModel
        val navController = rememberNavController()
        OnboardingScreen(nav = navController, vm = hiltViewModel())
    }
}
