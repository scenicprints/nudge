package com.nudge.assistant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The whole palette. Grey on grey, and colour that carries one meaning: this needs you now.
 *
 * [now] is the app's own accent, for the line under the title and the count on the widget.
 * [reply], [leave] and [bill] say which of the three a nudge is. A nudge that is only coming
 * is drawn in grey and takes its colour when it speaks up, so colour never appears as
 * decoration: where you see it, something is waiting.
 *
 * The dark set is bright enough to carry on the dark panel and the light set dark enough to
 * carry on white, which is also what lets [ground] be used for text on top of any of them.
 */
data class Palette(
    val ground: Color,
    val panel: Color,
    val line: Color,
    val ink: Color,
    val dim: Color,
    val now: Color,
    val onNow: Color,
    val reply: Color,
    val leave: Color,
    val bill: Color
)

private val Dark = Palette(
    ground = Color(0xFF0F1012),
    panel = Color(0xFF191B1F),
    line = Color(0xFF2B2E34),
    ink = Color(0xFFECEBE6),
    dim = Color(0xFF9A9CA3),
    now = Color(0xFFFFB23E),
    onNow = Color(0xFF1B1300),
    reply = Color(0xFF5AA9F0),
    leave = Color(0xFFB693F7),
    bill = Color(0xFF7CC95F)
)

private val Light = Palette(
    ground = Color(0xFFF4F3EF),
    panel = Color(0xFFFFFFFF),
    line = Color(0xFFDDDBD4),
    ink = Color(0xFF17181B),
    dim = Color(0xFF63656C),
    now = Color(0xFF9A5B00),
    onNow = Color(0xFFFFFFFF),
    reply = Color(0xFF155E99),
    leave = Color(0xFF5B35A6),
    bill = Color(0xFF2E7320)
)

val LocalPalette = staticCompositionLocalOf { Dark }

object Type {
    val title = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
    val head = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.9.sp)
    val button = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun NudgeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.ink, onPrimary = p.ground, background = p.ground, onBackground = p.ink,
            surface = p.panel, onSurface = p.ink, onSurfaceVariant = p.dim, outline = p.line,
            surfaceVariant = p.line
        )
    } else {
        lightColorScheme(
            primary = p.ink, onPrimary = p.ground, background = p.ground, onBackground = p.ink,
            surface = p.panel, onSurface = p.ink, onSurfaceVariant = p.dim, outline = p.line,
            surfaceVariant = p.line
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = Typography(bodyLarge = Type.body), content = content)
    }
}
