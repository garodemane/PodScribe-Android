package com.example.podscribe

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowInsetsControllerCompat

// Soft peach canvas with coral actions and violet secondary accents.
val Accent = Color(0xFFC74436)
val Lilac = Color(0xFF8B55C8)
val AccentSoft = Color(0xFFFF913F)
val LilacSoft = Color(0xFFFFB36B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(0xFFFFF0EF.toInt(), 0xFFFFF0EF.toInt()),
            navigationBarStyle = SystemBarStyle.light(0xFFFFF0EF.toInt(), 0xFFFFF0EF.toInt()),
        )
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            val vm: AppVM = viewModel()
            var darkTheme by rememberSaveable {
                mutableStateOf(Repo.prefs.getString("theme_mode", "light") == "dark")
            }
            val lightScheme = lightColorScheme(
                primary = Accent, onPrimary = Color(0xFF34130F),
                background = Color(0xFFFFF0EF), onBackground = Color(0xFF292331),
                surface = Color(0xFFFFFFFF), onSurface = Color(0xFF292331),
                surfaceVariant = Color(0xFFF3E9EE), onSurfaceVariant = Color(0xFF756B78),
                secondary = Lilac, onSecondary = Color.White,
                secondaryContainer = Color(0xFFF0E5FF), onSecondaryContainer = Color(0xFF513878),
                tertiary = Color(0xFFFFA06F), onTertiary = Color(0xFF3D1A10),
                tertiaryContainer = Color(0xFFFFE6D8), onTertiaryContainer = Color(0xFF63311E),
                outline = Color(0xFFE3D7DE), error = Color(0xFFB3263B),
            )
            val darkScheme = darkColorScheme(
                primary = AccentSoft, onPrimary = Color(0xFF321A0B),
                background = Color(0xFF111210), onBackground = Color(0xFFF5F2ED),
                surface = Color(0xFF1D1E1C), onSurface = Color(0xFFF5F2ED),
                surfaceVariant = Color(0xFF292A28), onSurfaceVariant = Color(0xFFBDBAB4),
                secondary = LilacSoft, onSecondary = Color(0xFF38200D),
                secondaryContainer = Color(0xFF3B2B1E), onSecondaryContainer = Color(0xFFFFDDBB),
                tertiary = Color(0xFFFFB96E), onTertiary = Color(0xFF3A200D),
                tertiaryContainer = Color(0xFF4A301E), onTertiaryContainer = Color(0xFFFFDCC0),
                outline = Color(0xFF4B4A46), error = Color(0xFFFFB4AB),
            )
            MaterialTheme(colorScheme = if (darkTheme) darkScheme else lightScheme, typography = Typography(
                headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
                headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
                titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
                titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
                titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
                bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
                bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
            ), shapes = Shapes(
                extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp),
                medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp),
                extraLarge = RoundedCornerShape(32.dp)
            )) {
                SideEffect {
                    WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !darkTheme
                    WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = !darkTheme
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PodApp(vm, darkTheme) { selected ->
                        darkTheme = selected
                        Repo.prefs.edit().putString("theme_mode", if (selected) "dark" else "light").apply()
                    }
                }
            }
        }
    }
}
