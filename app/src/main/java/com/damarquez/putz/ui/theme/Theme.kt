package com.damarquez.putz.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val NormalLightColorScheme = lightColorScheme(
    primary = NormalPrimaryLight,
    onPrimary = NormalOnPrimaryLight,
    primaryContainer = NormalPrimaryContainerLight,
    onPrimaryContainer = NormalOnPrimaryContainerLight,
    background = NormalBackgroundLight,
    surface = NormalSurfaceLight,
    surfaceVariant = NormalSurfaceVariantLight,
    onSurface = NormalOnSurfaceLight,
    onSurfaceVariant = NormalOnSurfaceVariantLight,
    outline = NormalOutlineLight,
)

private val NormalDarkColorScheme = darkColorScheme(
    primary = NormalPrimaryDark,
    onPrimary = NormalOnPrimaryDark,
    primaryContainer = NormalPrimaryContainerDark,
    onPrimaryContainer = NormalOnPrimaryContainerDark,
    background = NormalBackgroundDark,
    surface = NormalSurfaceDark,
    surfaceVariant = NormalSurfaceVariantDark,
    onSurface = NormalOnSurfaceDark,
    onSurfaceVariant = NormalOnSurfaceVariantDark,
    outline = NormalOutlineDark,
)

private val EinkLightColorScheme = lightColorScheme(
    primary = EinkBlack,
    onPrimary = EinkWhite,
    primaryContainer = EinkLightGrey,
    onPrimaryContainer = EinkBlack,
    background = EinkWhite,
    surface = EinkWhite,
    surfaceVariant = EinkLightGrey,
    onSurface = EinkBlack,
    onSurfaceVariant = EinkDarkGrey,
    outline = EinkBlack,
)

private val EinkDarkColorScheme = darkColorScheme(
    primary = EinkWhite,
    onPrimary = EinkBlack,
    primaryContainer = EinkDarkGrey,
    onPrimaryContainer = EinkWhite,
    background = EinkBlack,
    surface = EinkBlack,
    surfaceVariant = EinkDarkGrey,
    onSurface = EinkWhite,
    onSurfaceVariant = EinkLightGrey,
    outline = EinkWhite,
)

@Composable
fun PutzTheme(
    category: AppCategory = AppCategory.NORMAL,
    mode: AppMode = AppMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val isDark = when (mode) {
        AppMode.LIGHT -> false
        AppMode.DARK -> true
        AppMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        category == AppCategory.EINK && isDark -> EinkDarkColorScheme
        category == AppCategory.EINK -> EinkLightColorScheme
        isDark -> NormalDarkColorScheme
        else -> NormalLightColorScheme
    }

    val styling = AppStyling(category, mode)

    CompositionLocalProvider(LocalAppStyling provides styling) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PutzTypography,
            content = content,
        )
    }
}
