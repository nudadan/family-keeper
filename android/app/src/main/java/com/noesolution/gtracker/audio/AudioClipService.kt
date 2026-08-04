package com.noesolution.gtracker.audio

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.noesolution.gtracker.App
import com.noesolution.gtracker.data.ApiClient
import com.noesolution.gtracker.data.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Records a short emergency-audio clip on request and uploads it. A loud
 * indicator (tone + vibration) plus a high-priority notification make the
 * recording OBVIOUS to anyone near the device — this is not a covert bug.
 */
class AudioClipService : LifecycleService() {

    @Volatile
    private var busy = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
        val requester = intent?.getStringExtra(EXTRA_REQUESTER) ?: "keluarga"

        if (requestId == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Must call startForeground promptly to satisfy startForegroundService.
        // The poller only starts us when RECORD_AUDIO is granted, so the mic
        // foreground-service type is allowed here.
        startForegroundNotification(requester)

        if (busy) {
            // Another recording is already running; reject the extra and leave
            // the ongoing one untouched.
            rejectQuietly(requestId)
            return START_NOT_STICKY
        }
        busy = true
        handleRequest(requestId, requester)
        return START_NOT_STICKY
    }

    private fun handleRequest(requestId: String, requester: String) {
        lifecycleScope.launch {
            val repo = SettingsRepository(applicationContext)
            val settings = repo.current()
            val api = ApiClient.create(
                settings.backendUrl, settings.apiKey, settings.groupCode, settings.deviceId,
            )

            // Defensive: honor consent + permission even though the server checks too.
            val hasPermission = ContextCompat.checkSelfPermission(
                this@AudioClipService, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (!settings.allowAudio || !hasPermission) {
                runCatching { api.rejectAudio(requestId) }
                finish()
                return@launch
            }

            playIndicator()

            val file = File(cacheDir, "clip_$requestId.m4a")
            val recorded = runCatching { recordTo(file, RECORD_MS) }.isSuccess

            if (recorded && file.exists() && file.length() > 0) {
                runCatching {
                    val body = file.readBytes().toRequestBody("audio/mp4".toMediaType())
                    api.uploadClip(requestId, body, RECORD_MS)
                }.onFailure { runCatching { api.rejectAudio(requestId) } }
            } else {
                runCatching { api.rejectAudio(requestId) }
            }
            file.delete()
            finish()
        }
    }

    private suspend fun recordTo(file: File, durationMs: Long) {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        try {
            delay(durationMs)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    /** Audible + haptic signal so people nearby know the mic is active. */
    private fun playIndicator() {
        runCatching {
            ToneGenerator(AudioManager.STREAM_ALARM, 100).apply {
                startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800)
            }
        }
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(longArrayOf(0, 400, 200, 400), -1)
            }
        }
    }

    private fun startForegroundNotification(requester: String) {
        val notification: Notification = NotificationCompat.Builder(this, App.AUDIO_CHANNEL_ID)
            .setContentTitle("Merekam audio darurat")
            .setContentText("Permintaan dari $requester. Mikrofon sedang aktif.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun rejectQuietly(requestId: String) {
        lifecycleScope.launch {
            val repo = SettingsRepository(applicationContext)
            val s = repo.current()
            runCatching {
                ApiClient.create(s.backendUrl, s.apiKey, s.groupCode, s.deviceId).rejectAudio(requestId)
            }
        }
    }

    private fun finish() {
        busy = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    companion object {
        private const val NOTIFICATION_ID = 2002
        private const val RECORD_MS = 15_000L
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_REQUESTER = "requester"

        fun start(context: Context, requestId: String, requester: String?) {
            val intent = Intent(context, AudioClipService::class.java).apply {
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_REQUESTER, requester)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
