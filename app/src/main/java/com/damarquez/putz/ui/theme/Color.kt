package com.damarquez.putz.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Put.io orange palette
val PutioOrange = Color(0xFFFF5722)
val PutioOrangeDark = Color(0xFFE64A19)
val PutioOrangeContainer = Color(0xFFFFDBD1)
val PutioOrangeOnContainer = Color(0xFF3E0900)

// Normal mode — light
val NormalPrimaryLight = PutioOrange
val NormalOnPrimaryLight = Color.White
val NormalPrimaryContainerLight = PutioOrangeContainer
val NormalOnPrimaryContainerLight = PutioOrangeOnContainer
val NormalBackgroundLight = Color(0xFFFFFBFF)
val NormalSurfaceLight = Color(0xFFFFFBFF)
val NormalSurfaceVariantLight = Color(0xFFF5DED8)
val NormalOnSurfaceLight = Color(0xFF201A19)
val NormalOnSurfaceVariantLight = Color(0xFF53433F)
val NormalOutlineLight = Color(0xFF85736F)

// Normal mode — dark
val NormalPrimaryDark = Color(0xFFFFB59C)
val NormalOnPrimaryDark = Color(0xFF5F1500)
val NormalPrimaryContainerDark = Color(0xFF862200)
val NormalOnPrimaryContainerDark = Color(0xFFFFDBD1)
val NormalBackgroundDark = Color(0xFF201A19)
val NormalSurfaceDark = Color(0xFF201A19)
val NormalSurfaceVariantDark = Color(0xFF53433F)
val NormalOnSurfaceDark = Color(0xFFEDE0DC)
val NormalOnSurfaceVariantDark = Color(0xFFD8C2BC)
val NormalOutlineDark = Color(0xFFA08C87)

// Success / Completion colors
val SuccessGreen = Color(0xFF4CAF50)
val SuccessGreenContainer = Color(0xFFE8F5E9)
val SuccessGreenDark = Color(0xFFB9F6CA)
val SuccessGreenContainerDark = Color(0xFF00391C)

// Pending-verification colors (daemon completed, not yet confirmed in library)
val PendingVerifyAmber = Color(0xFFF9A825)
val PendingVerifyContainer = Color(0xFFFFFDE7)
val PendingVerifyAmberDark = Color(0xFFFFE57F)
val PendingVerifyContainerDark = Color(0xFF3E2C00)

// E-ink (always high contrast black/white)
val EinkBlack = Color(0xFF000000)
val EinkWhite = Color(0xFFFFFFFF)
val EinkGrey = Color(0xFF808080)
val EinkLightGrey = Color(0xFFE0E0E0)
val EinkDarkGrey = Color(0xFF404040)

enum class AppCategory { NORMAL, EINK }
enum class AppMode { LIGHT, DARK, SYSTEM }

data class AppStyling(
    val category: AppCategory,
    val mode: AppMode,
) {
    val isEink: Boolean get() = category == AppCategory.EINK
    val cornerRadiusDp: Int get() = if (isEink) 0 else 12
    val useBorders: Boolean get() = isEink
    val useAnimations: Boolean get() = !isEink
}

val LocalAppStyling = staticCompositionLocalOf { AppStyling(AppCategory.NORMAL, AppMode.SYSTEM) }
