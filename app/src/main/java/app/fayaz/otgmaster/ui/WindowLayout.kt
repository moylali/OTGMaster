package app.fayaz.otgmaster.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A tablet or unfolded phone, or a phone-sized window in landscape wide enough for two panes. */
fun isTwoPaneWindow(width: Dp, height: Dp): Boolean = width >= 840.dp || (width > height && width >= 600.dp)
