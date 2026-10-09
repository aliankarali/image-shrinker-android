package app.shrinkerpro.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Brand colours from the desktop app's indigo-to-cyan icon, used where the
// device doesn't offer Material You dynamic colour.
private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2DFFF),
    onPrimaryContainer = Color(0xFF14006E),
    secondary = Color(0xFF0E7490),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEFFA),
    onSecondaryContainer = Color(0xFF001F28),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC3C0FF),
    onPrimary = Color(0xFF26138F),
    primaryContainer = Color(0xFF3E33C2),
    onPrimaryContainer = Color(0xFFE2DFFF),
    secondary = Color(0xFF7DD3EA),
    onSecondary = Color(0xFF003542),
    secondaryContainer = Color(0xFF004E5F),
    onSecondaryContainer = Color(0xFFCDEFFA),
)

@Composable
fun ShrinkerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
