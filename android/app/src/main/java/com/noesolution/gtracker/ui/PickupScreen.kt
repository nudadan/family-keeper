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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.delay

private const val COOLDOWN_SECONDS = 60

@Composable
fun PickupScreen(
    modifier: Modifier = Modifier,
    vm: MainViewModel,
) {
    val settings by vm.settings.collectAsState()
    val groupSet = settings?.groupCode?.isNotBlank() == true

    var note by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var cooldown by remember { mutableStateOf(0) }

    // Countdown ticker while a cooldown is active.
    LaunchedEffect(cooldown > 0) {
        while (cooldown > 0) {
            delay(1000)
            cooldown -= 1
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenTopBar(title = "Jemput")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
                        text = "Kirim permintaan dijemput ke grup WhatsApp keluarga, lengkap " +
                            "dengan lokasi Anda saat ini (jika tersedia).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard {
                SectionHeading("Permintaan Jemput", icon = Icons.Filled.DirectionsCar)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Catatan (opsional)") },
                    placeholder = { Text("Contoh: di depan sekolah") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        sending = true
                        status = "Mengirim…"
                        vm.requestPickup(note) { result ->
                            sending = false
                            status = result
                            cooldown = COOLDOWN_SECONDS
                        }
                    },
                    enabled = groupSet && !sending && cooldown == 0,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                    ),
                ) {
                    Icon(Icons.Filled.DirectionsCar, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        when {
                            sending -> "Mengirim…"
                            cooldown > 0 -> "Tunggu ${cooldown}d"
                            else -> "Kirim Permintaan Jemput"
                        }
                    )
                }
                if (!groupSet) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "⚠️ Isi Group code di tab Setting sebelum mengirim.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (status.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
