package app.fayaz.otgmaster.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.fayaz.otgmaster.R

/** The two lists of what is mounted. A mounted backup goes under Backups, everything else under Drives. */
enum class MountedTab { DRIVES, BACKUPS }

/** A sideways drag this far towards the edge closes the slider. */
private val CLOSE_DRAG = 80.dp
/** A swipe this far to the left opens it. */
private val OPEN_DRAG = 48.dp

/**
 * The title, the two tabs and the chosen tab's cards, in the slider. Without [showBackupsTab]
 * there are no tabs, only the list.
 */
@Composable
fun MountedPanel(
    tab: MountedTab,
    onTab: (MountedTab) -> Unit,
    driveCount: Int,
    backupCount: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showBackupsTab: Boolean = true,
    cards: @Composable (MountedTab) -> Unit,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mounted_devices), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_mounted))
            }
        }
        if (showBackupsTab) TabRow(selectedTabIndex = tab.ordinal) {
            Tab(selected = tab == MountedTab.DRIVES, onClick = { onTab(MountedTab.DRIVES) },
                modifier = Modifier.semantics { contentDescription = "mounted_tab_drives" },
                text = { Text(stringResource(R.string.mounted_tab_drives, driveCount)) })
            Tab(selected = tab == MountedTab.BACKUPS, onClick = { onTab(MountedTab.BACKUPS) },
                modifier = Modifier.semantics { contentDescription = "mounted_tab_backups" },
                text = { Text(stringResource(R.string.mounted_tab_backups, backupCount)) })
        }
        // A tab opens at its top, not where the other one was scrolled to.
        key(tab) {
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) { cards(tab) }
        }
    }
}

/** A swipe to the left over [this] opens the slider. Vertical scrolling and taps are left alone. */
fun Modifier.swipeLeftOpens(onOpen: () -> Unit): Modifier = pointerInput(onOpen) {
    var dragged = 0f
    detectHorizontalDragGestures(
        onDragStart = { dragged = 0f },
        onDragEnd = { if (dragged < -OPEN_DRAG.toPx()) onOpen() },
    ) { _, amount -> dragged += amount }
}

/**
 * The part of the right edge where a swipe in opens the slider. Anywhere else on that edge the
 * system's Back gesture takes it, which is why this is excluded from it. Android allows 200 dp per
 * edge. It stays within the screen's margin, so it covers nothing that can be tapped.
 */
@Composable
fun BoxScope.MountedEdgeSwipe(onOpen: () -> Unit) {
    Box(
        Modifier.align(Alignment.CenterEnd).size(width = 16.dp, height = 200.dp)
            .systemGestureExclusion()
            .swipeLeftOpens(onOpen)
    )
}

/** Slides in from the right over the main screen. Back, a tap outside or a drag to the right closes it. */
@Composable
fun BoxScope.MountedDrawer(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = Modifier.align(Alignment.CenterEnd),
    ) {
        Surface(
            // Capped, or on a tablet in landscape the cards stretch across most of the screen.
            modifier = Modifier.fillMaxHeight().widthIn(max = 480.dp).fillMaxWidth(0.92f)
                .semantics { contentDescription = "mounted_drawer" }
                .pointerInput(Unit) {
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = { if (dragged > CLOSE_DRAG.toPx()) onDismiss() },
                    ) { _, amount -> dragged += amount }
                },
            tonalElevation = 4.dp,
        ) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)).padding(16.dp)) {
                content()
            }
        }
    }
}
