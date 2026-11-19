package com.liu.gallery.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D6BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF001945),
    secondary = Color(0xFF00A0A8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCEFF1),
    onSecondaryContainer = Color(0xFF002022),
    tertiary = Color(0xFF8A4FBE),
    background = Color(0xFFFAFAFC),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFAFAFC),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE4E2E9),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF767681),
    outlineVariant = Color(0xFFC7C5D0),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C3FF),
    onPrimary = Color(0xFF002C76),
    primaryContainer = Color(0xFF1742A4),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFF80D4D7),
    onSecondary = Color(0xFF003739),
    secondaryContainer = Color(0xFF004F51),
    onSecondaryContainer = Color(0xFFCCEFF1),
    tertiary = Color(0xFFD7B9F5),
    background = Color(0xFF101014),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF101014),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C5CF),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF45464F),
    error = Color(0xFFFFB4AB),
)

private val AppTypography = Typography(
    // 页面标题更紧凑有神
    titleLarge = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
    ),
)

@Composable
fun AlbumTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        // Android 12+ 跟随壁纸动态取色
        Build.VERSION.SDK_INT >= 31 && dynamicAvailable() ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content,
    )
}

private fun dynamicAvailable() = true
