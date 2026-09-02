package com.noesolution.gtracker.tracker

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.noesolution.gtracker.App
import com.noesolution.gtracker.MainActivity
import com.noesolution.gtracker.R
import com.noesolution.gtracker.audio.AudioClipService
import com.noesolution.gtracker.data.ApiClient
import com.noesolution.gtracker.data.PositionUpload
import com.noesolution.gtracker.data.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that requests periodic GPS fixes and POSTs each one to the
 * backend. Runs even when the app is in the background, as long as the
 * persistent notification is shown.
 */
class LocationTrackerService : LifecycleService() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var settingsRepo: SettingsRepository
    private var audioPollingStarted = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            uploadLocation(
                lat = location.latitude,
                lng = location.longitude,
                accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                speed = if (location.hasSpeed()) location.speed.toDouble() else null,
                timestamp = location.time,
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        settingsRepo = SettingsRepository(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }
            else -> startTracking()
        }
        return START_STICKY
    }

    private fun startTracking() {
        startForegroundWithNotification()
        acquireWakeLock()

        lifecycleScope.launch {
            val settings = settingsRepo.current()
            val intervalMs = settings.intervalMinutes.coerceAtLeast(1) * 60_000L

            val request = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                intervalMs,
            )
                .setMinUpdateIntervalMillis(intervalMs / 2)
                // Wait briefly for a precise GPS fix instead of returning a
                // quick, coarse network/wifi location.
                .setWaitForAccurateLocation(true)
                .build()

            try {
                fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
            } catch (e: SecurityException) {
                // Location permission was revoked; stop gracefully.
                stopTracking()
            }
        }

        startAudioPolling()
    }

    /**
     * While tracking is active, poll for pending emergency-audio requests and
     * hand each one to [AudioClipService]. Only runs when the owner has opted in.
     */
    private fun startAudioPolling() {
        if (audioPollingStarted) return
        audioPollingStarted = true
        lifecycleScope.launch {
            val handled = mutableSetOf<String>()
            while (true) {
                try {
                    val s = settingsRepo.current()
                    if (s.allowAudio && s.backendUrl.isNotBlank() && s.apiKey.isNotBlank()) {
                        val api = ApiClient.create(s.backendUrl, s.apiKey, s.groupCode, s.deviceId)
                        val micGranted = ContextCompat.checkSelfPermission(
                            this@LocationTrackerService, Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        for (r in api.pendingAudio()) {
                            if (handled.add(r.id)) {
                                if (micGranted) {
                                    AudioClipService.start(
                                        this@LocationTrackerService, r.id, r.requesterLabel,
                                    )
                                } else {
                                    // No mic permission: reject cleanly instead of crashing.
                                    runCatching { api.rejectAudio(r.id) }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Network hiccup; try again next cycle.
                }
                // Kept short (not tied to the position-send interval) so an
                // emergency-audio request is picked up quickly — this device
                // may belong to someone who cannot act on a slow notification.
                delay(AUDIO_POLL_INTERVAL_MS)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Some OEMs (Xiaomi/MIUI, etc.) kill the service when the app is swiped
        // from recents. Schedule a quick self-restart so tracking resumes.
        try {
            val restart = Intent(applicationContext, LocationTrackerService::class.java).apply {
                action = ACTION_START
            }
            val pi = PendingIntent.getService(
                this, 1, restart,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.RTC, System.currentTimeMillis() + 1500, pi)
        } catch (_: Exception) {
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun stopTracking() {
        fusedClient.removeLocationUpdates(locationCallback)
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    /**
     * Holds the CPU awake (screen may still be off) so GPS fixes keep arriving
     * and getting uploaded while the device is locked. A foreground service
     * alone only protects the process from being killed and from most Doze
     * restrictions — it does not stop the CPU from deep-sleeping, which is
     * what actually stalls background location delivery on many devices,
     * especially older ones. Without this, pending fixes only flush once the
     * user unlocks the screen and wakes the CPU themselves.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GTracker::LocationTrackingWakeLock",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun uploadLocation(
        lat: Double,
        lng: Double,
        accuracy: Double?,
        speed: Double?,
        timestamp: Long,
    ) {
        lifecycleScope.launch {
            val settings = settingsRepo.current()
            if (settings.backendUrl.isBlank() || settings.apiKey.isBlank()) return@launch
            val deviceId = settingsRepo.ensureDeviceId()
            try {
                val api = ApiClient.create(settings.backendUrl, settings.apiKey, settings.groupCode, deviceId)
                api.upload(
                    PositionUpload(
                        deviceId = deviceId,
                        label = settings.label.ifBlank { null },
                        lat = lat,
                        lng = lng,
                        accuracy = accuracy,
                        speed = speed,
                        timestamp = timestamp,
                        allowAudio = settings.allowAudio,
                    )
                )
            } catch (e: Exception) {
                // Network/server error: skip this fix. The next one will retry.
            }
        }
    }

    private fun startForegroundWithNotification() {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, App.TRACKING_CHANNEL_ID)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(getString(R.string.tracking_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val AUDIO_POLL_INTERVAL_MS = 15_000L
        const val ACTION_START = "com.noesolution.gtracker.START"
        const val ACTION_STOP = "com.noesolution.gtracker.STOP"

        fun start(context: Context) {
            val intent = Intent(context, LocationTrackerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationTrackerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
