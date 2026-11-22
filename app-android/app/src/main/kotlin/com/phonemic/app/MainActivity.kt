package com.phonemic.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonemic.app.network.DiscoveryClient
import com.phonemic.app.service.MicStreamService
import com.phonemic.app.ui.DiscoveryDialog
import com.phonemic.app.ui.MainScreen
import com.phonemic.app.ui.ScanQrView
import com.phonemic.app.ui.theme.PhoneMicTheme

class MainActivity : ComponentActivity() {

    private lateinit var discoveryClient: DiscoveryClient

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!recordAudioGranted) {
            Toast.makeText(this, "需要麦克风权限才能作为电脑麦克风", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        discoveryClient = DiscoveryClient(this)

        requestRequiredPermissions()

        setContent {
            PhoneMicTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showScanQr by remember { mutableStateOf(false) }
                    var showDiscoveryDialog by remember { mutableStateOf(false) }
                    val discoveredDevices by discoveryClient.discoveredDevices.collectAsStateWithLifecycle()

                    if (showScanQr) {
                        ScanQrView(
                            onResult = { host, port, name ->
                                showScanQr = false
                                connect(host, port, name)
                            },
                            onDismiss = { showScanQr = false }
                        )
                    } else {
                        MainScreen(
                            discoveredDevices = discoveredDevices,
                            onStartScanQr = {
                                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    showScanQr = true
                                } else {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                                }
                            },
                            onOpenDiscovery = { showDiscoveryDialog = true },
                            onConnect = { host, port, name -> connect(host, port, name) },
                            onDisconnect = { disconnect() }
                        )

                        if (showDiscoveryDialog) {
                            DiscoveryDialog(
                                devices = discoveredDevices,
                                onSelectDevice = { host, port, name ->
                                    showDiscoveryDialog = false
                                    connect(host, port, name)
                                },
                                onManualConnect = { host, port ->
                                    showDiscoveryDialog = false
                                    connect(host, port, "PC ($host)")
                                },
                                onDismiss = { showDiscoveryDialog = false }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun connect(host: String, port: Int, name: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRequiredPermissions()
            return
        }
        MicStreamService.startStreaming(this, host, port, name)
        Toast.makeText(this, "正在连接至 $name ($host:$port)", Toast.LENGTH_SHORT).show()
    }

    private fun disconnect() {
        MicStreamService.stopStreaming(this)
        Toast.makeText(this, "已断开连接", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        discoveryClient.startDiscovery()
    }

    override fun onPause() {
        super.onPause()
        discoveryClient.stopDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryClient.stopDiscovery()
    }
}
