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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noesolution.gtracker.data.Position

@Composable
fun EmergencyAudioScreen(
    modifier: Modifier = Modifier,
    vm: MainViewModel,
) {
    val settings by vm.settings.collectAsState()
    val myId = settings?.deviceId
    var devices by remember { mutableStateOf<List<Position>>(emptyList()) }
    var listStatus by remember { mutableStateOf("Memuat…") }
    // requestId/target -> status text
    var statuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

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
                            "yang bisa diminta.",
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
                SectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                            Column {
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                                val s = statuses[d.deviceId]
                                if (!s.isNullOrBlank()) {
                                    Text(s, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
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
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
