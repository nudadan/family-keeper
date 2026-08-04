package com.noesolution.gtracker.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.noesolution.gtracker.admin.GTrackerDeviceAdminReceiver

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    vm: MainViewModel,
) {
    val settings by vm.settings.collectAsState()

    var label by remember(settings) { mutableStateOf(settings?.label ?: "") }
    var group by remember(settings) { mutableStateOf(settings?.groupCode ?: "") }
    var interval by remember(settings) {
        mutableStateOf((settings?.intervalMinutes ?: 5).toString())
    }
    var saved by remember { mutableStateOf(false) }

    // --- Device Admin (uninstall protection) ---
    val context = LocalContext.current
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
                Text(
                    text = "Device ID: ${settings?.deviceId ?: "…"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
