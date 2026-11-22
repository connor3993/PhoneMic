package com.phonemic.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonemic.app.model.ServerDevice
import com.phonemic.app.service.MicStreamService
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    discoveredDevices: List<ServerDevice>,
    onStartScanQr: () -> Unit,
    onOpenDiscovery: () -> Unit,
    onConnect: (host: String, port: Int, name: String) -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val isStreaming by MicStreamService.isStreaming.collectAsStateWithLifecycle()
    val isMuted by MicStreamService.isMuted.collectAsStateWithLifecycle()
    val currentDb by MicStreamService.currentDb.collectAsStateWithLifecycle()
    val connectedServer by MicStreamService.connectedServer.collectAsStateWithLifecycle()
    val gain by MicStreamService.gain.collectAsStateWithLifecycle()
    val nsEnabled by MicStreamService.nsEnabled.collectAsStateWithLifecycle()
    val aecEnabled by MicStreamService.aecEnabled.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🎙️ PhoneMic", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isStreaming) Color(0xFF1B5E20) else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = if (isStreaming) (if (isMuted) "已静音" else "运行中") else "待连接",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 11.sp,
                                color = if (isStreaming) Color(0xFFA5D6A7) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenDiscovery) {
                        Icon(Icons.Default.Search, contentDescription = "发现电脑")
                    }
                    IconButton(onClick = onStartScanQr) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "扫码")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            // 1. Connection Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isStreaming) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (isStreaming) (if (isMuted) Color.Red else Color.Green) else Color.Gray)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isStreaming && connectedServer != null) connectedServer!!.name else "电脑端未连接",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (isStreaming && connectedServer != null) "${connectedServer!!.ip}:${connectedServer!!.port} (48kHz Mono)"
                            else "请扫码或通过局域网发现连接",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isStreaming) {
                        FilledTonalButton(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("断开")
                        }
                    } else {
                        Button(
                            onClick = onOpenDiscovery,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("连接")
                        }
                    }
                }
            }

            // 2. VU Volume Meter & dB Readout
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("实时电平监控", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = if (!isStreaming) "OFF" else if (isMuted) "MUTED" else String.format("%.1f dB", currentDb),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = if (!isStreaming) Color.Gray else if (isMuted) Color.Red else Color(0xFF00C853)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Gradient VU segmented meter bar
                    VuMeterBar(
                        db = if (isStreaming && !isMuted) currentDb else -96f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(18.dp)
                    )
                }
            }

            // 3. Huge Central Action / Mute Button
            Spacer(modifier = Modifier.height(8.dp))
            BigMicButton(
                isStreaming = isStreaming,
                isMuted = isMuted,
                currentDb = currentDb,
                onClick = {
                    if (isStreaming) {
                        MicStreamService.toggleMute(context)
                    } else {
                        onOpenDiscovery()
                    }
                }
            )

            Text(
                text = if (!isStreaming) "点击开始连接电脑"
                else if (isMuted) "麦克风已静音 (点击取消静音)"
                else "正在收音中 (点击快速静音)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (!isStreaming) MaterialTheme.colorScheme.onSurfaceVariant
                else if (isMuted) MaterialTheme.colorScheme.error
                else Color(0xFF2E7D32)
            )

            // 4. Audio Processing & Controls Panel
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("音频调节与硬件降噪", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    // Gain Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("麦克风增益 (Gain)", style = MaterialTheme.typography.bodyMedium)
                            Text("${(gain * 100).roundToInt()}%", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = gain,
                            onValueChange = { MicStreamService.setGain(it) },
                            valueRange = 0.0f..2.0f,
                            steps = 19
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Noise Suppressor
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Android 原生硬件降噪 (NS)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text("利用手机专用芯片降噪算法过滤环境风噪、键盘与底噪", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = nsEnabled,
                            onCheckedChange = { MicStreamService.setNsEnabled(it) }
                        )
                    }

                    // Acoustic Echo Canceler
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("回声消除 (AEC)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text("消除室内声场扬声器自激与回环回声", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = aecEnabled,
                            onCheckedChange = { MicStreamService.setAecEnabled(it) }
                        )
                    }
                }
            }

            // 5. Connection Action Shortcuts
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenDiscovery,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("局域网探测")
                }
                Button(
                    onClick = onStartScanQr,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("扫码配对")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun VuMeterBar(db: Float, modifier: Modifier = Modifier) {
    val minDb = -60f
    val maxDb = 0f
    val clamped = db.coerceIn(minDb, maxDb)
    val ratio = (clamped - minDb) / (maxDb - minDb)

    val animatedRatio by animateFloatAsState(
        targetValue = ratio,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "vuRatio"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1E1E1E))
            .border(1.dp, Color(0xFF333333), RoundedCornerShape(6.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedRatio)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF00E676),
                            Color(0xFFFFEA00),
                            Color(0xFFFF1744)
                        )
                    )
                )
        )
    }
}

@Composable
fun BigMicButton(
    isStreaming: Boolean,
    isMuted: Boolean,
    currentDb: Float,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isStreaming && !isMuted) 1.06f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val buttonBgColor by animateColorAsState(
        targetValue = when {
            !isStreaming -> MaterialTheme.colorScheme.primary
            isMuted -> Color(0xFFD32F2F)
            else -> Color(0xFF2E7D32)
        },
        label = "buttonBgColor"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.padding(12.dp)
    ) {
        // Outer glow ripple
        if (isStreaming && !isMuted) {
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50).copy(alpha = 0.25f))
            )
        }

        Surface(
            modifier = Modifier
                .size(126.dp)
                .clip(CircleShape)
                .clickable { onClick() },
            shape = CircleShape,
            color = buttonBgColor,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = when {
                        !isStreaming -> Icons.Default.Mic
                        isMuted -> Icons.Default.MicOff
                        else -> Icons.Default.Mic
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(46.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        !isStreaming -> "开始收音"
                        isMuted -> "已静音"
                        else -> "静音"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}
