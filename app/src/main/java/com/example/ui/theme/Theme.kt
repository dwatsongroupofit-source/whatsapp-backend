package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = WhatsGreen,
    secondary = WhatsGreen,
    background = WhatsBackground,
    surface = WhatsCardDark,
    onPrimary = WhatsTealDark,
    onBackground = WhatsLightText,
    onSurface = WhatsLightText
)

private val LightColorScheme = lightColorScheme(
    primary = WhatsGreenLight,
    secondary = WhatsGreenLight,
    background = WhatsBackgroundLight,
    surface = WhatsCardLight,
    onPrimary = WhatsBackgroundLight,
    onBackground = WhatsTealDark,
    onSurface = WhatsTealDark
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Default to gorgeous dark mode
    dynamicColor: Boolean = false, // Use our dedicated WhatsApp theme colors
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
