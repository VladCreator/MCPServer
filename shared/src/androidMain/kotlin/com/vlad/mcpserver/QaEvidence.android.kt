package com.vlad.mcpserver

import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.app.Activity
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.ext.gfm.tables.TablesExtension

private data class EvidencePreview(
    val title: String,
    val text: String? = null,
    val image: ImageBitmap? = null,
    val markdown: Boolean = false,
    val warning: String? = null
)
private data class EvidenceTransfer(val item: QaEvidence, val share: Boolean)

@Composable
actual fun QaEvidenceCard(modifier: Modifier, evidenceRevision: Int) {
    val context = LocalContext.current
    val repository = remember(context) { QaEvidenceRepository(context) }
    val scope = rememberCoroutineScope()
    val desktop = remember(context) { DesktopEvidenceClient(context) }
    var desktopDialog by remember { mutableStateOf(false) }
    var desktopStatus by remember { mutableStateOf("Desktop not paired") }
    var desktopActive by remember { mutableStateOf(false) }
    var sendFlow by remember { mutableStateOf<Long?>(null) }
    var sendEntries by remember { mutableStateOf<List<QaEvidence>>(emptyList()) }
    var sendDestination by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<QaEvidence>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var refreshRevision by remember { mutableStateOf(0) }
    var expandedDays by remember { mutableStateOf(emptySet<LocalDate>()) }
    var expandedFlows by remember { mutableStateOf(emptySet<Pair<LocalDate, Long?>>()) }
    var preview by remember { mutableStateOf<EvidencePreview?>(null) }
    var transfer by remember { mutableStateOf<EvidenceTransfer?>(null) }
    var downloadPath by rememberSaveable { mutableStateOf<String?>(null) }
    var downloadName by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    fun sendApprovedFlow(flow: Long) {
        val ids = sendEntries.map { it.id }.toSet()
        sendFlow = null
        busy = true
        scope.launch {
            try {
                notice = withContext(Dispatchers.IO) { desktop.sendFlow(repository, flow, ids) }
                desktopActive = true
                desktopStatus = "Tester Desktop active - evidence received."
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                desktopActive = false
                desktopStatus = "Desktop status unknown after Send failed."
                error = "Evidence Send failed: ${failure.localizedMessage}. Check Desktop inbox before retrying."
            } finally { busy = false }
        }
    }
    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = downloadPath
        val name = downloadName
        if (path == null) {
            error = "Unable to save evidence: the pending download is unavailable."
        } else {
            busy = true
            scope.launch {
                try {
                    val uri = result.data?.data.takeIf { result.resultCode == Activity.RESULT_OK }
                    withContext(Dispatchers.IO) { repository.finishDownload(path, uri) }
                    check(result.resultCode != Activity.RESULT_OK || uri != null) { "No download destination was returned." }
                    notice = if (uri == null) "Download cancelled." else "Saved $name."
                    error = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = "Unable to save evidence: ${failure.localizedMessage}. The destination may be incomplete."
                } finally {
                    downloadPath = null
                    downloadName = null
                    busy = false
                }
            }
        }
    }

    fun download(item: QaEvidence) {
        busy = true
        notice = null
        scope.launch {
            var staged: File? = null
            try {
                val file = withContext(Dispatchers.IO) { repository.stage(item) }
                staged = file
                downloadPath = file.path
                downloadName = item.name
                downloadLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = item.mime
                    putExtra(Intent.EXTRA_TITLE, item.name)
                })
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                staged?.let { file ->
                    if (file.exists() && !file.delete()) failure.addSuppressed(
                        IllegalStateException("Unable to remove temporary download.")
                    )
                }
                downloadPath = null
                downloadName = null
                error = "Unable to start evidence download: ${failure.localizedMessage}"
            } finally {
                busy = false
            }
        }
    }
    val actionsEnabled = !busy && downloadPath == null

    LaunchedEffect(desktop, desktopDialog) {
        while (true) {
            try {
                val status = withContext(Dispatchers.IO) {
                    if (desktop.pairing() == null) false to "Desktop not paired"
                    else true to desktop.status()
                }
                desktopActive = status.first
                desktopStatus = status.second
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                desktopActive = false
                desktopStatus = "Desktop unavailable: ${failure.localizedMessage}"
            }
            kotlinx.coroutines.delay(10_000)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshRevision++ }
    LaunchedEffect(refreshRevision, evidenceRevision) {
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
                            val text = readEvidenceText(file)
                            val formatted = if (item.mime == "application/json") prettyEvidenceJson(text) else text to null
                            EvidencePreview(item.name, text = formatted.first,
                                markdown = item.mime == "text/markdown", warning = formatted.second)
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

    Column(modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { desktopDialog = true }, enabled = actionsEnabled,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                Icon(Icons.Rounded.Link, contentDescription = "Desktop pairing")
            }
            IconButton(onClick = { refreshRevision++ }, enabled = !loading && !busy,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh QA Evidence")
            }
        }
            if (desktopActive) {
                val dark = isSystemInDarkTheme()
                Surface(
                    color = if (dark) Color(0xFF143525) else Color(0xFFDCFCE7),
                    contentColor = if (dark) Color(0xFF86EFAC) else Color(0xFF166534),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(desktopStatus, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
            } else Text(desktopStatus, style = MaterialTheme.typography.bodySmall)
            Text("Reports, retained run artifacts and saved media. Sharing sends a copy to an app you choose.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading || busy) CircularProgressIndicator(Modifier.size(24.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (!loading && entries.isEmpty() && error == null) {
                Text("No saved evidence yet. Record a QA step, request a screenshot, or retain a run artifact. Host reports are not stored here.",
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
                        val flowKey = day to flowId
                        val flowExpanded = flowKey in expandedFlows
                        item(key = "flow:$day:$flowId") {
                            Row(
                                Modifier.fillMaxWidth()
                                    .semantics { stateDescription = if (flowExpanded) "Expanded" else "Collapsed" }
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = if (flowExpanded) "Collapse flow" else "Expand flow"
                                    ) {
                                        expandedFlows = if (flowExpanded) expandedFlows - flowKey else expandedFlows + flowKey
                                    }
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(if (flowExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = null, modifier = Modifier.size(20.dp))
                                Text("${flowLabel(flowId)} (${flowEvidence.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                if (flowId != null) IconButton(
                                    modifier = Modifier.size(48.dp), enabled = actionsEnabled, onClick = {
                                    sendEntries = entries.filter { it.flowId == flowId }
                                    busy = true
                                    scope.launch {
                                        try {
                                            val (pair, status) = withContext(Dispatchers.IO) {
                                                val pairing = desktop.pairing()
                                                pairing to if (pairing == null) "Desktop not paired" else desktop.status(pairing)
                                            }
                                            sendFlow = flowId
                                            desktopActive = pair != null
                                            desktopStatus = status
                                            if (pair == null) desktopDialog = true else sendDestination = pair.url
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            desktopActive = false
                                            desktopStatus = "Desktop unavailable: ${failure.localizedMessage}"
                                            error = "Desktop unavailable: ${failure.localizedMessage}. Pair again using USB or Wi-Fi."
                                            sendFlow = flowId
                                            desktopDialog = true
                                        } finally { busy = false }
                                    }
                                }) { Icon(Icons.Rounded.Send, contentDescription = "Send Flow #$flowId to Desktop") }
                            }
                        }
                        if (flowExpanded) items(flowEvidence, key = { it.id }) { item ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                EvidenceFileIcon(item.mime)
                                Column(Modifier.weight(1f)
                                    .clickable(enabled = actionsEnabled, role = Role.Button,
                                        onClickLabel = "View ${item.name}") { view(item) }
                                    .heightIn(min = 48.dp),
                                    verticalArrangement = Arrangement.Center) {
                                    Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${item.origin} | ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.created))}",
                                        style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { transfer = EvidenceTransfer(item, share = true) },
                                    enabled = actionsEnabled,
                                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.tertiary)) {
                                    Icon(Icons.Rounded.Share, contentDescription = "Share ${item.name}",
                                        modifier = Modifier.size(14.4.dp))
                                }
                                IconButton(onClick = { download(item) }, enabled = actionsEnabled,
                                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.tertiary)) {
                                    Icon(Icons.Rounded.Download, contentDescription = "Download ${item.name}",
                                        modifier = Modifier.size(14.4.dp))
                                }
                            }
                        }
                    }
                }
        }
    }
    if (desktopDialog) DesktopPairingDialog(desktop, {
        desktopDialog = false
        sendFlow = null
    }, {
        desktopStatus = it
        desktopActive = true
        desktopDialog = false
        sendFlow?.let(::sendApprovedFlow)
    }, sendDescription = sendFlow?.let {
        "Confirming sends Flow #$it (${sendEntries.size} files) to this Desktop, including reports and photos. " +
            "Evidence may contain private project data. Originals stay here. Limit: 200 files / 100 MiB. No automatic retry."
    }, onForgot = {
        desktopActive = false
        desktopStatus = "Desktop not paired"
    })
    if (!desktopDialog) sendFlow?.let { flow ->
        AlertDialog(onDismissRequest = { sendFlow = null }, title = { Text("Send Flow #$flow to Desktop?") },
            text = {
                Text("Send all ${sendEntries.size} saved files for this flow, including reports and photos across dates, " +
                    "to $sendDestination. Evidence may contain private project data. Originals stay on this device. " +
                    "Maximum 200 files / 100 MiB. No automatic retry.")
            },
            dismissButton = { TextButton(onClick = { sendFlow = null }) { Text("Cancel") } },
            confirmButton = { TextButton(onClick = {
                sendApprovedFlow(flow)
            }) { Text("Send") } })
    }
    preview?.let { current ->
        Dialog(onDismissRequest = { preview = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(current.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { preview = null }) { Text("Close") }
                    }
                    current.warning?.let {
                        Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    }
                    if (current.markdown) {
                        MarkdownEvidencePreview(current.text.orEmpty(), Modifier.weight(1f).fillMaxWidth())
                    } else {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            current.image?.let { Image(it, contentDescription = current.title, modifier = Modifier.fillMaxWidth()) }
                            current.text?.let {
                                SelectionContainer {
                                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
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
private fun MarkdownEvidencePreview(text: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val background = colors.surface.toArgb()
    val foreground = colors.onSurface.toArgb()
    val html = remember(text, background, foreground) {
        val body = evidenceMarkdownHtml(text)
        val bg = "#%06x".format(background and 0xffffff)
        val fg = "#%06x".format(foreground and 0xffffff)
        """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
            <style>
            body { background:$bg; color:$fg; font:16px/1.6 sans-serif; padding:16px; margin:0; overflow-wrap:anywhere; }
            h1,h2,h3,h4 { line-height:1.3; } pre { overflow:auto; padding:12px; background:rgba(127,127,127,.15); }
            code { font-family:monospace; } blockquote { border-left:3px solid; margin-left:0; padding-left:16px; }
            table { border-collapse:collapse; display:block; overflow:auto; } th,td { border:1px solid; padding:8px; }
            a { color:inherit; text-decoration:underline; }
            </style></head><body>$body</body></html>"""
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(background)
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) =
                        WebResourceResponse("text/plain", "UTF-8", byteArrayOf().inputStream())
                }
            }
        },
        update = { view ->
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        onRelease = { it.destroy() }
    )
}

internal fun evidenceMarkdownHtml(text: String): String {
    val extensions = listOf(TablesExtension.create())
    val document = Parser.builder().extensions(extensions).build().parse(text)
    return HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true).build().render(document)
}

internal fun prettyEvidenceJson(text: String): Pair<String, String?> = try {
    val json = Json { prettyPrint = true }
    json.encodeToString(JsonElement.serializer(), json.parseToJsonElement(text)) to null
} catch (error: SerializationException) {
    text to "JSON could not be formatted (invalid or truncated content). Showing the original text."
} catch (error: IllegalArgumentException) {
    text to "JSON could not be formatted (invalid or truncated content). Showing the original text."
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
