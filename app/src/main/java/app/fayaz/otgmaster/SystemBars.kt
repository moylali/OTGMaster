package app.fayaz.otgmaster

import android.app.Activity
import androidx.core.view.WindowCompat

/**
 * Match the status and navigation bar icons to the in-app theme.
 *
 * The window theme sets `android:windowLightStatusBar`, which fixes the icons dark
 * whatever the app draws behind them — so with the in-app Dark theme the clock and
 * status icons were dark on a dark background. The in-app theme can differ from the
 * system's, so this has to follow the app's choice at runtime, not a values-night
 * resource.
 */
fun Activity.applySystemBarAppearance(darkTheme: Boolean) {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !darkTheme
        isAppearanceLightNavigationBars = !darkTheme
    }
}
