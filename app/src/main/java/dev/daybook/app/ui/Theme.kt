package dev.daybook.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF7165D8), onPrimary = Color.White,
    primaryContainer = Color(0xFFEAE5FF), onPrimaryContainer = Color(0xFF413785),
    secondary = Color(0xFF706887), secondaryContainer = Color(0xFFF0EBFA), onSecondaryContainer = Color(0xFF49405F),
    tertiary = Color(0xFF956542), background = Color(0xFFF6F3FB),
    surface = Color(0xFFFFFEFF), onSurface = Color(0xFF262331),
    onSurfaceVariant = Color(0xFF726C80), surfaceVariant = Color(0xFFEDE9F4),
    surfaceContainer = Color(0xFFF0ECF7), outline = Color(0xFFAEA6BF), outlineVariant = Color(0xFFE9E3F0))
private val Dark = darkColorScheme(
    primary = Color(0xFFC4B9FF), onPrimary = Color(0xFF302564),
    primaryContainer = Color(0xFF453875), onPrimaryContainer = Color(0xFFEAE2FF),
    secondary = Color(0xFFC9BEDD), secondaryContainer = Color(0xFF3B344D), onSecondaryContainer = Color(0xFFE8DFF6),
    tertiary = Color(0xFFE2B795), background = Color(0xFF191622),
    surface = Color(0xFF24202E), onSurface = Color(0xFFF3EEF8),
    onSurfaceVariant = Color(0xFFBEB5CE), surfaceVariant = Color(0xFF393242),
    surfaceContainer = Color(0xFF2E283B), outline = Color(0xFF91879E), outlineVariant = Color(0xFF443C50))

val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable fun DaybookTheme(dark: Boolean, reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val base = Typography()
    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
    MaterialTheme(colorScheme = if (dark) Dark else Light,
        typography = base.copy(
            headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 32.sp, letterSpacing = (-0.7).sp),
            headlineMedium = base.headlineMedium.copy(fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
            titleLarge = base.titleLarge.copy(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
            titleMedium = base.titleMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
            titleSmall = base.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
            bodyMedium = base.bodyMedium.copy(fontSize = 13.sp, lineHeight = 20.sp),
            bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
            labelMedium = base.labelMedium.copy(fontSize = 11.sp, lineHeight = 15.sp),
            bodyLarge = base.bodyLarge.copy(fontSize = 14.sp, lineHeight = 22.sp)),
        shapes = Shapes(small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)),
        content = content)
}
}
