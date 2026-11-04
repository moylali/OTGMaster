package app.fayaz.otgmaster.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The green or red note that slides in for a moment after a mount, an unmount or a failure.
 *
 * [onDismiss] gets the message whose time is up, and must clear it only if that is still the one
 * showing: a new message cancels this timer at the next recomposition, and until then the old timer
 * can still fire. That lost "Check the cable" to the end of a "Mounted:" note.
 */
@Composable
fun StatusToast(message: String?, isSuccess: Boolean, onDismiss: (String) -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier,
    ) {
        message?.let { msg ->
            LaunchedEffect(msg) {
                delay(2500)
                onDismiss(msg)
            }
            Surface(
                color = if (isSuccess) Color(0xFF2E7D32) else Color(0xFFC62828),
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 6.dp,
            ) {
                Text(
                    text = msg,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
