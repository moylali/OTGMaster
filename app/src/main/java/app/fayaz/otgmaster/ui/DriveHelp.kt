package app.fayaz.otgmaster.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.fayaz.otgmaster.DisconnectEvent
import app.fayaz.otgmaster.DisconnectedDrive
import app.fayaz.otgmaster.DriveTag
import app.fayaz.otgmaster.R
import app.fayaz.otgmaster.encryptionTag

/**
 * What is left of a mounted card when its drive drops: the name and tags, when it happened,
 * a link to what to do about it, and a way to clear it. No Open or Unmount — there is
 * nothing behind them.
 */
@Composable
fun DisconnectedDriveCard(drive: DisconnectedDrive, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "disconnected_card" },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = drive.title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val encTag = encryptionTag(drive.containerType)
                DriveTag(label = encTag.first, color = encTag.second)
                if (drive.filesystemName.isNotEmpty()) {
                    DriveTag(label = drive.filesystemName.uppercase(), color = Color(0xFF37474F))
                }
                DriveTag(label = stringResource(R.string.disconnected_tag), color = Color(0xFFC62828))
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(text = stringResource(R.string.disconnected_card_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = eventLine(drive.event), style = MaterialTheme.typography.bodySmall)
                    RecommendationsLink(drive.event)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "dismiss_disconnected_button" },
            ) {
                Text(stringResource(R.string.dismiss))
            }
        }
    }
}

/**
 * The disconnect history on a mounted card: a one-line summary that opens to the list.
 * Kept for the session so the record survives the drive coming back, which is when the
 * user is most likely to wonder what happened.
 */
@Composable
fun DisconnectHistory(events: List<DisconnectEvent>) {
    if (events.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp)
                .semantics { contentDescription = "disconnect_history" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null,
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.disconnect_history_summary, events.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 22.dp)) {
                events.forEach { event ->
                    Text(
                        text = eventLine(event),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                RecommendationsLink(events.first())
            }
        }
    }
}

/** "14:32, Oct 14 · on battery, 33%" in the phone's own time and date format. */
@Composable
private fun eventLine(event: DisconnectEvent): String {
    val whenText = DateUtils.formatDateTime(
        LocalContext.current, event.atMillis,
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
    )
    val power = when {
        event.externalPower -> stringResource(R.string.disconnect_power_external)
        event.batteryPercent != null -> stringResource(R.string.disconnect_power_battery_pct, event.batteryPercent)
        else -> stringResource(R.string.disconnect_power_battery)
    }
    return "$whenText · $power"
}

/** A small text link that opens the advice for a drive that dropped. */
@Composable
private fun RecommendationsLink(event: DisconnectEvent) {
    HelpLink { onClose -> DisconnectHelpDialog(event, onClose) }
}

/** The "See recommendations" link under "No USB drives available to mount". */
@Composable
fun NoDriveHelpLink() {
    HelpLink { onClose -> NoDriveHelpDialog(onClose) }
}

@Composable
private fun HelpLink(dialog: @Composable (onClose: () -> Unit) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(
        onClick = { open = true },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        modifier = Modifier.semantics { contentDescription = "see_recommendations" },
    ) {
        Text(stringResource(R.string.see_recommendations), style = MaterialTheme.typography.labelLarge)
    }
    if (open) dialog { open = false }
}

/**
 * What to do about a drive that dropped. The order follows what the phone saw: on battery,
 * power comes first, because that is what issue #50 turned out to be; on external power
 * the power supply is less likely, so the connection comes first.
 */
@Composable
private fun DisconnectHelpDialog(event: DisconnectEvent, onDismiss: () -> Unit) {
    val power = listOf(R.string.recommendation_hub, R.string.recommendation_enclosure)
    val steps = if (event.externalPower) {
        listOf(R.string.recommendation_cable) + power
    } else {
        power + R.string.recommendation_cable + R.string.recommendation_battery
    } + R.string.recommendation_check_drive + R.string.recommendation_replug
    HelpDialog(
        title = R.string.recommendations_title,
        onDismiss = onDismiss,
        sections = listOf(HelpSection(
            intro = if (event.externalPower) R.string.recommendations_intro_external
                    else R.string.recommendations_intro_power,
            bullets = steps,
        )),
    )
}

/**
 * Why a scan found nothing, and what the app can open once it does. The phone-specific
 * causes (OTG switch, charging through the port) are also logged by logNoDeviceHints when
 * they apply; this lists them for everyone, since the log is easy to miss.
 */
@Composable
private fun NoDriveHelpDialog(onDismiss: () -> Unit) {
    HelpDialog(
        title = R.string.no_drive_help_title,
        onDismiss = onDismiss,
        sections = listOf(
            HelpSection(
                intro = null,
                bullets = listOf(
                    R.string.no_drive_help_replug,
                    R.string.no_drive_help_adapter,
                    R.string.no_drive_help_otg_switch,
                    R.string.no_drive_help_charging,
                    R.string.no_drive_help_power,
                    R.string.no_drive_help_hardware_lock,
                    R.string.no_drive_help_rescan,
                ),
            ),
            HelpSection(
                heading = R.string.supported_heading,
                intro = null,
                bullets = listOf(
                    R.string.supported_encryption,
                    R.string.supported_filesystems,
                    R.string.supported_plain,
                ),
            ),
        ),
    )
}

private class HelpSection(
    val heading: Int? = null,
    val intro: Int?,
    val bullets: List<Int>,
)

@Composable
private fun HelpDialog(title: Int, onDismiss: () -> Unit, sections: List<HelpSection>) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
        title = { Text(stringResource(title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                sections.forEachIndexed { i, section ->
                    section.heading?.let {
                        Text(
                            stringResource(it),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = if (i == 0) 0.dp else 16.dp),
                        )
                    }
                    section.intro?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                    section.bullets.forEachIndexed { j, step ->
                        val first = j == 0 && section.heading == null && section.intro == null
                        Row(modifier = Modifier.padding(top = if (first) 0.dp else 8.dp)) {
                            Text("•", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(step), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
}
