package com.p2r3.convert.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.p2r3.convert.model.AppSettings
import com.p2r3.convert.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF1D56C7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE6FF),
    onPrimaryContainer = Color(0xFF001848),
    secondary = Color(0xFF006B78),
    secondaryContainer = Color(0xFFBCEBF2),
    tertiary = Color(0xFF8A4E00),
    tertiaryContainer = Color(0xFFFFDDBB),
    background = Color(0xFFF4F7FC),
    surface = Color(0xFFF4F7FC),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFDDE4F2),
    surfaceContainer = Color(0xFFF0F3FA),
    surfaceContainerLow = Color(0xFFFBFCFF),
    surfaceContainerHigh = Color(0xFFE4EAF6),
    outline = Color(0xFF717B8F),
    outlineVariant = Color(0xFFC1C8D6)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB7C7FF),
    onPrimary = Color(0xFF002B79),
    primaryContainer = Color(0xFF0B3C9E),
    onPrimaryContainer = Color(0xFFDEE6FF),
    secondary = Color(0xFF7CD0DF),
    secondaryContainer = Color(0xFF004F59),
    tertiary = Color(0xFFFFB86D),
    tertiaryContainer = Color(0xFF693A00),
    background = Color(0xFF0E141F),
    surface = Color(0xFF0E141F),
    surfaceBright = Color(0xFF222937),
    surfaceVariant = Color(0xFF404959),
    surfaceContainer = Color(0xFF151C28),
    surfaceContainerLow = Color(0xFF141B26),
    surfaceContainerHigh = Color(0xFF202836),
    outline = Color(0xFF8993A8),
    outlineVariant = Color(0xFF414B5D)
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 42.sp, lineHeight = 46.sp),
    displayMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 40.sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(16.dp),
    small = RoundedCornerShape(20.dp),
    medium = RoundedCornerShape(26.dp),
    large = RoundedCornerShape(34.dp),
    extraLarge = RoundedCornerShape(42.dp)
)

@Composable
fun ConvertTheme(
    settings: AppSettings,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        settings.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
