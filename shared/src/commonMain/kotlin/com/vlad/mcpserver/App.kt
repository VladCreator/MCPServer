package com.vlad.mcpserver

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vlad.mcpserver.mcp.ServerStatus
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Main Composable presenting the MCP server dashboard, settings screen, and bottom connection status bar.
 */
@Composable
fun App(viewModel: AppViewModel = viewModel()) {
    val clipboardManager = LocalClipboardManager.current
    var showCopiedMessage by remember { mutableStateOf(false) }
    var showUrlCopiedMessage by remember { mutableStateOf(false) }

    val status by viewModel.serverStatus.collectAsState()
    val connectedClients by viewModel.connectedClients.collectAsState()
    val pendingClients by viewModel.pendingClients.collectAsState()
    val executionLogs by viewModel.executionLogs.collectAsState()

    val isRunning = status is ServerStatus.Running
    val isStarting = status is ServerStatus.Starting
    val lastError = (status as? ServerStatus.Failed)?.message

    LaunchedEffect(showCopiedMessage) {
        if (showCopiedMessage) {
            delay(1_500)
            showCopiedMessage = false
        }
    }
    LaunchedEffect(showUrlCopiedMessage) {
        if (showUrlCopiedMessage) {
            delay(1_500)
            showUrlCopiedMessage = false
        }
    }

    // Theme color palette definitions based on isDarkMode state
    val isDark = viewModel.isDarkMode
    
    val bgObsidian = if (isDark) Color(0xFF0B0F19) else Color(0xFFF1F5F9)
    val cardSlate = if (isDark) Color(0xFF161E2E) else Color(0xFFFFFFFF)
    val indigoGlow = if (isDark) Color(0xFF6366F1) else Color(0xFF4F46E5)
    val cyanElectric = if (isDark) Color(0xFF06B6D4) else Color(0xFF0891B2)
    val emeraldSuccess = if (isDark) Color(0xFF10B981) else Color(0xFF059669)
    val warningYellow = if (isDark) Color(0xFFF59E0B) else Color(0xFFD97706)
    val textPrimary = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val textSecondary = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val borderColor = if (isDark) Color(0xFF2D3748) else Color(0xFFE2E8F0)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bgObsidian
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (viewModel.showSettings) "Security & Theme" else "MCP Server Host",
                        color = textPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (viewModel.showSettings) "Configure Token, SSL & Theme" else "v0.0.1 • Hybrid Auth Gateway",
                        color = textSecondary,
                        fontSize = 12.sp
                    )
                }
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Settings Toggle Button
                    Button(
                        onClick = { viewModel.toggleSettings() },
                        colors = ButtonDefaults.buttonColors(containerColor = cardSlate),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, borderColor)
                    ) {
                        Text(
                            text = if (viewModel.showSettings) "Dashboard" else "Settings",
                            color = textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }

                    // Server switch button (only shown on dashboard)
                    if (!viewModel.showSettings) {
                        val serverButtonColor = when (status) {
                            is ServerStatus.Running -> emeraldSuccess
                            is ServerStatus.Starting -> warningYellow
                            is ServerStatus.Failed -> Color(0xFFDC2626)
                            else -> indigoGlow
                        }
                        val serverButtonLabel = when (status) {
                            is ServerStatus.Running -> "Active"
                            is ServerStatus.Starting -> "Starting"
                            is ServerStatus.Failed -> "Retry"
                            is ServerStatus.Unsupported -> "Unsupported"
                            else -> "Start"
                        }
                        Button(
                            onClick = { viewModel.toggleServer() },
                            enabled = !isStarting && status !is ServerStatus.Unsupported,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = serverButtonColor,
                                disabledContainerColor = serverButtonColor.copy(alpha = 0.55f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = serverButtonLabel,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            if (viewModel.showSettings) {
                // Settings View
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardSlate)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Text(
                            text = "Settings & Preferences",
                            color = textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )

                        // Dark/Light Theme Toggle Switch
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bgObsidian, RoundedCornerShape(12.dp))
                                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Dark Theme",
                                    color = textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (viewModel.isDarkMode) "Using Dark Obsidian mode" else "Using Light Clean mode",
                                    color = textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = viewModel.isDarkMode,
                                onCheckedChange = { viewModel.updateDarkMode(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = indigoGlow,
                                    uncheckedThumbColor = textSecondary,
                                    uncheckedTrackColor = bgObsidian
                                )
                            )
                        }

                        // Bearer Token Input & Action Buttons
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Bearer Token",
                                    color = textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                if (showCopiedMessage) {
                                    Text(
                                        text = "Copied to Clipboard!",
                                        color = emeraldSuccess,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            
                            OutlinedTextField(
                                value = viewModel.bearerToken,
                                onValueChange = { viewModel.updateBearerToken(it) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = indigoGlow,
                                    unfocusedBorderColor = borderColor,
                                    focusedTextColor = textPrimary,
                                    unfocusedTextColor = textPrimary,
                                    cursorColor = indigoGlow
                                ),
                                singleLine = true
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.generateSecureToken() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = indigoGlow),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Generate Token", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(viewModel.bearerToken))
                                        showCopiedMessage = true
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = cyanElectric),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Copy Token", fontSize = 11.sp, color = if (isDark) bgObsidian else Color.White, fontWeight = FontWeight.Bold)
                                }
                            }

                            Text(
                                text = "AI clients must send 'Authorization: Bearer <token>' in connection headers.",
                                color = textSecondary,
                                fontSize = 11.sp
                            )

                            viewModel.tokenWarning?.let { warning ->
                                Text(
                                    text = warning,
                                    color = warningYellow,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            viewModel.storageError?.let { error ->
                                Text(
                                    text = error,
                                    color = Color(0xFFDC2626),
                                    fontSize = 11.sp
                                )
                            }
                        }

                        // SSL/TLS Toggle Switch
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bgObsidian, RoundedCornerShape(12.dp))
                                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Enable SSL/TLS (HTTPS / WSS)",
                                    color = textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Switches server port from 8080 (HTTP) to 8443 (HTTPS).",
                                    color = textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = viewModel.sslEnabled,
                                onCheckedChange = { viewModel.updateSslEnabled(it) },
                                enabled = !isRunning && !isStarting,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = indigoGlow,
                                    uncheckedThumbColor = textSecondary,
                                    uncheckedTrackColor = bgObsidian
                                )
                            )
                        }

                        // Touch injection access
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bgObsidian, RoundedCornerShape(12.dp))
                                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Touch injection (touch_test)",
                                    color = textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Enable MCP Touch Service in Android accessibility settings.",
                                    color = textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            Button(
                                onClick = { viewModel.openTouchAccessibilitySettings() },
                                colors = ButtonDefaults.buttonColors(containerColor = indigoGlow),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Open", fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Info Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(indigoGlow.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                                .border(1.dp, indigoGlow.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "Note: Unauthenticated clients connecting without this token will appear in the Pending Approval queue on the dashboard.",
                                color = cyanElectric,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            } else {
                // Dashboard content scrolls; the connection bar stays pinned below.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                // Pending AI Connection Requests Section
                if (pendingClients.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = cardSlate)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Pending Connection Requests",
                                    color = warningYellow,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Box(
                                    modifier = Modifier
                                        .background(warningYellow.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "${pendingClients.size} Pending",
                                        color = warningYellow,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            pendingClients.forEach { client ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(bgObsidian, RoundedCornerShape(10.dp))
                                        .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = client.name,
                                            color = textPrimary,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = "ID: ${client.id}" + (client.remoteAddress?.let { " • $it" } ?: ""),
                                            color = textSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { viewModel.approveClient(client.id) },
                                            colors = ButtonDefaults.buttonColors(containerColor = emeraldSuccess),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Approve", fontSize = 11.sp, color = Color.White)
                                        }
                                        Button(
                                            onClick = { viewModel.rejectClient(client.id) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f)),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Reject", fontSize = 11.sp, color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Connected AIs Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardSlate)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Connected AI Agents",
                                color = textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = if (connectedClients.isNotEmpty()) emeraldSuccess.copy(alpha = 0.2f) else textSecondary.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${connectedClients.size} Online",
                                    color = if (connectedClients.isNotEmpty()) emeraldSuccess else textSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (connectedClients.isEmpty()) {
                            Text(
                                text = "No AI agents connected. Start the server to accept connections.",
                                color = textSecondary,
                                fontSize = 13.sp
                            )
                        } else {
                            connectedClients.forEach { client ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(bgObsidian, RoundedCornerShape(10.dp))
                                        .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = client.name,
                                            color = textPrimary,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "ID: ${client.id}" + (client.remoteAddress?.let { " • $it" } ?: ""),
                                            color = textSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .background(cyanElectric.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = client.status,
                                            color = cyanElectric,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Live Tools & Commands Execution Log
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardSlate)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Executed Tools & Commands Log",
                            color = textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )

                        if (executionLogs.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No tool commands executed yet.",
                                    color = textSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(executionLogs) { log ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(bgObsidian, RoundedCornerShape(10.dp))
                                            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "${formatLogTime(log.timestamp)}  ${log.toolName}",
                                                color = cyanElectric,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                            Text(
                                                text = log.status,
                                                color = when (log.status) {
                                                    "SUCCESS" -> emeraldSuccess
                                                    "WARNING" -> warningYellow
                                                    else -> Color.Red
                                                },
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp
                                            )
                                        }
                                        Text(
                                            text = "Args: ${log.arguments}",
                                            color = textSecondary,
                                            fontSize = 12.sp
                                        )
                                        log.result?.let {
                                            Text(
                                                text = "Result: $it",
                                                color = textPrimary,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                } // end scrollable dashboard content

                // Pinned Bottom Connection Status Bar
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = cardSlate,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, borderColor)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(
                                            color = when (status) {
                                                is ServerStatus.Running -> emeraldSuccess
                                                is ServerStatus.Starting -> warningYellow
                                                is ServerStatus.Failed -> Color(0xFFDC2626)
                                                else -> textSecondary.copy(alpha = 0.5f)
                                            },
                                            shape = CircleShape
                                        )
                                )
                                Text(
                                    text = "${viewModel.serverName} · ${statusLabel(status)}",
                                    color = textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            if (isRunning && showUrlCopiedMessage) {
                                Text(
                                    text = "Copied to Clipboard!",
                                    color = emeraldSuccess,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        when (status) {
                            is ServerStatus.Running -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "HTTP: ${viewModel.connectionUrl}",
                                            color = cyanElectric,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "SSE: ${viewModel.sseUrl}",
                                            color = indigoGlow,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(viewModel.connectionUrl))
                                                showUrlCopiedMessage = true
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = cyanElectric),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Copy HTTP", fontSize = 10.sp, color = if (isDark) bgObsidian else Color.White)
                                        }
                                        Button(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(viewModel.sseUrl))
                                                showUrlCopiedMessage = true
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = indigoGlow),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Copy SSE", fontSize = 10.sp, color = Color.White)
                                        }
                                    }
                                }

                                (status as? ServerStatus.Running)?.fingerprint?.let { fingerprint ->
                                    Text(
                                        text = "TLS SHA-256: $fingerprint",
                                        color = textSecondary,
                                        fontSize = 9.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            is ServerStatus.Failed -> {
                                Text(
                                    text = "Last error: $lastError",
                                    color = Color(0xFFDC2626),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            is ServerStatus.Starting -> {
                                Text(
                                    text = "Starting server…",
                                    color = warningYellow,
                                    fontSize = 11.sp
                                )
                            }

                            is ServerStatus.Unsupported -> {
                                Text(
                                    text = "The embedded server is only available on Android.",
                                    color = textSecondary,
                                    fontSize = 11.sp
                                )
                            }

                            else -> {
                                Text(
                                    text = "Server stopped",
                                    color = textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


private fun formatLogTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "--:--:--"
    return Instant.fromEpochMilliseconds(epochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .time
        .toString()
        .take(8)
}

private fun statusLabel(status: ServerStatus): String = when (status) {
    is ServerStatus.Running -> "Active"
    is ServerStatus.Starting -> "Starting"
    is ServerStatus.Failed -> "Failed"
    is ServerStatus.Unsupported -> "Unsupported"
    else -> "Stopped"
}
