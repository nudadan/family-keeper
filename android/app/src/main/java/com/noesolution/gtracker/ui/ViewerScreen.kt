package com.noesolution.gtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.noesolution.gtracker.data.Track

private const val TRACK_HOURS = 12.0

@OptIn(MapsComposeExperimentalApi::class)
@Composable
fun ViewerScreen(
    modifier: Modifier = Modifier,
    vm: MainViewModel,
    onBack: () -> Unit,
) {
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var status by remember { mutableStateOf("Loading…") }

    val cameraPositionState = rememberCameraPositionState {
        // Default view: Indonesia.
        position = CameraPosition.fromLatLngZoom(LatLng(-2.5, 118.0), 4f)
    }

    fun refresh() {
        status = "Loading…"
        vm.loadTracks(TRACK_HOURS) { result ->
            result
                .onSuccess { list ->
                    // Only keep tracks that actually have points.
                    val nonEmpty = list.filter { it.points.isNotEmpty() }
                    tracks = nonEmpty

                    val allPoints = nonEmpty.flatMap { it.points }
                    status = when {
                        nonEmpty.isEmpty() -> "No positions in the last 12h"
                        else -> "${nonEmpty.size} device(s), ${allPoints.size} points (last 12h)"
                    }

                    // Fit the camera to everything we have.
                    val latLngs = allPoints.map { LatLng(it.lat, it.lng) }
                    when (latLngs.size) {
                        0 -> {}
                        1 -> cameraPositionState.position =
                            CameraPosition.fromLatLngZoom(latLngs.first(), 15f)
                        else -> {
                            val boundsBuilder = LatLngBounds.builder()
                            latLngs.forEach { boundsBuilder.include(it) }
                            runCatching {
                                cameraPositionState.move(
                                    CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 120)
                                )
                            }
                        }
                    }
                }
                .onFailure { status = "Error: ${it.message}" }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
            ) {
                tracks.forEach { track ->
                    val color = colorForDevice(track.deviceId)
                    val latLngs = track.points.map { LatLng(it.lat, it.lng) }
                    val name = track.label?.takeIf { it.isNotBlank() } ?: track.deviceId

                    if (latLngs.size >= 2) {
                        Polyline(
                            points = latLngs,
                            color = color,
                            width = 8f,
                        )
                    }

                    // Marker at the most recent point (last in chronological order),
                    // showing the device name as an always-visible label.
                    track.points.lastOrNull()?.let { last ->
                        val pos = LatLng(last.lat, last.lng)
                        MarkerComposable(
                            keys = arrayOf(name, last.lat, last.lng),
                            state = MarkerState(position = pos),
                            title = name,
                            snippet = "${track.points.size} points · last 12h",
                        ) {
                            DeviceLabel(name = name, color = color)
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(status)
            Button(onClick = { refresh() }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Refresh")
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
                Text("Back")
            }
        }
    }
}

// DeviceLabel and colorForDevice live in MapMarkers.kt (shared with Home).
