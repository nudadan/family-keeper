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
import com.noesolution.gtracker.data.PickupRequestBody
import com.noesolution.gtracker.data.Position
import com.noesolution.gtracker.data.PositionUpload
import com.noesolution.gtracker.data.Settings
import com.noesolution.gtracker.data.SettingsRepository
import com.noesolution.gtracker.tracker.LocationTrackerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import retrofit2.HttpException
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

    // --- Jemput (pickup request) ---

    /**
     * Sends a pickup request: the server relays it as a WhatsApp message
     * (text + the device's last known location) to the group's configured
     * WhatsApp group. [note] is an optional short message (e.g. "di depan sekolah").
     */
    fun requestPickup(note: String, targetDeviceId: String? = null, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val s = repo.current()
            if (s.groupCode.isBlank()) {
                onResult("⚠️ Isi Group code di Settings dulu.")
                return@launch
            }
            try {
                val deviceId = repo.ensureDeviceId()
                ApiClient.create(s.backendUrl, s.apiKey, s.groupCode, deviceId)
                    .requestPickup(PickupRequestBody(note.ifBlank { null }, targetDeviceId))
                onResult(
                    if (targetDeviceId != null) "✅ Peringatan darurat terkirim ke grup WhatsApp."
                    else "✅ Permintaan jemput terkirim ke grup WhatsApp."
                )
            } catch (e: Exception) {
                onResult("❌ ${friendlyError(e)}")
            }
        }
    }

    /**
     * Self-initiated emergency SOS: sends an urgent WhatsApp alert with the
     * device's current location to the group, and boosts this device's own
     * location-send frequency for the next 15 minutes so family can follow
     * along in near-real time — independent of whether the WhatsApp send
     * itself succeeds.
     */
    fun sendSos(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val s = repo.current()
            if (s.groupCode.isBlank()) {
                onResult("⚠️ Isi Group code di Settings dulu.")
                return@launch
            }
            LocationTrackerService.boost(getApplication())
            try {
                val deviceId = repo.ensureDeviceId()
                ApiClient.create(s.backendUrl, s.apiKey, s.groupCode, deviceId)
                    .requestPickup(PickupRequestBody(note = null, kind = "sos"))
                onResult("✅ SOS terkirim ke grup WhatsApp. Lokasi dikirim lebih sering selama 15 menit.")
            } catch (e: Exception) {
                onResult("❌ ${friendlyError(e)}")
            }
        }
    }

    /** Extracts the backend's {"error": "..."} message from an HTTP error response, if present. */
    private suspend fun friendlyError(e: Exception): String {
        if (e is HttpException) {
            val body = withContext(Dispatchers.IO) { e.response()?.errorBody()?.string() }
            val match = body?.let { Regex("\"error\"\\s*:\\s*\"([^\"]*)\"").find(it) }
            if (match != null) return match.groupValues[1]
        }
        return e.message ?: e.javaClass.simpleName
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

            // Target polls for pending requests every ~15s, then records for 15s
            // and uploads — worst case is roughly 35s. We wait well beyond that
            // (90s) since this may be the only chance to reach someone in danger.
            val pollEveryMs = 2000L
            val maxTries = 45 // 45 * 2s = 90s
            repeat(maxTries) { i ->
                delay(pollEveryMs)
                onUpdate("Menunggu device merekam… (${(i + 1) * pollEveryMs / 1000}d)")
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
