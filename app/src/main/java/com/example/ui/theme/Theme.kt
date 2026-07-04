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

private val DarkColorScheme =
  darkColorScheme(
    primary = EcoGreenDarkPrimary,
    secondary = EcoGreenDarkSecondary,
    tertiary = EcoGreenDarkTertiary,
    background = EcoBackground,
    surface = EcoSurface,
    onPrimary = androidx.compose.ui.graphics.Color.Black,
    onBackground = EcoOnBackground,
    onSurface = EcoOnSurface
  )

private val LightColorScheme =
  lightColorScheme(
    primary = EcoGreenPrimary,
    secondary = EcoGreenSecondary,
    tertiary = EcoGreenTertiary,
    background = EcoBackground,
    surface = EcoSurface,
    onPrimary = androidx.compose.ui.graphics.Color.Black,
    onBackground = EcoOnBackground,
    onSurface = EcoOnSurface
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Force Premium Dark theme by default for visual wow
  dynamicColor: Boolean = false, // Disable dynamic colors to preserve customized neon theme branding
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
