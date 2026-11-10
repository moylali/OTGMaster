package app.fayaz.otgmaster.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.fayaz.otgmaster.R
import kotlin.math.roundToInt

/**
 * A one-time hint under the top bar's Drives icon, its arrow pointing at [anchor] (the
 * icon's bottom centre, in root coordinates): where the mounted drives went when the
 * slider closed. A tap on it dismisses it.
 */
@Composable
fun BoxScope.SliderHint(anchor: Offset, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val arrowW = 18.dp
    val arrowH = 9.dp
    val color = MaterialTheme.colorScheme.inverseSurface
    Box(Modifier.align(Alignment.TopStart).fillMaxWidth().offset { IntOffset(0, anchor.y.roundToInt()) }) {
        Canvas(Modifier
            .offset { IntOffset((anchor.x - with(density) { arrowW.toPx() } / 2).roundToInt(), 0) }
            .size(arrowW, arrowH)) {
            drawPath(Path().apply {
                moveTo(size.width / 2, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }, color)
        }
        Surface(
            color = color,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = arrowH, end = 8.dp, start = 24.dp)
                .widthIn(max = 280.dp)
                .semantics { contentDescription = "slider_hint" }
                .clickable(onClick = onDismiss),
        ) {
            Text(stringResource(R.string.slider_hint), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}
