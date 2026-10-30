package com.vlad.mcpserver

import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

private data class EvidencePreview(val title: String, val text: String? = null, val image: ImageBitmap? = null)
private data class EvidenceTransfer(val item: QaEvidence, val share: Boolean)

@Composable
actual fun QaEvidenceCard(modifier: Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { QaEvidenceRepository(context) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<QaEvidence>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    var expandedDays by remember { mutableStateOf(emptySet<LocalDate>()) }
    var preview by remember { mutableStateOf<EvidencePreview?>(null) }
    var transfer by remember { mutableStateOf<EvidenceTransfer?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    LaunchedEffect(revision) {
        loading = true
        try {
            entries = withContext(Dispatchers.IO) { repository.list() }
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = "Unable to load QA Evidence: ${failure.localizedMessage}"
        } finally {
            loading = false
        }
    }

    fun view(item: QaEvidence) {
        if (!item.mime.startsWith("image/") && !isEvidenceText(item.mime)) {
            transfer = EvidenceTransfer(item, share = false)
            return
        }
        busy = true
        scope.launch {
            try {
                preview = withContext(Dispatchers.IO) {
                    val file = repository.stage(item)
                    try {
                        if (isEvidenceText(item.mime)) {
                            EvidencePreview(item.name, text = readEvidenceText(file))
                        } else {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(file.path, bounds)
                            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image is corrupt or unsupported." }
                            val options = BitmapFactory.Options().apply {
                                inSampleSize = 1
                                while (bounds.outWidth / inSampleSize > 1600 || bounds.outHeight / inSampleSize > 1600) {
                                    inSampleSize *= 2
                                }
                            }
                            val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path, options)) { "Unable to decode image." }
                            EvidencePreview(item.name, image = bitmap.asImageBitmap())
                        }
                    } finally {
                        check(file.delete()) { "Unable to remove temporary preview." }
                    }
                }
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "Unable to preview evidence: ${failure.localizedMessage}"
            } finally {
                busy = false
            }
        }
    }

    OutlinedCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("QA Evidence", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { revision++ }, enabled = !loading && !busy) { Text("Refresh") }
            }
            Text("Reports, retained run artifacts and saved media. Sharing sends a copy to an app you choose.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading || busy) CircularProgressIndicator(Modifier.size(24.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (!loading && entries.isEmpty() && error == null) {
                Text("No saved evidence yet. Finish a QA session or retain a run artifact. Inline-only screenshots and host reports are not stored here.",
                    style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                groupEvidenceByDay(entries).forEach { (day, evidence) ->
                    val expanded = day in expandedDays
                    item(key = "day:$day") {
                        Row(
                            Modifier.fillMaxWidth()
                                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = if (expanded) "Collapse day" else "Expand day"
                                ) {
                                    expandedDays = if (expanded) expandedDays - day else expandedDays + day
                                }
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f))
                            Text("${evidence.size}", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (expanded) evidence.groupBy { it.flowId }.forEach { (flowId, flowEvidence) ->
                        item(key = "flow:$day:$flowId") {
                            Text("${flowLabel(flowId)} (${flowEvidence.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 4.dp))
                        }
                        items(flowEvidence, key = { it.id }) { item ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                EvidenceFileIcon(item.mime)
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${item.origin} | ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.created))}",
                                        style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = { view(item) }, enabled = !busy,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                    Text(if (isEvidenceText(item.mime) || item.mime.startsWith("image/")) "View" else "Open")
                                }
                                TextButton(onClick = { transfer = EvidenceTransfer(item, share = true) }, enabled = !busy,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                    Text("Share")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    preview?.let { current ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(current.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    current.image?.let { Image(it, contentDescription = current.title, modifier = Modifier.fillMaxWidth()) }
                    current.text?.let {
                        SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } }
        )
    }
    transfer?.let { request ->
        AlertDialog(
            onDismissRequest = { transfer = null },
            title = { Text(if (request.share) "Share evidence?" else "Open in another app?") },
            text = { Text("${request.item.name}\n\nEvidence may contain sensitive screen content or unredacted QA notes. Only continue if you trust the receiving app. Tester does not upload it automatically.") },
            dismissButton = { TextButton(onClick = { transfer = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = {
                    transfer = null
                    busy = true
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) { repository.stage(request.item) }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.qa-evidence", file)
                            val intent = Intent(if (request.share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
                                if (request.share) {
                                    type = request.item.mime
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                } else setDataAndType(uri, request.item.mime)
                                clipData = ClipData.newRawUri("QA Evidence", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, if (request.share) "Share QA Evidence" else "Open QA Evidence"))
                            error = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = "Unable to open/share evidence: ${failure.localizedMessage}"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Continue") }
            }
        )
    }
}

@Composable
private fun EvidenceFileIcon(mime: String) {
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when {
        mime.startsWith("image/") -> colors.tertiaryContainer to colors.onTertiaryContainer
        mime.startsWith("video/") || mime.startsWith("audio/") -> colors.secondaryContainer to colors.onSecondaryContainer
        mime == "application/pdf" -> colors.errorContainer to colors.onErrorContainer
        else -> colors.primaryContainer to colors.onPrimaryContainer
    }
    Box(Modifier.size(30.dp).background(background, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center) {
        Icon(evidenceIcon(mime), contentDescription = mime,
            modifier = Modifier.size(18.dp), tint = foreground)
    }
}

internal fun isEvidenceText(mime: String): Boolean =
    mime.startsWith("text/") || mime in setOf("application/json", "application/xml")

internal fun evidenceIcon(mime: String): ImageVector = when {
    mime.startsWith("image/") -> Icons.Rounded.Image
    mime.startsWith("video/") -> Icons.Rounded.VideoFile
    mime.startsWith("audio/") -> Icons.Rounded.AudioFile
    mime == "application/pdf" -> Icons.Rounded.PictureAsPdf
    mime in setOf("application/json", "application/xml", "text/xml") -> Icons.Rounded.Code
    else -> Icons.Rounded.Description
}

internal fun groupEvidenceByDay(
    entries: List<QaEvidence>,
    zone: ZoneId = ZoneId.systemDefault()
): Map<LocalDate, List<QaEvidence>> = entries.sortedByDescending { it.created }.groupBy {
    Instant.ofEpochMilli(it.created).atZone(zone).toLocalDate()
}

internal fun readEvidenceText(file: File): String {
    val bytes = file.inputStream().use { it.readNBytes(256 * 1024 + 1) }
    return bytes.take(256 * 1024).toByteArray().toString(Charsets.UTF_8) +
        if (bytes.size > 256 * 1024) "\n\n[Preview limited to 256 KiB. Share to read the complete file.]" else ""
}
