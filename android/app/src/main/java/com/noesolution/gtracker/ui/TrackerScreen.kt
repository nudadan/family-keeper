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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.noesolution.gtracker.ui.theme.StatusActiveGreen
import com.noesolution.gtracker.ui.theme.StatusActiveGreenContainer
import com.noesolution.gtracker.ui.theme.StatusIdleGrey
import com.noesolution.gtracker.ui.theme.StatusIdleGreyContainer
import com.noesolution.gtracker.ui.theme.StatusWarnAmberContainer
import com.noesolution.gtracker.ui.theme.StatusWarnAmberOn

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
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val tracking = settings?.trackingEnabled == true
    val groupSet = settings?.groupCode?.isNotBlank() == true
    var testStatus by remember { mutableStateOf("") }

    var fineGranted by remember { mutableStateOf(isFineGranted(context)) }
    var batteryOk by remember { mutableStateOf(isIgnoringBattery(context)) }

    // Re-check permission/battery whenever we come back to this screen (e.g.
    // after the user changed it in a system settings page).
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

    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handled by the system */ }

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
            testStatus = "Izin lokasi ditolak. Buka pengaturan app lalu izinkan lokasi."
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
            testStatus = "⚠️ Isi Group code di Pengaturan dulu sebelum mulai."
            return
        }
        requestForegroundPermission()
    }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenTopBar(title = "Tracker")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // --- Identity card ---
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val name = settings?.label?.takeIf { it.isNotBlank() } ?: "Belum diberi nama"
                    Surface(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = name.take(1).uppercase(),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "ID: ${settings?.deviceId ?: "…"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // --- Status + primary action ---
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeading("Status pengiriman", icon = Icons.Filled.Sensors)
                    if (tracking) {
                        StatusPill(
                            text = "AKTIF",
                            containerColor = StatusActiveGreenContainer,
                            contentColor = StatusActiveGreen,
                            icon = Icons.Filled.CheckCircle,
                        )
                    } else {
                        StatusPill(
                            text = "TIDAK AKTIF",
                            containerColor = StatusIdleGreyContainer,
                            contentColor = StatusIdleGrey,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (tracking) {
                        "Mengirim posisi setiap ${settings?.intervalMinutes ?: 5} menit."
                    } else {
                        "Posisi tidak dikirim. Tekan tombol di bawah untuk mulai berbagi lokasi."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                if (tracking) {
                    Button(
                        onClick = { vm.stopTracking() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Hentikan berbagi lokasi")
                    }
                } else {
                    Button(
                        onClick = { requestAndStart() },
                        enabled = groupSet,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Mulai berbagi lokasi")
                    }
                    if (!groupSet) {
                        Spacer(Modifier.height(8.dp))
                        HintRow(
                            text = "Isi Group code di tab Pengaturan sebelum mulai.",
                            containerColor = StatusWarnAmberContainer,
                            contentColor = StatusWarnAmberOn,
                        )
                    }
                }
            }

            // --- Permission problem card ---
            if (!fineGranted) {
                SectionCard {
                    SectionHeading("Izin lokasi diperlukan", icon = Icons.Filled.LocationOn)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Posisi tidak akan terkirim sampai izin lokasi diberikan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { requestForegroundPermission() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Berikan izin lokasi")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { openAppSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Buka pengaturan aplikasi")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Di HP Xiaomi, pilih \"Allow all the time\" agar berjalan di latar belakang.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // --- Background reliability ---
            SectionCard {
                SectionHeading("Keandalan latar belakang", icon = Icons.Filled.BatteryAlert)
                Spacer(Modifier.height(4.dp))
                if (batteryOk) {
                    HintRow(
                        text = "Baterai: tanpa batasan",
                        containerColor = StatusActiveGreenContainer,
                        contentColor = StatusActiveGreen,
                        icon = Icons.Filled.CheckCircle,
                    )
                } else {
                    Text(
                        text = "Agar tidak dihentikan sistem saat aplikasi ditutup, izinkan berjalan tanpa batasan baterai.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { requestIgnoreBattery(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Izinkan tanpa batas baterai")
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { openAppSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Buka pengaturan app (Autostart)")
                }
            }

            // --- Diagnostics ---
            SectionCard {
                SectionHeading("Diagnostik")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Kirim satu titik lokasi sekarang untuk memastikan konfigurasi benar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        if (!groupSet) {
                            testStatus = "⚠️ Isi Group code di Pengaturan dulu."
                        } else {
                            testStatus = "Mengambil lokasi…"
                            vm.sendTestLocation { testStatus = it }
                        }
                    },
                    enabled = groupSet,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Kirim lokasi sekarang (tes)")
                }
                if (testStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(testStatus, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun HintRow(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Warning,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
