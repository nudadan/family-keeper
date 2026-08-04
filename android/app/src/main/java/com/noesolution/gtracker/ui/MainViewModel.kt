package com.noesolution.gtracker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.noesolution.gtracker.data.ApiClient
import com.noesolution.gtracker.data.AudioLogEntry
import com.noesolution.gtracker.data.AudioRequestBody
import com.noesolution.gtracker.data.Position
import com.noesolution.gtracker.data.PositionUpload
import com.noesolution.gtracker.data.Settings
import com.noesolution.gtracker.data.Track
import com.noesolution.gtracker.data.SettingsRepository
import com.noesolution.gtracker.tracker.LocationTrackerService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)

    val settings: StateFlow<Settings?> = repo.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    init {
        // Make sure a device id exists from the start.
        viewModelScope.launch { repo.ensureDeviceId() }
    }

    fun saveSettings(
        label: String,
        groupCode: String,
        intervalMinutes: Int,
    ) {
        viewModelScope.launch {
            repo.update(
                label = label,
                groupCode = groupCode,
                intervalMinutes = intervalMinutes,
            )
        }
    }

    /**
     * Called on app launch: if tracking was enabled before (and location
     * permission is still granted), make sure the service is running again.
     * Does NOT change the enabled flag.
     */
    fun resumeTrackingIfEnabled() {
        viewModelScope.launch {
            val s = repo.current()
            if (!s.trackingEnabled) return@launch
            val app = getApplication<Application>()
            val granted = ContextCompat.checkSelfPermission(
                app, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                LocationTrackerService.start(app)
            }
        }
    }

    fun startTracking() {
        viewModelScope.launch {
            repo.update(trackingEnabled = true)
            LocationTrackerService.start(getApplication())
        }
    }

    fun stopTracking() {
        viewModelScope.launch {
            repo.update(trackingEnabled = false)
            LocationTrackerService.stop(getApplication())
        }
    }

    /** Loads the latest position for every device. Calls [onResult] on completion. */
    fun loadLatest(onResult: (Result<List<Position>>) -> Unit) {
        viewModelScope.launch {
            val s = repo.current()
            val result = runCatching {
                ApiClient.create(s.backendUrl, s.apiKey, s.groupCode, s.deviceId).latestAll()
            }
            onResult(result)
        }
    }

    /**
     * One-shot diagnostic: grab a fresh high-accuracy fix and POST it now,
     * reporting a human-readable outcome (permission / GPS / network) via [onResult].
     */
    @SuppressLint("MissingPermission")
    fun sendTestLocation(onResult: (String) -> Unit) {
        val app = getApplication<Application>()
        val granted = ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            onResult("❌ Location permission not granted. Grant it in the Tracker screen / app settings.")
            return
        }

        viewModelScope.launch {
            try {
                val cts = CancellationTokenSource()
                val client = LocationServices.getFusedLocationProviderClient(app)
                val loc = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
                if (loc == null) {
                    onResult("⚠️ No GPS fix. Turn Location ON and try near a window / outdoors.")
                    return@launch
                }
                val s = repo.current()
                if (s.backendUrl.isBlank() || s.apiKey.isBlank()) {
                    onResult("❌ Backend URL / API key empty. Check Settings.")
                    return@launch
                }
                val deviceId = repo.ensureDeviceId()
                ApiClient.create(s.backendUrl, s.apiKey, s.groupCode, deviceId).upload(
                    PositionUpload(
                        deviceId = deviceId,
                        label = s.label.ifBlank { null },
                        lat = loc.latitude,
                        lng = loc.longitude,
                        accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                        speed = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                        timestamp = loc.time,
                        allowAudio = s.allowAudio,
                    )
                )
                onResult(
                    "✅ Sent %.5f, %.5f (±%dm)".format(
                        loc.latitude, loc.longitude, loc.accuracy.toInt(),
                    )
                )
            } catch (e: Exception) {
                onResult("❌ ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** Loads each device's track for the last [hours] hours. */
    fun loadTracks(hours: Double = 12.0, onResult: (Result<List<Track>>) -> Unit) {
        viewModelScope.launch {
            val s = repo.current()
            val result = runCatching {
                ApiClient.create(s.backendUrl, s.apiKey, s.groupCode, s.deviceId).tracks(hours).tracks
            }
            onResult(result)
        }
    }

    // --- Emergency audio ---

    private var mediaPlayer: MediaPlayer? = null

    /** Owner consent toggle (persisted; pushed to the server on the next position). */
    fun setAllowAudio(enabled: Boolean) {
        viewModelScope.launch { repo.update(allowAudio = enabled) }
    }

    /**
     * Ask [targetDeviceId] for an emergency clip, wait for it, then play it.
     * Progress is reported via [onUpdate].
     */
    fun requestAudio(targetDeviceId: String, onUpdate: (String) -> Unit) {
        viewModelScope.launch {
            val s = repo.current()
            val api = ApiClient.create(s.backendUrl, s.apiKey, s.groupCode, s.deviceId)
            onUpdate("Meminta audio…")
            val requestId = try {
                api.requestAudio(AudioRequestBody(targetDeviceId)).requestId
            } catch (e: Exception) {
                onUpdate("❌ Gagal meminta: ${e.message}")
                return@launch
            }

            onUpdate("Menunggu device merekam…")
            repeat(20) { // ~60s: record 15s + upload + margin
                delay(3000)
                val bytes = runCatching { api.downloadClip(requestId).bytes() }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    val file = File(getApplication<Application>().cacheDir, "recv_$requestId.m4a")
                    file.writeBytes(bytes)
                    onUpdate("▶️ Memutar…")
                    playAudio(file) { onUpdate("✅ Selesai memutar") }
                    return@launch
                }
            }
            onUpdate("⌛ Timeout — device tidak merespons atau menolak.")
        }
    }

    private fun playAudio(file: File, onDone: () -> Unit) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                it.release()
                if (mediaPlayer === it) mediaPlayer = null
                onDone()
            }
            prepare()
            start()
        }
    }

    fun loadAudioLog(onResult: (Result<List<AudioLogEntry>>) -> Unit) {
        viewModelScope.launch {
            val s = repo.current()
            onResult(
                runCatching {
                    ApiClient.create(s.backendUrl, s.apiKey, s.groupCode, s.deviceId).audioLog()
                }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
