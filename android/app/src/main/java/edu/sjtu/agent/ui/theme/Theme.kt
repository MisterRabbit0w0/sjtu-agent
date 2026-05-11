package edu.sjtu.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF8B1E2D),
    onPrimary = Color.White,
    secondary = Color(0xFF2F6152),
    tertiary = Color(0xFF5C4A8A),
    background = Color(0xFFF8F7F4),
    surface = Color(0xFFFFFBF7),
    surfaceVariant = Color(0xFFE9E2DD),
    outline = Color(0xFF80746D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB2BA),
    secondary = Color(0xFF9DD1C1),
    tertiary = Color(0xFFC8B8FF),
    background = Color(0xFF141211),
    surface = Color(0xFF1D1A18),
    surfaceVariant = Color(0xFF504640),
)

@Composable
fun SJTUAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
