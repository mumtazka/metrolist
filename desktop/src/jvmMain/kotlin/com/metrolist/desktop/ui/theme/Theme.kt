/**
 * Metrolist Desktop — Theme matching mobile app exactly
 * Uses materialkolor for dynamic color generation from seed color
 */

package com.metrolist.desktop.ui.theme

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
// MetrolistTheme — mirrors the mobile Theme.kt
// ============================================================

@Composable
fun MetrolistTheme(
    darkTheme: Boolean = true,
    pureBlack: Boolean = true,
    themeColor: Color = DefaultThemeColor,
    content: @Composable () -> Unit,
) {
    val baseColorScheme = rememberDynamicColorScheme(
        primary = themeColor,
        isDark = darkTheme,
        isAmoled = pureBlack,
        style = PaletteStyle.TonalSpot,
    )

    MaterialTheme(
        colorScheme = baseColorScheme,
        content = content,
    )
}
