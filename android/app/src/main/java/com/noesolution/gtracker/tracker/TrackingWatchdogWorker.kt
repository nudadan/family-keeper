package com.noesolution.gtracker.tracker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noesolution.gtracker.data.SettingsRepository
import java.util.concurrent.TimeUnit

/**
 * Periodic safety net for background tracking. Aggressive OEM battery
 * managers (and plain old Doze after hours of a locked screen) can kill the
 * tracking foreground service outright — at which point nothing restarts it
 * until the user manually reopens the app. WorkManager is scheduled through
 * the OS's own JobScheduler, so unlike the service itself it keeps getting
 * rescheduled even after a full process death. Each run just checks whether
 * tracking should be on and restarts the service if so (safe to call even
 * when it's already running).
 */
class TrackingWatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext).current()
        if (settings.trackingEnabled) {
            LocationTrackerService.start(applicationContext)
        }
        return Result.success()
    }
}

object TrackingWatchdog {
    private const val UNIQUE_WORK_NAME = "tracking-watchdog"

    /** Idempotent: safe to call every time tracking starts, on app launch, and on boot. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<TrackingWatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
