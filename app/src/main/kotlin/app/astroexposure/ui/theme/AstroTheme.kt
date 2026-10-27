package app.astroexposure.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Visual tokens from the approved UI design spec (docs/ui-design.md):
 * deep-space black background (#05070D), card surface (#0C1120), and the
 * starlight-gold accent (#F2C66D) reserved for conclusion values.
 * Red-light mode swaps the whole palette to a red scale (dark-adaptation
 * safe), not just a tinted accent.
 */
private val StarlightGold = Color(0xFFF2C66D)

private val NightScheme = darkColorScheme(
    primary = StarlightGold,
    onPrimary = Color(0xFF1A1405),
    background = Color(0xFF05070D),
    onBackground = Color(0xFFE9ECF5),
    surface = Color(0xFF0C1120),
    onSurface = Color(0xFFE9ECF5),
    surfaceVariant = Color(0xFF131A30),
    onSurfaceVariant = Color(0xFFA7AEC4),
)

private val RedScheme = darkColorScheme(
    primary = Color(0xFFFF4A33),
    onPrimary = Color(0xFF2B0500),
    background = Color(0xFF0B0101),
    onBackground = Color(0xFFFF7A68),
    surface = Color(0xFF1C0505),
    onSurface = Color(0xFFFF8A78),
    surfaceVariant = Color(0xFF2A0808),
    onSurfaceVariant = Color(0xFFC25444),
)

@Composable
fun AstroTheme(redLight: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (redLight) RedScheme else NightScheme,
        content = content,
    )
}
