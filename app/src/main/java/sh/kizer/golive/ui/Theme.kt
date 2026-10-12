package sh.kizer.golive.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import sh.kizer.golive.Level

// kizer.sh's warm palette.
private val Light = lightColorScheme(
    primary = Color(0xFFB5501F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6DCCB),
    onPrimaryContainer = Color(0xFF4A1C05),
    background = Color(0xFFFBF7F0),
    onBackground = Color(0xFF221A16),
    surface = Color(0xFFFBF7F0),
    onSurface = Color(0xFF221A16),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF4ECE1),
    surfaceContainerHighest = Color(0xFFEEE4D6),
    onSurfaceVariant = Color(0xFF6E6058),
    outline = Color(0xFFCDBFAE),
    outlineVariant = Color(0xFFE8DCCB),
    secondaryContainer = Color(0xFFF6DCCB),
    onSecondaryContainer = Color(0xFF4A1C05),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF0A070),
    onPrimary = Color(0xFF1A1210),
    primaryContainer = Color(0xFF5C2A10),
    onPrimaryContainer = Color(0xFFF6DCCB),
    background = Color(0xFF1A1210),
    onBackground = Color(0xFFF2E6D6),
    surface = Color(0xFF1A1210),
    onSurface = Color(0xFFF2E6D6),
    surfaceContainer = Color(0xFF241A17),
    surfaceContainerHigh = Color(0xFF2C211D),
    surfaceContainerHighest = Color(0xFF362924),
    onSurfaceVariant = Color(0xFFB8A898),
    outline = Color(0xFF5A4A40),
    outlineVariant = Color(0xFF3A2C26),
    secondaryContainer = Color(0xFF5C2A10),
    onSecondaryContainer = Color(0xFFF6DCCB),
    error = Color(0xFFF2B8B5),
)

@Composable
fun GoLiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** Status colours, fixed in both themes and always shown with a label. */
fun Level.color(): Color = when (this) {
    Level.GOOD -> Color(0xFF0CA30C)
    Level.MODERATE -> Color(0xFFFAB219)
    Level.POOR -> Color(0xFFEC835A)
    Level.BAD -> Color(0xFFD03B3B)
}
