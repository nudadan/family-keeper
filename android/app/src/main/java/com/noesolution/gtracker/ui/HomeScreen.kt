package com.noesolution.gtracker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onOpenSettings: () -> Unit,
) {
    var positions by remember { mutableStateOf<List<Position>>(emptyList()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var centered by remember { mutableStateOf(false) }

    val locale = remember { Locale("id", "ID") }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", locale) }
    val dateFmt = remember { SimpleDateFormat("EEEE, d MMMM yyyy", locale) }

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
                ) {
                    DeviceLabel(name = name, color = color)
                }
            }
        }

        // Clock + date card, top-start.
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shadowElevation = 4.dp,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = timeFmt.format(Date(now)),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = dateFmt.format(Date(now)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${positions.size} device aktif",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        // Settings button, top-end.
        FilledTonalIconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}
