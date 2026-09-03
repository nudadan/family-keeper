package com.noesolution.gtracker.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noesolution.gtracker.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts location tracking after the device reboots, but only if the user had
 * tracking turned on before. Relies on the persisted [SettingsRepository] flag.
 *
 * Note: BOOT_COMPLETED is delivered after the user unlocks the device for the
 * first time, which is when DataStore (credential-encrypted) becomes readable.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        // onReceive runs on the main thread; do the async work with goAsync().
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = SettingsRepository(appContext).current()
                if (settings.trackingEnabled) {
                    LocationTrackerService.start(appContext)
                    TrackingWatchdog.schedule(appContext)
                }
            } catch (_: Exception) {
                // Ignore; nothing else we can do this early in boot.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
