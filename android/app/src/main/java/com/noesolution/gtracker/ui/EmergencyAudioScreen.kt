package com.noesolution.gtracker.ui

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
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noesolution.gtracker.data.Position

/** "3 menit lalu" style relative time, in Indonesian. */
private fun timeAgo(eventMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val sec = ((nowMs - eventMs) / 1000).coerceAtLeast(0)
    return when {
        sec < 60 -> "baru saja"
        sec < 3600 -> "${sec / 60} menit lalu"
        sec < 86_400 -> "${sec / 3600} jam lalu"
        else -> "${sec / 86_400} hari lalu"
    }
}

@Composable
fun EmergencyAudioScreen(
    modifier: Modifier = Modifier,
    vm: MainViewModel,
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val myId = settings?.deviceId
    var devices by remember { mutableStateOf<List<Position>>(emptyList()) }
    var listStatus by remember { mutableStateOf("Memuat…") }
    // requestId/target -> status text
    var statuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var alertStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    fun refreshDevices() {
        listStatus = "Memuat…"
        vm.loadLatest { result ->
            result
                .onSuccess { list ->
                    devices = list.filter { it.deviceId != myId }
                    listStatus = if (devices.isEmpty()) "Tidak ada anggota grup lain." else ""
                }
                .onFailure { listStatus = "Error: ${it.message}" }
        }
    }

    LaunchedEffect(Unit) { refreshDevices() }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenTopBar(title = "Audio Darurat") {
            IconButton(onClick = { refreshDevices() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "Minta audio sekitar dari perangkat anggota grup. Perangkat itu " +
                            "akan berbunyi & memberi notifikasi saat merekam — tidak ada " +
                            "perekaman diam-diam. Hanya perangkat yang mengaktifkan izin audio " +
                            "yang bisa diminta. Lokasi terakhir setiap anggota selalu " +
                            "ditampilkan di bawah, terlepas dari berhasil-tidaknya audio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (listStatus.isNotBlank()) {
                Text(
                    listStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            devices.forEach { d ->
                val name = d.label?.takeIf { it.isNotBlank() } ?: d.deviceId
                val audioStatus = statuses[d.deviceId]
                val timedOut = audioStatus?.contains("Timeout") == true
                SectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Surface(
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = name.take(1).uppercase(),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                            Spacer(Modifier.size(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.LocationOn,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.size(2.dp))
                                    Text(
                                        "Lokasi terakhir: ${timeAgo(d.recordedAt)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!audioStatus.isNullOrBlank()) {
                                    Text(audioStatus, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Button(
                                onClick = {
                                    statuses = statuses + (d.deviceId to "Meminta audio…")
                                    vm.requestAudio(d.deviceId) { st ->
                                        statuses = statuses + (d.deviceId to st)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                ),
                            ) {
                                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Minta")
                            }
                            Spacer(Modifier.height(6.dp))
                            IconButton(onClick = { openNavigation(context, d.lat, d.lng) }) {
                                Icon(Icons.Filled.Directions, contentDescription = "Navigasi ke $name")
                            }
                        }
                    }

                    // Fallback: audio timed out (e.g. the person can't act on the
                    // request themselves) — offer to alert the family via
                    // WhatsApp with the last known location instead.
                    if (timedOut) {
                        Spacer(Modifier.height(8.dp))
                        val alertStatus = alertStatuses[d.deviceId]
                        OutlinedButton(
                            onClick = {
                                alertStatuses = alertStatuses + (d.deviceId to "Mengirim…")
                                vm.requestPickup(note = "", targetDeviceId = d.deviceId) { st ->
                                    alertStatuses = alertStatuses + (d.deviceId to st)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Sos, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Kirim peringatan darurat via WhatsApp")
                        }
                        if (!alertStatus.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(alertStatus, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
