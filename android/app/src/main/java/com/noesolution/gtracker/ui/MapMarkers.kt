package com.noesolution.gtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** An always-visible map label: a colored pill with the name and a dot below. */
@Composable
internal fun DeviceLabel(name: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = name,
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(color)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(12.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/** Stable, distinct-ish color per device, derived from its id. */
internal fun colorForDevice(deviceId: String): Color {
    val hue = ((deviceId.hashCode() % 360) + 360) % 360
    return Color.hsv(hue.toFloat(), 0.85f, 0.85f)
}
