package com.ammalfarm.adusanthai.ui.theme

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
  primary = FarmPrimaryDark,
  onPrimary = FarmOnPrimaryDark,
  primaryContainer = FarmPrimaryContainerDark,
  onPrimaryContainer = FarmOnPrimaryContainerDark,
  inversePrimary = FarmInversePrimaryDark,
  secondary = FarmSecondaryDark,
  onSecondary = FarmOnSecondaryDark,
  secondaryContainer = FarmSecondaryContainerDark,
  onSecondaryContainer = FarmOnSecondaryContainerDark,
  tertiary = FarmTertiaryDark,
  onTertiary = FarmOnTertiaryDark,
  tertiaryContainer = FarmTertiaryContainerDark,
  onTertiaryContainer = FarmOnTertiaryContainerDark,
  error = FarmErrorDark,
  onError = FarmOnErrorDark,
  errorContainer = FarmErrorContainerDark,
  onErrorContainer = FarmOnErrorContainerDark,
  background = FarmBackgroundDark,
  onBackground = FarmOnBackgroundDark,
  surface = FarmSurfaceDark,
  onSurface = FarmOnSurfaceDark,
  surfaceVariant = FarmSurfaceVariantDark,
  onSurfaceVariant = FarmOnSurfaceVariantDark,
  surfaceContainerLowest = FarmSurfaceContainerLowestDark,
  surfaceContainerLow = FarmSurfaceContainerLowDark,
  surfaceContainer = FarmSurfaceContainerDark,
  surfaceContainerHigh = FarmSurfaceContainerHighDark,
  surfaceContainerHighest = FarmSurfaceContainerHighestDark,
  inverseSurface = FarmInverseSurfaceDark,
  inverseOnSurface = FarmInverseOnSurfaceDark,
  outline = FarmOutlineDark,
  outlineVariant = FarmOutlineVariantDark,
)

private val LightColorScheme = lightColorScheme(
  primary = FarmPrimaryLight,
  onPrimary = FarmOnPrimaryLight,
  primaryContainer = FarmPrimaryContainerLight,
  onPrimaryContainer = FarmOnPrimaryContainerLight,
  inversePrimary = FarmInversePrimaryLight,
  secondary = FarmSecondaryLight,
  onSecondary = FarmOnSecondaryLight,
  secondaryContainer = FarmSecondaryContainerLight,
  onSecondaryContainer = FarmOnSecondaryContainerLight,
  tertiary = FarmTertiaryLight,
  onTertiary = FarmOnTertiaryLight,
  tertiaryContainer = FarmTertiaryContainerLight,
  onTertiaryContainer = FarmOnTertiaryContainerLight,
  error = FarmErrorLight,
  onError = FarmOnErrorLight,
  errorContainer = FarmErrorContainerLight,
  onErrorContainer = FarmOnErrorContainerLight,
  background = FarmBackgroundLight,
  onBackground = FarmOnBackgroundLight,
  surface = FarmSurfaceLight,
  onSurface = FarmOnSurfaceLight,
  surfaceVariant = FarmSurfaceVariantLight,
  onSurfaceVariant = FarmOnSurfaceVariantLight,
  surfaceContainerLowest = FarmSurfaceContainerLowestLight,
  surfaceContainerLow = FarmSurfaceContainerLowLight,
  surfaceContainer = FarmSurfaceContainerLight,
  surfaceContainerHigh = FarmSurfaceContainerHighLight,
  surfaceContainerHighest = FarmSurfaceContainerHighestLight,
  inverseSurface = FarmInverseSurfaceLight,
  inverseOnSurface = FarmInverseOnSurfaceLight,
  outline = FarmOutlineLight,
  outlineVariant = FarmOutlineVariantLight,
)

@Composable
fun MyApplicationTheme(
  themeMode: ThemeMode = ThemeMode.SYSTEM,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val isSystemDark = isSystemInDarkTheme()
  val darkTheme = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
  }

  val colorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      val context = LocalContext.current
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
  }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    shapes = Shapes,
    content = content
  )
}

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      val context = LocalContext.current
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
  }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    shapes = Shapes,
    content = content
  )
}


