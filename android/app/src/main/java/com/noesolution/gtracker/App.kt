package com.noesolution.gtracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    TRACKING_CHANNEL_ID,
                    getString(R.string.tracking_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
            // High importance so the "recording emergency audio" alert is obvious.
            manager.createNotificationChannel(
                NotificationChannel(
                    AUDIO_CHANNEL_ID,
                    "Emergency audio",
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
    }

    companion object {
        const val TRACKING_CHANNEL_ID = "location_tracking"
        const val AUDIO_CHANNEL_ID = "emergency_audio"
    }
}
