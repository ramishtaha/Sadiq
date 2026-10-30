package com.ramish.sadiq.ui.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.ramish.sadiq.ui.onboarding.OnboardingPlan.StepId

/**
 * The exact menu path for [step] on this HyperOS version, drawn under the step's body.
 *
 * One plain paragraph, left aligned so the arrows read as a route, in the same secondary ink as the
 * body: it is the answer to "where is the switch", not a second instruction. Nothing is drawn when
 * the version is unknown — see [OemPaths.pathFor].
 *
 * @param step the OEM step being drawn.
 * @param hyperOsMajor the detected HyperOS major, or null when unknown.
 * @param modifier for the text.
 */
@Composable
fun OemPathNote(step: StepId, hyperOsMajor: Int?, modifier: Modifier = Modifier) {
    val path: Int = OemPaths.pathFor(step, hyperOsMajor) ?: return
    Text(
        text = stringResource(path),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        modifier = modifier.fillMaxWidth(),
    )
}
