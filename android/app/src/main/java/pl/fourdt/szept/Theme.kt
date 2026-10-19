package pl.fourdt.szept

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Paleta „Sowa” – wspólna dla zestawu PLGPT. */
object Sowa {
    val cyan = Color(0xFF5CE6FF); val magenta = Color(0xFFDA6CFF); val violet = Color(0xFF6B4DFF); val violetDeep = Color(0xFF9D4EFF)
    val glow = Color(0xFF142A4A); val ground = Color(0xFF0A0E2B); val groundAlt = Color(0xFF1B103C); val card = Color(0xFF141A3E)
    val border = Color(0xFF2C356E); val textMuted = Color(0xFFA2ACDC); val ok = Color(0xFF4AE39E); val warn = Color(0xFFFFB04A)
    val backdrop = Brush.linearGradient(listOf(ground, groundAlt))
    val userBubble = Brush.linearGradient(listOf(violet, violetDeep))
    val action = Brush.linearGradient(listOf(magenta, violet))
}

private val scheme = darkColorScheme(
    primary = Sowa.cyan, onPrimary = Sowa.ground, secondary = Sowa.magenta, tertiary = Sowa.violet,
    background = Sowa.ground, onBackground = Color(0xFFF1F2FA), surface = Sowa.card, onSurface = Color(0xFFF1F2FA),
    surfaceVariant = Sowa.card, onSurfaceVariant = Sowa.textMuted, outline = Sowa.border, error = Color(0xFFFF6B8A),
)

@Composable
fun SzeptTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme) { Box(Modifier.fillMaxSize().background(Sowa.backdrop)) { content() } }
}
