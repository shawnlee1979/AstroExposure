package app.astroexposure.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Visual tokens from the UI design spec (docs/ui-design.md §2):
 * deep-space black background (#05070D), card surface (#0C1120), surface2
 * (#131B31), border (#22304F) and the starlight-gold accent (#F2C66D)
 * reserved for conclusion values. Red-light mode swaps the whole palette
 * to a red scale (dark-adaptation safe), not just a tinted accent.
 *
 * Both schemes fill the full Material role table — including
 * outline/outlineVariant (the spec border), errorContainer and
 * secondaryContainer (chip selected state) — so no component falls back
 * to library-default blue-grey/pink tints, which the red theme in
 * particular must never show (spec §2: red spectrum only).
 */
private val NightScheme = darkColorScheme(
    primary = Color(0xFFF2C66D),
    onPrimary = Color(0xFF1A1405),
    secondary = Color(0xFF6EA8FE),
    onSecondary = Color(0xFF05070D),
    secondaryContainer = Color(0xFF22304F),
    onSecondaryContainer = Color(0xFFE9EEF8),
    tertiary = Color(0xFF4CC38A),
    onTertiary = Color(0xFF05070D),
    tertiaryContainer = Color(0xFF16281E),
    onTertiaryContainer = Color(0xFFE9EEF8),
    error = Color(0xFFE5484D),
    onError = Color(0xFFFDECEA),
    errorContainer = Color(0xFF3D1512),
    onErrorContainer = Color(0xFFFDECEA),
    background = Color(0xFF05070D),
    onBackground = Color(0xFFE9EEF8),
    surface = Color(0xFF0C1120),
    onSurface = Color(0xFFE9EEF8),
    surfaceVariant = Color(0xFF131B31),
    onSurfaceVariant = Color(0xFF96A3BE),
    surfaceContainerLowest = Color(0xFF05070D),
    surfaceContainerLow = Color(0xFF0C1120),
    surfaceContainer = Color(0xFF0C1120),
    surfaceContainerHigh = Color(0xFF131B31),
    surfaceContainerHighest = Color(0xFF131B31),
    outline = Color(0xFF22304F),
    outlineVariant = Color(0xFF22304F),
)

private val RedScheme = darkColorScheme(
    primary = Color(0xFFFF9D8A),
    onPrimary = Color(0xFF2B0500),
    secondary = Color(0xFFFF9D8A),
    onSecondary = Color(0xFF2B0500),
    secondaryContainer = Color(0xFF471512),
    onSecondaryContainer = Color(0xFFFF7A6B),
    tertiary = Color(0xFFE5563C),
    onTertiary = Color(0xFF2B0500),
    tertiaryContainer = Color(0xFF471512),
    onTertiaryContainer = Color(0xFFFF7A6B),
    error = Color(0xFFFF4A3D),
    onError = Color(0xFF2B0500),
    errorContainer = Color(0xFF471512),
    onErrorContainer = Color(0xFFFF9D8A),
    background = Color(0xFF0A0202),
    onBackground = Color(0xFFFF7A6B),
    surface = Color(0xFF180404),
    onSurface = Color(0xFFFF7A6B),
    surfaceVariant = Color(0xFF220707),
    onSurfaceVariant = Color(0xFFD64A3E),
    surfaceContainerLowest = Color(0xFF0A0202),
    surfaceContainerLow = Color(0xFF180404),
    surfaceContainer = Color(0xFF180404),
    surfaceContainerHigh = Color(0xFF220707),
    surfaceContainerHighest = Color(0xFF220707),
    outline = Color(0xFF471512),
    outlineVariant = Color(0xFF471512),
)

/**
 * Type scale: the Material defaults, with labelSmall raised to the
 * spec's 12sp footnote floor (spec §2: footnotes at least 12sp).
 * Conclusion values keep their explicit large styling at the call sites.
 */
private val AstroTypography = Typography(
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
)

/** Corner tokens (spec §2): cards 16dp, controls 12dp. */
private val AstroShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun AstroTheme(redLight: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (redLight) RedScheme else NightScheme,
        typography = AstroTypography,
        shapes = AstroShapes,
        content = content,
    )
}
