package com.vlad.mcpserver

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

// Modern custom color palette (Dark Obsidian & Neon Indigo/Cyan)
val BgObsidian = Color(0xFF0B0F19)
val CardSlate = Color(0xFF161E2E)
val IndigoGlow = Color(0xFF6366F1)
val CyanElectric = Color(0xFF06B6D4)
val EmeraldSuccess = Color(0xFF10B981)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val BorderColor = Color(0xFF2D3748)

/**
 * Main Composable presenting the MCP server dashboard with modern styling and real-time activity logs.
 */
@Composable
fun App(viewModel: AppViewModel = viewModel()) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BgObsidian
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
                        text = "MCP Server Host",
                        color = TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "v0.0.1 • AI Device Gateway",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                
                // Server Switch Button
                Button(
                    onClick = { viewModel.toggleServer() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (viewModel.isServerRunning) EmeraldSuccess else IndigoGlow
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (viewModel.isServerRunning) "Server Active" else "Start Server",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Connected AIs Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardSlate)
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
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (viewModel.connectedClients.value.isNotEmpty()) EmeraldSuccess.copy(alpha = 0.2f) else TextSecondary.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${viewModel.connectedClients.value.size} Online",
                                color = if (viewModel.connectedClients.value.isNotEmpty()) EmeraldSuccess else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (viewModel.connectedClients.value.isEmpty()) {
                        Text(
                            text = "No AI agents connected. Start the server to accept connections.",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    } else {
                        viewModel.connectedClients.value.forEach { client ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(BgObsidian, RoundedCornerShape(10.dp))
                                    .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = client.name,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "ID: ${client.id}",
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .background(CyanElectric.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = client.status,
                                        color = CyanElectric,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Quick Tool Simulation Buttons (for testing capabilities)
            if (viewModel.isServerRunning) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Simulate AI Tool Execution",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.simulateAiToolCall("touch_test", "x:500, y:800") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = IndigoGlow),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Touch Test", fontSize = 12.sp)
                        }
                        Button(
                            onClick = { viewModel.simulateAiToolCall("take_photo", "quality:high") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = IndigoGlow),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Take Photo", fontSize = 12.sp)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.simulateAiToolCall("read_file", "path:/documents/config.json") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanElectric),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Read File", fontSize = 12.sp, color = BgObsidian, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { viewModel.simulateAiToolCall("create_notification", "title:Test message") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanElectric),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Notification", fontSize = 12.sp, color = BgObsidian, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Live Tools & Commands Execution Log
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardSlate)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Executed Tools & Commands Log",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    if (viewModel.executionLogs.value.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No tool commands executed yet.",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(viewModel.executionLogs.value) { log ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(BgObsidian, RoundedCornerShape(10.dp))
                                        .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = log.toolName,
                                            color = CyanElectric,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = log.status,
                                            color = if (log.status == "SUCCESS") EmeraldSuccess else Color.Red,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Text(
                                        text = "Args: ${log.arguments}",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                    log.result?.let {
                                        Text(
                                            text = "Result: $it",
                                            color = TextPrimary,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
