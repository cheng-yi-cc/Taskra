package com.taskra.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.taskra.R
import androidx.compose.ui.unit.sp

val NotebookHand = FontFamily(Font(R.font.wenkai_regular))

val TaskraTypography = Typography(
    headlineLarge = TextStyle(fontFamily = NotebookHand, fontSize = 36.sp, fontWeight = FontWeight.Normal, lineHeight = 42.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.Medium, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = NotebookHand, fontSize = 25.sp, fontWeight = FontWeight.Normal, lineHeight = 32.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 29.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
)
