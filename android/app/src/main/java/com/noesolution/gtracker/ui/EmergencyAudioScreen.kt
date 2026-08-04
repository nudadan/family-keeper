package com.noesolution.gtracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.noesolution.gtracker.data.Position

@Composable
fun EmergencyAudioScreen(
    modifier: Modifier = Modifier,
    vm: MainViewModel,
    onBack: () -> Unit,
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Audio darurat", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Minta audio sekitar dari perangkat anggota grup. Perangkat itu akan " +
                "berbunyi & memberi notifikasi saat merekam — tidak ada perekaman diam-diam. " +
                "Hanya perangkat yang mengaktifkan izin audio yang bisa diminta.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))

        if (listStatus.isNotBlank()) {
            Text(listStatus, style = MaterialTheme.typography.bodyMedium)
        }

        devices.forEach { d ->
            val name = d.label?.takeIf { it.isNotBlank() } ?: d.deviceId
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    val s = statuses[d.deviceId]
                    if (!s.isNullOrBlank()) {
                        Text(s, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(onClick = {
                    statuses = statuses + (d.deviceId to "Meminta audio…")
                    vm.requestAudio(d.deviceId) { st ->
                        statuses = statuses + (d.deviceId to st)
                    }
                }) {
                    Text("Minta audio")
                }
            }
            Divider(modifier = Modifier.padding(top = 12.dp))
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { refreshDevices() }) { Text("Refresh daftar") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}
