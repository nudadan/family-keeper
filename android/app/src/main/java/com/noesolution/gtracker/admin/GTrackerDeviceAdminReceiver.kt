package com.noesolution.gtracker.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Registering the app as a Device Administrator blocks the normal Uninstall
 * action until the user first deactivates this admin (Settings > Security >
 * Device admin apps). It is a deterrent, not an absolute block.
 */
class GTrackerDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Shown when the user tries to deactivate the admin.
        return "Menonaktifkan ini akan memungkinkan aplikasi Gardenia-1 dihapus dari perangkat."
    }
}
