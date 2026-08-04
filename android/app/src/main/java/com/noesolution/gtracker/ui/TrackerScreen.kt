package com.noesolution.gtracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private fun isFineGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun isIgnoringBattery(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@Suppress("BatteryLife")
private fun requestIgnoreBattery(context: Context) {
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
fun TrackerScreen(
    modifier: Modifier = Modifier,
    vm: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val tracking = settings?.trackingEnabled == true
    val groupSet = settings?.groupCode?.isNotBlank() == true
    var testStatus by remember { mutableStateOf("") }

    var fineGranted by remember { mutableStateOf(isFineGranted(context)) }
    var batteryOk by remember { mutableStateOf(isIgnoringBattery(context)) }

    // Re-check the permission whenever we come back to this screen (e.g. after
    // the user changed it in the system app-settings page), and resume the
    // service if it was supposed to be running.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                fineGranted = isFineGranted(context)
                batteryOk = isIgnoringBattery(context)
                if (fineGranted) vm.resumeTrackingIfEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Step 2: background location (requested separately, after fine location).
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handled by the system */ }

    // Step 1: fine location + notifications.
    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted) {
            vm.startTracking()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        } else {
            testStatus = "Izin lokasi ditolak. Buka 'Open app settings' lalu izinkan lokasi."
        }
    }

    fun requestForegroundPermission() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        foregroundLauncher.launch(perms.toTypedArray())
    }

    fun requestAndStart() {
        if (!groupSet) {
            testStatus = "⚠️ Isi Group code di Settings dulu sebelum mulai."
            return
        }
        requestForegroundPermission()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Tracker", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Name: ${settings?.label?.takeIf { it.isNotBlank() } ?: "(not set — see Settings)"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Device ID: ${settings?.deviceId ?: "…"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (tracking) "Status: SHARING every ${settings?.intervalMinutes} min"
            else "Status: stopped",
            style = MaterialTheme.typography.bodyMedium,
        )

        // Permission problem banner — shown whenever fine location is missing,
        // regardless of the SHARING/stopped state, so the user is never stuck.
        if (!fineGranted) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = "❌ Izin lokasi belum diberikan — posisi tidak akan terkirim.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { requestForegroundPermission() }) {
                Text("Grant location permission")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { openAppSettings(context) }) {
                Text("Open app settings")
            }
            Text(
                text = "Di HP Xiaomi, pilih \"Allow all the time\" agar berjalan di latar belakang.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(28.dp))

        if (tracking) {
            Button(onClick = { vm.stopTracking() }) {
                Text("Stop sharing")
            }
        } else {
            Button(onClick = { requestAndStart() }, enabled = groupSet) {
                Text("Start sharing my location")
            }
            if (!groupSet) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "⚠️ Set a Group code in Settings before starting.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Background reliability — the usual cause of "stops when app closed"
        // on Xiaomi/Oppo/etc. is battery optimization + Autostart.
        Spacer(Modifier.height(24.dp))
        Text("Agar tetap jalan di background:", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        if (!batteryOk) {
            Button(onClick = { requestIgnoreBattery(context) }) {
                Text("Izinkan tanpa batas baterai")
            }
        } else {
            Text("✅ Baterai: tanpa batasan", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { openAppSettings(context) }) {
            Text("Buka setelan app (Autostart)")
        }

        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = {
                if (!groupSet) {
                    testStatus = "⚠️ Isi Group code di Settings dulu."
                } else {
                    testStatus = "Getting location…"
                    vm.sendTestLocation { testStatus = it }
                }
            },
            enabled = groupSet,
        ) {
            Text("Test: send my location now")
        }

        if (testStatus.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = testStatus,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}
