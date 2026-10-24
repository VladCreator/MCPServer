package com.vlad.mcpserver

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vlad.mcpserver.mcp.ServerStatus
import com.vlad.mcpserver.mcp.ToolApprovalRequest
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Minimal Material 3 dashboard for the local MCP server.
 *
 * The main column is centered and width-limited on tablets. Secondary
 * information (settings, endpoints, fingerprints) lives in bottom sheets so the
 * dashboard only shows what is actionable right now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(viewModel: AppViewModel = viewModel()) {
    val status by viewModel.serverStatus.collectAsState()
    val connectedClients by viewModel.connectedClients.collectAsState()
    val pendingClients by viewModel.pendingClients.collectAsState()
    val executionLogs by viewModel.executionLogs.collectAsState()
    val toolApprovalRequests by viewModel.toolApprovalRequests.collectAsState()

    val clipboardManager = LocalClipboardManager.current

    var showSettings by remember { mutableStateOf(false) }
    var showConnection by remember { mutableStateOf(false) }
    var showAllLogs by remember { mutableStateOf(false) }

    val copy: (String, String) -> Unit = { _, value ->
        clipboardManager.setText(AnnotatedString(value))
    }

    McpTheme(darkTheme = viewModel.isDarkMode) {
        SystemBarAppearance(useDarkIcons = !viewModel.isDarkMode)

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 900.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "header") {
                        AppHeader(
                            status = status,
                            onSettings = { showSettings = true }
                        )
                    }

                    item(key = "server") {
                        ServerCard(
                            status = status,
                            agentCount = connectedClients.size,
                            onStart = viewModel::startServer,
                            onStop = viewModel::stopServer,
                            onConnect = { showConnection = true }
                        )
                    }

                    if (toolApprovalRequests.isNotEmpty()) {
                        item(key = "tool-approvals") {
                            ToolApprovalCard(
                                requests = toolApprovalRequests,
                                onResolve = viewModel::resolveToolApproval
                            )
                        }
                    }

                    if (pendingClients.isNotEmpty()) {
                        item(key = "pending") {
                            PendingClientsCard(
                                clients = pendingClients,
                                onApprove = viewModel::approveClient,
                                onReject = viewModel::rejectClient
                            )
                        }
                    }

                    if (connectedClients.isNotEmpty()) {
                        item(key = "agents") {
                            ConnectedAgentsCard(clients = connectedClients)
                        }
                    }

                    if (executionLogs.isNotEmpty()) {
                        item(key = "activity") {
                            ActivityCard(
                                logs = executionLogs,
                                showAll = showAllLogs,
                                onToggleAll = { showAllLogs = !showAllLogs }
                            )
                        }
                    }
                }
            }
        }

        if (showSettings) {
            SettingsSheet(
                viewModel = viewModel,
                status = status,
                onDismiss = { showSettings = false },
                onCopy = copy
            )
        }

        if (showConnection) {
            ConnectionSheet(
                viewModel = viewModel,
                status = status,
                onDismiss = { showConnection = false },
                onCopy = copy
            )
        }
    }
}

@Composable
private fun AppHeader(
    status: ServerStatus,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "MCP Server",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                StatusPill(status)
            }
            Text(
                text = "Local device tools for AI agents",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onSettings) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusPill(status: ServerStatus) {
    val colors = MaterialTheme.colorScheme
    val warning = LocalMcpExtraColors.current.warning
    val color = when (status) {
        is ServerStatus.Running -> colors.tertiary
        is ServerStatus.Starting -> warning
        is ServerStatus.Failed -> colors.error
        else -> colors.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(color, CircleShape)
            )
            Text(
                text = statusLabel(status),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ServerCard(
    status: ServerStatus,
    agentCount: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onConnect: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (status) {
                is ServerStatus.Running -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(colors.tertiary.copy(alpha = 0.12f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Dns,
                                contentDescription = null,
                                tint = colors.tertiary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text(
                                text = "Server is live",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "${if (status.secure) "HTTPS" else "HTTP"} · ${status.port} · $agentCount ${if (agentCount == 1) "agent" else "agents"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        FilledTonalButton(
                            onClick = onConnect,
                            shape = MaterialTheme.shapes.small,
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Link,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Connect", style = MaterialTheme.typography.labelMedium)
                        }
                        FilledTonalIconButton(
                            onClick = onStop,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Stop,
                                contentDescription = "Stop server",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                is ServerStatus.Starting -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Starting server…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                }

                is ServerStatus.Failed -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = colors.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Couldn't start the server",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = status.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Button(onClick = onStart, shape = MaterialTheme.shapes.small) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Retry", style = MaterialTheme.typography.labelMedium)
                    }
                }

                is ServerStatus.Unsupported -> {
                    Text(
                        text = "The embedded server is only available on Android.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }

                else -> {
                    Text(
                        text = "Turn this device into an MCP server",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Expose camera, touch, files and notifications to AI clients on your local network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    Button(onClick = onStart, shape = MaterialTheme.shapes.small) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Start server", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolApprovalCard(
    requests: List<ToolApprovalRequest>,
    onResolve: (String, Boolean) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val warning = LocalMcpExtraColors.current.warning
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = warning.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, warning.copy(alpha = 0.30f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = warning,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Tool approval",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = requests.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = warning
                )
            }
            requests.forEach { request ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = request.toolName,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "${request.clientName} · ${request.arguments}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { onResolve(request.id, true) },
                            shape = MaterialTheme.shapes.small,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Approve", style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = { onResolve(request.id, false) }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = colors.error
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Reject",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingClientsCard(
    clients: List<AiClient>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val warning = LocalMcpExtraColors.current.warning
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Devices,
                    contentDescription = null,
                    tint = warning,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Connection requests",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = clients.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = warning
                )
            }
            clients.forEach { client ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = client.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = listOfNotNull(
                                client.remoteAddress,
                                client.id.take(8)
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    FilledTonalButton(
                        onClick = { onApprove(client.id) },
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Approve", style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = { onReject(client.id) }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Reject",
                            tint = colors.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedAgentsCard(clients: List<AiClient>) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Devices,
                    contentDescription = null,
                    tint = colors.tertiary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Connected agents",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = clients.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
            clients.forEachIndexed { index, client ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(colors.tertiary, CircleShape)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = client.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = listOfNotNull(
                                client.remoteAddress,
                                client.status
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (index < clients.lastIndex) {
                    HorizontalDivider(color = colors.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(
    logs: List<ToolExecutionLog>,
    showAll: Boolean,
    onToggleAll: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val visible = if (showAll) logs.take(20) else logs.take(4)
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Activity",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (logs.size > 4) {
                    TextButton(onClick = onToggleAll) {
                        Text(
                            text = if (showAll) "Show less" else "Show all",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            visible.forEachIndexed { index, log ->
                ActivityRow(log)
                if (index < visible.lastIndex) {
                    HorizontalDivider(color = colors.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(log: ToolExecutionLog) {
    val colors = MaterialTheme.colorScheme
    val warning = LocalMcpExtraColors.current.warning
    val statusColor = when (log.status) {
        "SUCCESS" -> colors.tertiary
        "WARNING" -> warning
        else -> colors.error
    }
    var expanded by remember(log.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(statusColor, CircleShape)
            )
            Text(
                text = log.toolName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatLogTime(log.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 4.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = log.arguments,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                log.result?.takeIf { it.isNotBlank() }?.let { result ->
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    viewModel: AppViewModel,
    status: ServerStatus,
    onDismiss: () -> Unit,
    onCopy: (String, String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tokenVisible by remember { mutableStateOf(false) }
    var copiedLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copiedLabel) {
        if (copiedLabel != null) {
            delay(1_500)
            copiedLabel = null
        }
    }

    val copyAndNotify: (String, String) -> Unit = { label, value ->
        onCopy(label, value)
        copiedLabel = label
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.onSurface,
        sheetMaxWidth = 640.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            SheetHeader(title = "Settings", onDismiss = onDismiss)
            copiedLabel?.let { label ->
                Text(
                    text = "$label copied",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.tertiary
                )
            }
            SectionLabel("Access")
            Text(
                text = "Bearer token",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 6.dp)
            )
            OutlinedTextField(
                value = viewModel.bearerToken,
                onValueChange = viewModel::updateBearerToken,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                visualTransformation = if (tokenVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            imageVector = if (tokenVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (tokenVisible) "Hide token" else "Show token"
                        )
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { viewModel.generateSecureToken() }) {
                    Icon(
                        imageVector = Icons.Rounded.Autorenew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Generate", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { copyAndNotify("Token", viewModel.bearerToken) }) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Copy", style = MaterialTheme.typography.labelMedium)
                }
            }
            viewModel.tokenWarning?.let { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMcpExtraColors.current.warning
                )
            }
            viewModel.storageError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }

            SheetDivider()
            SectionLabel("Security")
            SwitchRow(
                title = "Confirm sensitive tools",
                subtitle = "Ask before camera, touch and file reads.",
                checked = viewModel.confirmSensitiveTools,
                onCheckedChange = viewModel::updateConfirmSensitiveTools
            )
            SwitchRow(
                title = "HTTPS / TLS",
                subtitle = "Requires restarting the server.",
                checked = viewModel.sslEnabled,
                onCheckedChange = viewModel::updateSslEnabled,
                enabled = status !is ServerStatus.Running && status !is ServerStatus.Starting
            )
            SwitchRow(
                title = "Dark theme",
                subtitle = "Use the deep obsidian palette.",
                checked = viewModel.isDarkMode,
                onCheckedChange = viewModel::updateDarkMode
            )

            SheetDivider()
            SectionLabel("Device access")
            ActionRow(
                title = "Touch injection",
                subtitle = "Required for the touch_test tool.",
                action = "Open",
                onClick = viewModel::openTouchAccessibilitySettings
            )

            SheetDivider()
            Text(
                text = "MCPServer 0.0.1 · Streamable HTTP, SSE and local tools",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionSheet(
    viewModel: AppViewModel,
    status: ServerStatus,
    onDismiss: () -> Unit,
    onCopy: (String, String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showWebSocket by remember { mutableStateOf(false) }
    var showFingerprint by remember { mutableStateOf(false) }
    var copiedLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copiedLabel) {
        if (copiedLabel != null) {
            delay(1_500)
            copiedLabel = null
        }
    }

    val copyAndNotify: (String, String) -> Unit = { label, value ->
        onCopy(label, value)
        copiedLabel = label
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.onSurface,
        sheetMaxWidth = 640.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SheetHeader(title = "Connect a client", onDismiss = onDismiss)
            copiedLabel?.let { label ->
                Text(
                    text = "$label copied",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.tertiary
                )
            }
            Text(
                text = "Point your MCP client at the Streamable HTTP endpoint and send the bearer token.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )

            EndpointRow(
                label = "Streamable HTTP",
                value = viewModel.connectionUrl,
                onCopy = { copyAndNotify("Endpoint", it) }
            )
            EndpointRow(
                label = "SSE (legacy)",
                value = viewModel.sseUrl,
                onCopy = { copyAndNotify("SSE endpoint", it) }
            )

            TextButton(onClick = { showWebSocket = !showWebSocket }) {
                Text(
                    text = if (showWebSocket) "Hide legacy WebSocket" else "Show legacy WebSocket",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (showWebSocket) {
                EndpointRow(
                    label = "WebSocket (legacy)",
                    value = viewModel.wsUrl,
                    onCopy = { copyAndNotify("WebSocket endpoint", it) }
                )
            }

            HorizontalDivider(color = colors.outlineVariant)
            Text(
                text = "Authorization",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Bearer <token>",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { copyAndNotify("Token", viewModel.bearerToken) }) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Copy token", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (status is ServerStatus.Running && status.fingerprint != null) {
                HorizontalDivider(color = colors.outlineVariant)
                TextButton(onClick = { showFingerprint = !showFingerprint }) {
                    Text(
                        text = if (showFingerprint) "Hide TLS fingerprint" else "TLS fingerprint",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (showFingerprint) {
                    Surface(
                        color = colors.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = status.fingerprint,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { copyAndNotify("Fingerprint", status.fingerprint) }) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy fingerprint",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = "The client must keep the request open while you approve the connection.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EndpointRow(
    label: String,
    value: String,
    onCopy: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { onCopy(value) }) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = "Copy $label",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun SheetHeader(
    title: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Close"
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun SheetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                disabledCheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                disabledUncheckedThumbColor = MaterialTheme.colorScheme.outline,
                disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    action: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Rounded.TouchApp,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(action, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun statusLabel(status: ServerStatus): String = when (status) {
    is ServerStatus.Running -> "Live"
    is ServerStatus.Starting -> "Starting"
    is ServerStatus.Failed -> "Error"
    is ServerStatus.Unsupported -> "N/A"
    else -> "Off"
}

private fun formatLogTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "--:--"
    return Instant.fromEpochMilliseconds(epochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .time
        .toString()
        .take(5)
}
