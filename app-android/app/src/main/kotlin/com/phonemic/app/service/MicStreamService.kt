package com.phonemic.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.phonemic.app.MainActivity
import com.phonemic.app.R
import com.phonemic.app.audio.AudioCaptureManager
import com.phonemic.app.i18n.LocaleManager
import com.phonemic.app.model.ServerDevice
import com.phonemic.app.network.UdpAudioSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MicStreamService : LifecycleService() {

    companion object {
        private const val TAG = "MicStreamService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "phonemic_stream_channel"

        const val ACTION_START = "com.phonemic.app.action.START"
        const val ACTION_STOP = "com.phonemic.app.action.STOP"
        const val ACTION_TOGGLE_MUTE = "com.phonemic.app.action.TOGGLE_MUTE"
        const val ACTION_REFRESH_NOTIFICATION = "com.phonemic.app.action.REFRESH_NOTIFICATION"

        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_NAME = "extra_name"

        // Observable UI State
        private val _isStreaming = MutableStateFlow(false)
        val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

        private val _isMuted = MutableStateFlow(false)
        val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

        private val _currentDb = MutableStateFlow(-96f)
        val currentDb: StateFlow<Float> = _currentDb.asStateFlow()

        private val _connectedServer = MutableStateFlow<ServerDevice?>(null)
        val connectedServer: StateFlow<ServerDevice?> = _connectedServer.asStateFlow()

        private val _gain = MutableStateFlow(1.0f)
        val gain: StateFlow<Float> = _gain.asStateFlow()

        private val _nsEnabled = MutableStateFlow(false)
        val nsEnabled: StateFlow<Boolean> = _nsEnabled.asStateFlow()

        private val _aecEnabled = MutableStateFlow(false)
        val aecEnabled: StateFlow<Boolean> = _aecEnabled.asStateFlow()

        fun startStreaming(context: Context, host: String, port: Int, name: String = "PC") {
            val intent = Intent(context, MicStreamService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_NAME, name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopStreaming(context: Context) {
            val intent = Intent(context, MicStreamService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun toggleMute(context: Context) {
            val intent = Intent(context, MicStreamService::class.java).apply {
                action = ACTION_TOGGLE_MUTE
            }
            context.startService(intent)
        }

        fun refreshNotification(context: Context) {
            if (!_isStreaming.value) return
            val intent = Intent(context, MicStreamService::class.java).apply {
                action = ACTION_REFRESH_NOTIFICATION
            }
            context.startService(intent)
        }

        fun setGain(value: Float) {
            _gain.value = value
            activeManager?.gain = value
        }

        fun setNsEnabled(value: Boolean) {
            _nsEnabled.value = value
            activeManager?.isNoiseSuppressorEnabled = value
        }

        fun setAecEnabled(value: Boolean) {
            _aecEnabled.value = value
            activeManager?.isAcousticEchoCancelerEnabled = value
        }

        private var activeManager: AudioCaptureManager? = null
    }

    private var sender: UdpAudioSender? = null
    private var captureManager: AudioCaptureManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrapContext(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneMic::StreamWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, 9000)
                val name = intent.getStringExtra(EXTRA_NAME) ?: "PC"
                startInternal(host, port, name)
            }
            ACTION_STOP -> {
                stopInternal()
            }
            ACTION_TOGGLE_MUTE -> {
                val newMuted = !_isMuted.value
                _isMuted.value = newMuted
                captureManager?.isMuted = newMuted
                updateNotification()
            }
            ACTION_REFRESH_NOTIFICATION -> {
                createNotificationChannel()
                updateNotification()
            }
        }
        return START_NOT_STICKY
    }

    private fun startInternal(host: String, port: Int, name: String) {
        stopStreamingAudio()

        wakeLock?.acquire(3600 * 1000L) // 1 hour max

        val newSender = UdpAudioSender(host, port)
        sender = newSender

        val manager = AudioCaptureManager(
            sender = newSender,
            onDbUpdate = { db -> _currentDb.value = db }
        ).apply {
            gain = _gain.value
            isNoiseSuppressorEnabled = _nsEnabled.value
            isAcousticEchoCancelerEnabled = _aecEnabled.value
            isMuted = _isMuted.value
        }
        captureManager = manager
        activeManager = manager

        val started = manager.start()
        if (started) {
            _isStreaming.value = true
            _connectedServer.value = ServerDevice(name = name, ip = host, port = port)

            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            Log.e(TAG, "Failed to start audio recording")
            stopInternal()
        }
    }

    private fun stopInternal() {
        stopStreamingAudio()
        _isStreaming.value = false
        _connectedServer.value = null
        _currentDb.value = -96f
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopStreamingAudio() {
        captureManager?.stop()
        captureManager = null
        activeManager = null
        sender?.close()
        sender = null
    }

    private fun localizedString(resId: Int, vararg args: Any): String {
        return LocaleManager.wrapContext(this).getString(resId, *args)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                localizedString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = localizedString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(this, MicStreamService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val pendingMute = PendingIntent.getService(
            this, 1, muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MicStreamService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val server = _connectedServer.value
        val isMuted = _isMuted.value

        val title = localizedString(
            if (isMuted) R.string.notification_title_muted else R.string.notification_title_streaming
        )
        val content = if (server != null) {
            localizedString(R.string.notification_content_with_server, server.name, server.ip, server.port)
        } else {
            localizedString(R.string.notification_content)
        }

        val muteActionTitle = if (isMuted) localizedString(R.string.action_unmute) else localizedString(R.string.action_mute)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pendingOpenApp)
            .addAction(android.R.drawable.ic_media_pause, muteActionTitle, pendingMute)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, localizedString(R.string.action_stop), pendingStop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        if (_isStreaming.value) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onDestroy() {
        stopStreamingAudio()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        activeManager = null
        _isStreaming.value = false
        super.onDestroy()
    }
}
