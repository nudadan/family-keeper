package com.noesolution.gtracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.noesolution.gtracker.data.Position
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(MapsComposeExperimentalApi::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    vm: MainViewModel,
) {
    val context = LocalContext.current
    var positions by remember { mutableStateOf<List<Position>>(emptyList()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var centered by remember { mutableStateOf(false) }
    var selectedDeviceId by remember { mutableStateOf<String?>(null) }
    var checkInSending by remember { mutableStateOf(false) }
    var checkInStatus by remember { mutableStateOf("") }

    val locale = remember { Locale("id", "ID") }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", locale) }
    val dateFmt = remember { SimpleDateFormat("EEEE, d MMMM yyyy", locale) }
    val lastUpdateFmt = remember { SimpleDateFormat("HH:mm:ss, d MMM yyyy", locale) }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(-2.5, 118.0), 4f)
    }

    // Live clock: tick every second.
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    // Refresh device positions on launch and every 20 seconds.
    LaunchedEffect(Unit) {
        while (true) {
            vm.loadLatest { result ->
                result.onSuccess { list ->
                    positions = list
                    if (!centered && list.isNotEmpty()) {
                        centered = true
                        val latLngs = list.map { LatLng(it.lat, it.lng) }
                        if (latLngs.size == 1) {
                            cameraPositionState.position =
                                CameraPosition.fromLatLngZoom(latLngs.first(), 15f)
                        } else {
                            val b = LatLngBounds.builder()
                            latLngs.forEach { b.include(it) }
                            runCatching {
                                cameraPositionState.move(
                                    CameraUpdateFactory.newLatLngBounds(b.build(), 120)
                                )
                            }
                        }
                    }
                }
            }
            delay(20_000)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
        ) {
            positions.forEach { p ->
                val name = p.label?.takeIf { it.isNotBlank() } ?: p.deviceId
                val color = colorForDevice(p.deviceId)
                MarkerComposable(
                    keys = arrayOf(name, p.lat, p.lng),
                    state = MarkerState(position = LatLng(p.lat, p.lng)),
                    title = name,
                    onClick = {
                        selectedDeviceId = p.deviceId
                        true // consume: show our own info panel instead of the default one
                    },
                ) {
                    DeviceLabel(name = name, color = color)
                }
            }
        }

        // Clock + date card, top-start.
        ElevatedCard(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text(
                    text = timeFmt.format(Date(now)),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = dateFmt.format(Date(now)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.PeopleAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = "${positions.size} device aktif",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }

        // Check-in: a lightweight, non-urgent "I'm okay" ping — complements
        // the SOS button on the Darurat screen without living on the same
        // screen (different tone, shouldn't be reachable by the same reflex).
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            ExtendedFloatingActionButton(
                onClick = {
                    checkInSending = true
                    checkInStatus = ""
                    vm.checkIn { st ->
                        checkInSending = false
                        checkInStatus = st
                    }
                },
                icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                text = { Text(if (checkInSending) "Mengirim…" else "Saya Baik-Baik Saja") },
            )
            if (checkInStatus.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = checkInStatus,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // Device info panel, shown after tapping a marker.
        val selected = positions.firstOrNull { it.deviceId == selectedDeviceId }
        if (selected != null) {
            val name = selected.label?.takeIf { it.isNotBlank() } ?: selected.deviceId
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(onClick = { selectedDeviceId = null }) {
                            Icon(Icons.Filled.Close, contentDescription = "Tutup")
                        }
                    }
                    Text(
                        text = "Update GPS terakhir: ${lastUpdateFmt.format(Date(selected.recordedAt))}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = timeAgo(selected.recordedAt, now),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "Akurasi: ${selected.accuracy?.let { "±${it.toInt()} m" } ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    selected.speed?.let { spd ->
                        Text(
                            text = "Kecepatan: ${"%.1f".format(spd * 3.6)} km/jam",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = "Koordinat: %.5f, %.5f".format(selected.lat, selected.lng),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { openNavigation(context, selected.lat, selected.lng) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                            ),
                        ) {
                            Icon(
                                Icons.Filled.Directions,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("Navigasi")
                        }
                        Spacer(Modifier.size(8.dp))
                        OutlinedButton(
                            onClick = { shareLocation(context, name, selected.lat, selected.lng) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("Bagikan")
                        }
                    }
                }
            }
        }
    }
}

/** Human-readable "X menit lalu" style relative time, in Indonesian. */
private fun timeAgo(eventMs: Long, nowMs: Long): String {
    val diff = (nowMs - eventMs).coerceAtLeast(0)
    val sec = diff / 1000
    return when {
        sec < 60 -> "baru saja"
        sec < 3600 -> "${sec / 60} menit lalu"
        sec < 86_400 -> "${sec / 3600} jam lalu"
        else -> "${sec / 86_400} hari lalu"
    }
}
