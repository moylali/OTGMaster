package app.fayaz.otgmaster.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.fayaz.otgmaster.MountedDrive
import app.fayaz.otgmaster.OtgMasterState
import app.fayaz.otgmaster.R
import app.fayaz.otgmaster.backup.Format
import app.fayaz.otgmaster.backup.Paths
import app.fayaz.otgmaster.backup.SafFileTree
import app.fayaz.otgmaster.backup.VolumeInfo
import app.fayaz.otgmaster.provider.VeraCryptDocumentProvider
import app.fayaz.otgmaster.recovery.DeletedFile
import app.fayaz.otgmaster.recovery.ExFatRecovery
import app.fayaz.otgmaster.recovery.FatRecovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Whether [drive]'s filesystem can be scanned for deleted files; null if it can, else why not. */
fun recoveryUnavailable(drive: MountedDrive, notSupported: String): String? =
    if (drive.blockDevice != null && (drive.filesystemName.equals("FAT32", true) || drive.filesystemName.equals("exFAT", true))) null
    else notSupported

private interface Scanner {
    fun scan(cancel: () -> Boolean): List<DeletedFile>
    fun read(f: DeletedFile, out: OutputStream, limit: Long = Long.MAX_VALUE)
}

private fun scannerFor(drive: MountedDrive): Scanner {
    val dev = drive.blockDevice ?: throw IllegalStateException("no block device")
    return if (drive.filesystemName.equals("exFAT", true)) ExFatRecovery(dev).let { r ->
        object : Scanner {
            override fun scan(cancel: () -> Boolean) = r.scan(cancel)
            override fun read(f: DeletedFile, out: OutputStream, limit: Long) = r.read(f, out, limit)
        }
    } else FatRecovery(dev).let { r ->
        object : Scanner {
            override fun scan(cancel: () -> Boolean) = r.scan(cancel)
            override fun read(f: DeletedFile, out: OutputStream, limit: Long) = r.read(f, out, limit)
        }
    }
}

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")
private val VIDEO_EXT = setOf("mp4", "mov", "3gp", "mkv", "webm", "avi")
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus")
private fun ext(path: String) = path.substringAfterLast('.', "").lowercase()

/**
 * A small preview of a deleted photo, read from the drive without writing to it: the
 * EXIF thumbnail a camera stores in the first few kilobytes when there is one, else the
 * whole file decoded at a reduced size (photos up to [MAX_DECODE_BYTES] only).
 */
private fun thumbnail(scanner: Scanner, f: DeletedFile, lockId: String): Bitmap? {
    if (ext(f.path) !in IMAGE_EXT || f.state == DeletedFile.State.OVERWRITTEN || f.size == 0L) return null
    fun bytes(limit: Long) = ByteArrayOutputStream().also { o ->
        VeraCryptDocumentProvider.onDrive(lockId, "recover-thumb") { scanner.read(f, o, limit) }
    }.toByteArray()
    val head = bytes(EXIF_BYTES)
    runCatching {
        android.media.ExifInterface(java.io.ByteArrayInputStream(head)).thumbnailBitmap
    }.getOrNull()?.let { return it }
    if (f.size > MAX_DECODE_BYTES) return null
    val all = if (f.size <= EXIF_BYTES) head else bytes(f.size)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(all, 0, all.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= THUMB_PX && bounds.outHeight / (sample * 2) >= THUMB_PX) sample *= 2
    return BitmapFactory.decodeByteArray(all, 0, all.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

private const val EXIF_BYTES = 128L * 1024
private const val MAX_DECODE_BYTES = 16L * 1024 * 1024
private const val THUMB_PX = 160

/**
 * "Recover deleted files…": lists the files this partition's filesystem has deleted (only
 * those: files still on the drive are browsed in the Files app as usual), with photos
 * previewed, and copies the chosen ones to a folder somewhere else. Nothing is written to
 * this partition: writing here could land on the very clusters being recovered.
 */
@Composable
fun RecoveryDialog(drive: MountedDrive, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<DeletedFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val cancel = remember { AtomicBoolean(false) }
    val scanner = remember { runCatching { scannerFor(drive) } }
    val thumbs = remember { ConcurrentHashMap<String, Bitmap>() }
    val lockId = "${drive.id}:/"
    DisposableEffect(Unit) { onDispose { cancel.set(true) } }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            runCatching { VeraCryptDocumentProvider.onDrive(lockId, "recover-scan") { scanner.getOrThrow().scan { cancel.get() } } }
        }
        result.onSuccess { list ->
            files = list
            selected = list.filter { it.state == DeletedFile.State.RECOVERABLE }.map { it.path }.toSet()
            OtgMasterState.log("Recovery scan of ${drive.name}: ${list.size} deleted file(s), " +
                "${list.count { it.state == DeletedFile.State.RECOVERABLE }} recoverable")
        }.onFailure {
            error = "${it.javaClass.simpleName}: ${it.message}"
            OtgMasterState.logFailure("recovery", "Recovery scan of ${drive.name} failed", it)
        }
    }

    val pickTarget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (VolumeInfo.otgDriveId(uri) == drive.id) {
            status = context.getString(R.string.recovery_same_drive)
            return@rememberLauncherForActivityResult
        }
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val chosen = files.orEmpty().filter { it.path in selected }
        busy = true
        Thread {
            val tree = SafFileTree(context.contentResolver, uri)
            val folder = "Recovered ${Format.dateTime(System.currentTimeMillis()).replace(':', '.')}"
            var ok = 0
            val failed = ArrayList<String>()
            for ((i, f) in chosen.withIndex()) {
                if (cancel.get()) break
                status = context.getString(R.string.recovery_progress, i + 1, chosen.size)
                runCatching {
                    tree.openWrite(Paths.join(folder, f.path)).use { out ->
                        VeraCryptDocumentProvider.onDrive(lockId, "recover-read") { scanner.getOrThrow().read(f, out) }
                    }
                }.onSuccess { ok++ }.onFailure { failed += f.path; OtgMasterState.logFailure("recovery", "Recovering a file failed", it) }
            }
            OtgMasterState.log("Recovered $ok of ${chosen.size} file(s) from ${drive.name} to $folder" +
                if (failed.isNotEmpty()) "; ${failed.size} failed" else "")
            status = context.getString(R.string.recovery_done, ok, chosen.size, folder)
            busy = false
        }.start()
    }

    // Full screen, so a long list and long paths are never cut off.
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.recovery_title, drive.name), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f))
                    IconButton(enabled = !busy, onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                }
                val list = files
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    list == null -> Text(stringResource(R.string.recovery_scanning))
                    list.isEmpty() -> Text(stringResource(R.string.recovery_none))
                    else -> {
                        Text(stringResource(R.string.recovery_help), style = MaterialTheme.typography.bodySmall)
                        val selectable = list.filter { it.state != DeletedFile.State.OVERWRITTEN }.map { it.path }.toSet()
                        val allOn = selectable.isNotEmpty() && selected.containsAll(selectable)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.recovery_count, selected.size, list.size),
                                style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            TextButton(enabled = !busy, onClick = { selected = if (allOn) emptySet() else selectable },
                                modifier = Modifier.semantics { contentDescription = "recovery_select_all" }) {
                                Text(stringResource(if (allOn) R.string.recovery_select_none else R.string.recovery_select_all))
                            }
                        }
                        HorizontalDivider()
                        LazyColumn(Modifier.weight(1f)) {
                            items(list, key = { it.path }) { f ->
                                val canSelect = f.state != DeletedFile.State.OVERWRITTEN
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = canSelect && !busy) {
                                        selected = if (f.path in selected) selected - f.path else selected + f.path
                                    }.padding(vertical = 6.dp),
                                ) {
                                    Checkbox(f.path in selected, null, enabled = canSelect)
                                    Preview(f, thumbs, scanner.getOrNull(), lockId)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(Paths.name(f.path), style = MaterialTheme.typography.bodyMedium)
                                        Paths.parent(f.path).takeIf { it.isNotEmpty() }?.let {
                                            Text("/$it", style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(listOfNotNull(Format.bytes(f.size), f.modifiedMillis?.let { Format.dateTime(it) },
                                            when (f.state) {
                                                DeletedFile.State.RECOVERABLE -> null
                                                DeletedFile.State.PARTLY_OVERWRITTEN -> stringResource(R.string.recovery_partly)
                                                DeletedFile.State.OVERWRITTEN -> stringResource(R.string.recovery_overwritten)
                                            }).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                Button(enabled = !busy && selected.isNotEmpty(), onClick = { pickTarget.launch(null) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = "recovery_recover" }) {
                    Text(stringResource(R.string.recovery_recover, selected.size))
                }
            }
        }
    }
}

/** The photo's thumbnail once it is read (cached per path), else an icon for the file's kind. */
@Composable
private fun Preview(f: DeletedFile, cache: ConcurrentHashMap<String, Bitmap>, scanner: Scanner?, lockId: String) {
    val bitmap by produceState(cache[f.path], f.path) {
        if (value == null && scanner != null && ext(f.path) in IMAGE_EXT) {
            value = withContext(Dispatchers.IO) {
                runCatching { thumbnail(scanner, f, lockId) }.getOrNull()?.also { cache[f.path] = it }
            }
        }
    }
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(when (ext(f.path)) {
            in IMAGE_EXT -> Icons.Default.ImageIcon
            in VIDEO_EXT -> Icons.Default.Movie
            in AUDIO_EXT -> Icons.Default.MusicNote
            else -> Icons.Default.Description
        }, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
