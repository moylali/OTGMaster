package app.fayaz.otgmaster

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * Draw edge-to-edge, with system bar icons that match the in-app theme.
 *
 * Android 15 forces edge-to-edge on apps targeting SDK 35+, and Play asks for
 * `enableEdgeToEdge()` so older versions draw the same way; the screens pad themselves
 * with window insets (Scaffold, the settings drawer, the toast, the IME).
 *
 * The icon colour follows the app's own theme choice, not the system's: with the
 * in-app Dark theme on a light system, dark icons on a dark background was the bug
 * fixed in d19f5b7. So this is called again whenever the in-app theme changes.
 */
fun ComponentActivity.applySystemBarAppearance(darkTheme: Boolean) {
    val style = if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    // Both bars transparent; the system adds its own scrim behind three-button
    // navigation and none behind the gesture handle. A light/dark style turns that off,
    // and a fixed scrim of our own showed as an undimmed strip under the settings drawer.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = true
}
