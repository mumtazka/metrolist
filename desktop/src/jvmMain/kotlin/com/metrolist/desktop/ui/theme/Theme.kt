/**
 * Metrolist Desktop — Theme matching mobile app exactly
 * Uses materialkolor for dynamic color generation from seed color
 * Seed color animates smoothly when album-art dynamic color changes it.
 */

package com.metrolist.desktop.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

// ============================================================
// Default theme color — matches mobile DefaultThemeColor exactly
// ============================================================
val DefaultThemeColor = Color(0xFFED5564)

// ============================================================
// Pure black AMOLED enhancement for desktop
// ============================================================
fun ColorScheme.pureBlack(apply: Boolean): ColorScheme =
    if (apply) copy(
        surface = Color.Black,
        background = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0A0A0A),
        surfaceContainer = Color(0xFF121212),
        surfaceContainerHigh = Color(0xFF1E1E1E),
        surfaceContainerHighest = Color(0xFF282828),
    ) else this

// ============================================================
// MetrolistTheme — mirrors the mobile Theme.kt
// ============================================================

@Composable
fun MetrolistTheme(
    darkTheme: Boolean = true,
    pureBlack: Boolean = true,
    themeColor: Color = DefaultThemeColor,
    content: @Composable () -> Unit,
) {
    // Animate the seed colour so album-art recolors feel smooth (600 ms)
    val animatedSeed by animateColorAsState(
        targetValue = themeColor,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "themeColor",
    )

    val baseColorScheme = rememberDynamicColorScheme(
        primary = animatedSeed,
        isDark = darkTheme,
        isAmoled = pureBlack,
        style = PaletteStyle.TonalSpot,
    )

    val colorScheme = remember(baseColorScheme, pureBlack, darkTheme) {
        if (darkTheme && pureBlack) {
            baseColorScheme.pureBlack(true)
        } else {
            baseColorScheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
