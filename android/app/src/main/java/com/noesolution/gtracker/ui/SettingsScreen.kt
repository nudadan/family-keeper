package com.noesolution.gtracker.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.noesolution.gtracker.admin.GTrackerDeviceAdminReceiver
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
fun SettingsScreen(
    modifier: Modifier = Modifier,
    vm: MainViewModel,
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val tracking = settings?.trackingEnabled == true
    val groupSet = settings?.groupCode?.isNotBlank() == true

    // Editable copies of the persisted fields. These must load from `settings`
    // exactly ONCE (when it first becomes available) and never again — settings
    // re-emits on ANY change (e.g. toggling audio consent, start/stop tracking),
    // and re-seeding on every emission would wipe out text the user is mid-typing
    // here but hasn't pressed Simpan for yet.
    var label by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf("5") }
    var loadedInitial by remember { mutableStateOf(false) }
    LaunchedEffect(settings) {
        val s = settings
        if (s != null && !loadedInitial) {
            label = s.label
            group = s.groupCode
            interval = s.intervalMinutes.toString()
            loadedInitial = true
        }
    }
    var saved by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf("") }

    // --- Location permission / battery status (from the old Tracker screen) ---
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
            testStatus = "⚠️ Isi Group code di bawah, lalu Simpan, sebelum mulai."
            return
        }
        requestForegroundPermission()
    }

    // --- Device Admin (uninstall protection) ---
    val dpm = remember {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    val adminComponent = remember { ComponentName(context, GTrackerDeviceAdminReceiver::class.java) }
    var adminActive by remember { mutableStateOf(dpm.isAdminActive(adminComponent)) }
    val adminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        adminActive = dpm.isAdminActive(adminComponent)
    }

    // Enabling audio consent must first obtain the RECORD_AUDIO permission.
    var audioMsg by remember { mutableStateOf("") }
    val recordAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        vm.setAllowAudio(granted)
        audioMsg = if (granted) "" else "Izin mikrofon ditolak — audio darurat tidak bisa aktif."
    }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenTopBar(title = "Setting")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // --- Status pengiriman (start/stop) ---
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
                            text = "Isi Group code di bawah, lalu Simpan, sebelum mulai.",
                            containerColor = StatusWarnAmberContainer,
                            contentColor = StatusWarnAmberOn,
                        )
                    }
                }
            }

            // --- Profil ---
            SectionCard {
                SectionHeading("Profil perangkat", icon = Icons.Filled.Person)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it; saved = false },
                    label = { Text("Nama device (tampil di peta)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(28.dp).clip(CircleShape),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = (label.ifBlank { "?" }).take(1).uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "Device ID: ${settings?.deviceId ?: "…"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // --- Grup & interval ---
            SectionCard {
                SectionHeading("Grup & interval", icon = Icons.Filled.Group)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it; saved = false },
                    label = { Text("Group code") },
                    supportingText = { Text("Perangkat dengan kode sama saling terlihat") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = interval,
                    onValueChange = { interval = it.filter { c -> c.isDigit() }; saved = false },
                    label = { Text("Interval pengiriman (menit)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
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

            // --- Emergency audio consent ---
            SectionCard {
                SectionHeading("Audio darurat", icon = Icons.Filled.Mic)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Izinkan anggota grup meminta audio darurat dari perangkat ini",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = settings?.allowAudio == true,
                        onCheckedChange = { want ->
                            if (want) {
                                val granted = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    vm.setAllowAudio(true)
                                    audioMsg = ""
                                } else {
                                    recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            } else {
                                vm.setAllowAudio(false)
                                audioMsg = ""
                            }
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Saat direkam, HP akan berbunyi + bergetar + menampilkan notifikasi. Tidak ada perekaman diam-diam.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (audioMsg.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = audioMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // --- Uninstall protection ---
            SectionCard {
                SectionHeading("Proteksi aplikasi", icon = Icons.Filled.Shield)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (adminActive) {
                        "Proteksi uninstall AKTIF. Untuk menghapus aplikasi, nonaktifkan dulu di sini."
                    } else {
                        "Cegah aplikasi terhapus tanpa sengaja dari perangkat ini."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (adminActive) {
                    OutlinedButton(
                        onClick = {
                            dpm.removeActiveAdmin(adminComponent)
                            adminActive = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Nonaktifkan proteksi uninstall")
                    }
                } else {
                    Button(
                        onClick = {
                            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                                putExtra(
                                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    "Mencegah aplikasi Gardenia-1 dihapus dari perangkat tanpa sengaja.",
                                )
                            }
                            adminLauncher.launch(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Aktifkan proteksi uninstall")
                    }
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
                            testStatus = "⚠️ Isi Group code di bawah, lalu Simpan, dulu."
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

            Button(
                onClick = {
                    val minutes = interval.toIntOrNull()?.coerceAtLeast(1) ?: 5
                    vm.saveSettings(label, group, minutes)
                    saved = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (saved) "Tersimpan ✓" else "Simpan")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HintRow(
    text: String,
    containerColor: Color,
    contentColor: Color,
    icon: ImageVector = Icons.Filled.Warning,
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
