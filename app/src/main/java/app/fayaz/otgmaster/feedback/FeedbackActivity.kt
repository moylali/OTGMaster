@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.fayaz.otgmaster.feedback

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.fayaz.otgmaster.applySystemBarAppearance
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.fayaz.otgmaster.OtgMasterState
import app.fayaz.otgmaster.R

/**
 * Reviews what a bug report would contain, and opens the form with it.
 *
 * Its own activity rather than a row in the settings drawer: the drawer is a 300.dp
 * column, and this screen has to show the payload verbatim alongside a choice per
 * category. Cramming that into a drawer made both halves unreadable.
 *
 * Nothing is transmitted here. The screen builds a URL and hands it to a browser, so
 * submitting stays a deliberate act on the form itself — and the user can see the
 * address before it opens.
 */
class FeedbackActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // MainActivity applies MaterialTheme inline rather than exposing a named
            // theme composable, so match it here and read the same preference — a
            // report screen that ignored the user's dark-mode choice would be jarring.
            val prefs = getSharedPreferences("otgmaster_prefs", MODE_PRIVATE)
            val saved = prefs.getString("theme_mode", "SYSTEM")
            val dark = when (saved) {
                "LIGHT" -> false
                "DARK" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            androidx.compose.runtime.LaunchedEffect(dark) {
                with(this@FeedbackActivity) { applySystemBarAppearance(dark) }
            }
            androidx.compose.material3.MaterialTheme(
                colorScheme = if (dark) androidx.compose.material3.darkColorScheme()
                              else androidx.compose.material3.lightColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background
                ) { Screen() }
            }
        }
    }

    @Composable
    private fun Screen() {
        val uriHandler = LocalUriHandler.current
        val base = stringResource(R.string.feedback_url)

        // Snapshotted once, on entry. The log and mount state can change while this
        // screen is open, and the details shown must be the details sent — a payload
        // rebuilt at the moment of tapping could differ from what was reviewed.
        val contents = remember {
            FeedbackPayload.collect(
                OtgMasterState.mountedDrives.toList(),
                OtgMasterState.recentLogs(),
            )
        }
        var sel by remember { mutableStateOf(FeedbackPayload.Selection()) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.feedback_title)) },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = stringResource(R.string.feedback_back)
                            )
                        }
                    }
                )
            }
        ) { inner ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(R.string.feedback_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                Text(
                    stringResource(R.string.feedback_choose_heading),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))

                IncludeRow(stringResource(R.string.feedback_include_device), sel.device) {
                    sel = sel.copy(device = it)
                }
                IncludeRow(stringResource(R.string.feedback_include_version), sel.appVersion) {
                    sel = sel.copy(appVersion = it)
                }
                IncludeRow(stringResource(R.string.feedback_include_usb), sel.usb) {
                    sel = sel.copy(usb = it)
                }
                IncludeRow(stringResource(R.string.feedback_include_partitions), sel.partitions) {
                    sel = sel.copy(partitions = it)
                }
                IncludeRow(
                    stringResource(R.string.feedback_include_logs, contents.logLines.size),
                    sel.logs
                ) { sel = sel.copy(logs = it) }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Clears every box in one tap. Unticking it restores all of them rather
                // than the previous mixture: re-selecting should be deliberate, and
                // quietly reinstating a choice the user had just removed would be worse
                // than making them pick again.
                IncludeRow(stringResource(R.string.feedback_include_nothing), sel.nothing) { off ->
                    sel = if (off) FeedbackPayload.Selection.NONE else FeedbackPayload.Selection()
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.feedback_review_heading),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))

                // Verbatim, not summarised: consent to something unseen is not consent.
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        FeedbackPayload.summary(contents, sel),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .heightIn(min = 120.dp)
                            .padding(10.dp)
                    )
                }

                Text(
                    stringResource(R.string.feedback_excluded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Button(
                    onClick = {
                        uriHandler.openUri(FeedbackPayload.url(base, contents, sel))
                        finish()
                    },
                    // Always enabled: sending nothing is a legitimate choice, and the
                    // label states which of the two is about to happen.
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            if (sel.nothing) R.string.feedback_open_empty
                            else R.string.feedback_open
                        )
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun IncludeRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange(!checked) }
        ) {
            Checkbox(checked = checked, onCheckedChange = onChange)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun stringResource(id: Int, vararg args: Any): String =
        androidx.compose.ui.res.stringResource(id, *args)
}
