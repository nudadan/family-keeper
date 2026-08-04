package com.noesolution.gtracker.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.noesolution.gtracker.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "gtracker_settings")

data class Settings(
    val backendUrl: String,
    val apiKey: String,
    val deviceId: String,
    val label: String,
    val groupCode: String,
    val intervalMinutes: Int,
    val trackingEnabled: Boolean,
    val allowAudio: Boolean,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val LABEL = stringPreferencesKey("label")
        val GROUP_CODE = stringPreferencesKey("group_code")
        val INTERVAL_MIN = intPreferencesKey("interval_minutes")
        val TRACKING_ENABLED = booleanPreferencesKey("tracking_enabled")
        val ALLOW_AUDIO = booleanPreferencesKey("allow_audio")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            // Backend URL and API key are fixed at build time (gradle.properties)
            // so they can't be changed by accident from the UI.
            backendUrl = BuildConfig.DEFAULT_BACKEND_URL,
            apiKey = BuildConfig.DEFAULT_API_KEY,
            deviceId = prefs[Keys.DEVICE_ID] ?: "",
            label = prefs[Keys.LABEL] ?: "",
            groupCode = prefs[Keys.GROUP_CODE] ?: "",
            intervalMinutes = prefs[Keys.INTERVAL_MIN] ?: 5,
            trackingEnabled = prefs[Keys.TRACKING_ENABLED] ?: false,
            allowAudio = prefs[Keys.ALLOW_AUDIO] ?: false,
        )
    }

    suspend fun current(): Settings = settings.first()

    /** Returns the existing device id or creates and persists a new one. */
    suspend fun ensureDeviceId(): String {
        val existing = context.dataStore.data.first()[Keys.DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing
        val generated = "android-" + UUID.randomUUID().toString().take(8)
        context.dataStore.edit { it[Keys.DEVICE_ID] = generated }
        return generated
    }

    suspend fun update(
        deviceId: String? = null,
        label: String? = null,
        groupCode: String? = null,
        intervalMinutes: Int? = null,
        trackingEnabled: Boolean? = null,
        allowAudio: Boolean? = null,
    ) {
        context.dataStore.edit { prefs ->
            deviceId?.let { prefs[Keys.DEVICE_ID] = it.trim() }
            label?.let { prefs[Keys.LABEL] = it.trim() }
            groupCode?.let { prefs[Keys.GROUP_CODE] = it.trim() }
            intervalMinutes?.let { prefs[Keys.INTERVAL_MIN] = it }
            trackingEnabled?.let { prefs[Keys.TRACKING_ENABLED] = it }
            allowAudio?.let { prefs[Keys.ALLOW_AUDIO] = it }
        }
    }
}
