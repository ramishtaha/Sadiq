package com.ramish.sadiq.ui.components

// CONTRACT-GAP: the icon provider lives in WP-12's app contract (InstalledAppsSource.icon) and
// WP-12 has not landed, so the kit cannot depend on it. LocalAppIconLoader is the smallest seam:
// the app provides one lambda once (typically InstalledAppsSource::icon) and every component reads
// it. The signature of AppIcon stays exactly what WP-03 promises.

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.ramish.sadiq.ui.theme.ThemeFlavor

/**
 * The app-icon loader seam. WP-12 owns the real implementation; providing it here as a
 * CompositionLocal means the component kit never imports an app-contract interface and no screen
 * has to thread a loader through its parameters.
 *
 * The lambda must be cheap and may return null; the caller is expected to cache its Drawables.
 */
val LocalAppIconLoader: ProvidableCompositionLocal<(String) -> Drawable?> =
    staticCompositionLocalOf { { null } }

/**
 * Another app's icon at a fixed size, 32 dp on a guarded-app card, 40 dp in the picker, 20 dp in the
 * interception chip. Rounded to a quarter of its size so it sits inside Sadiq's geometry.
 *
 * When the loader has nothing (an uninstalled app, or a preview), the slot becomes a muted tile with
 * the label's initial. That is deliberate: an empty hole would read as a missing app, and a star
 * would confuse the app icon with the reward. Pass [contentDescription] only when no text label
 * sits next to the icon.
 */
@Composable
fun AppIcon(
    packageName: String,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    label: String? = null
) {
    val loadIcon = LocalAppIconLoader.current
    val density = LocalDensity.current
    val drawable = remember(packageName, loadIcon) { loadIcon(packageName) }
    val bitmap = remember(drawable, size, density) {
        drawable?.let { icon ->
            val px = with(density) { size.roundToPx() }.coerceAtLeast(1)
            runCatching { icon.toBitmap(px, px).asImageBitmap() }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(size / 4)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier.size(size).clip(shape),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initialOf(packageName = packageName, label = label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/** The first letter of the label, or of the package's last segment, upper-cased for the tile. */
private fun initialOf(packageName: String, label: String?): String {
    val fromLabel = label?.trim()?.takeIf { it.isNotEmpty() }
    val source = fromLabel ?: packageName.substringAfterLast('.').ifEmpty { packageName }
    return source.take(1).uppercase()
}

@Preview(name = "AppIcon · Light", widthDp = 360, showBackground = true)
@Composable
private fun AppIconLightPreview() {
    ComponentPreview(ThemeFlavor.LIGHT) {
        AppIcon(packageName = "com.instagram.android", size = 32.dp, label = "Instagram")
        AppIcon(packageName = "com.google.android.youtube", size = 40.dp, label = "YouTube")
        AppIcon(packageName = "com.twitter.android", size = 20.dp, label = "X")
    }
}

@Preview(name = "AppIcon · Dark", widthDp = 360, showBackground = true)
@Composable
private fun AppIconDarkPreview() {
    ComponentPreview(ThemeFlavor.DARK) {
        AppIcon(packageName = "com.instagram.android", size = 32.dp, label = "Instagram")
        AppIcon(packageName = "com.google.android.youtube", size = 40.dp, label = "YouTube")
        AppIcon(packageName = "com.twitter.android", size = 20.dp, label = "X")
    }
}

@Preview(name = "AppIcon · Sepia", widthDp = 360, showBackground = true)
@Composable
private fun AppIconSepiaPreview() {
    ComponentPreview(ThemeFlavor.SEPIA) {
        AppIcon(packageName = "com.instagram.android", size = 32.dp, label = "Instagram")
        AppIcon(packageName = "com.google.android.youtube", size = 40.dp, label = "YouTube")
        AppIcon(packageName = "com.twitter.android", size = 20.dp, label = "X")
    }
}
