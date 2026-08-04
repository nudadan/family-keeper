package com.noesolution.gtracker.data

import com.squareup.moshi.Json

/** Body sent to POST /api/positions */
data class PositionUpload(
    val deviceId: String,
    val label: String?,
    val lat: Double,
    val lng: Double,
    val accuracy: Double?,
    val speed: Double?,
    val timestamp: Long,
    val allowAudio: Boolean,
)

/** A pending emergency-audio request targeting this device. */
data class AudioRequest(
    val id: String,
    @Json(name = "requester_label") val requesterLabel: String?,
    @Json(name = "created_at") val createdAt: Long,
)

/** Body for POST /api/audio/request */
data class AudioRequestBody(val targetDeviceId: String)

/** Response of POST /api/audio/request */
data class AudioRequestResponse(val requestId: String)

/** One row of the emergency-audio transparency log. */
data class AudioLogEntry(
    val id: String,
    val status: String,
    @Json(name = "created_at") val createdAt: Long,
    @Json(name = "target_label") val targetLabel: String?,
    @Json(name = "requester_label") val requesterLabel: String?,
)

/** One point in a device's track. */
data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val accuracy: Double?,
    val speed: Double?,
    val recordedAt: Long,
)

/** A single device's track (points are chronological, oldest first). */
data class Track(
    val deviceId: String,
    val label: String?,
    val points: List<TrackPoint>,
)

/** Response of GET /api/positions/tracks */
data class TracksResponse(
    val since: Long,
    val tracks: List<Track>,
)

/** A position row as returned by the backend. */
data class Position(
    val id: Long,
    @Json(name = "device_id") val deviceId: String,
    val label: String?,
    val lat: Double,
    val lng: Double,
    val accuracy: Double?,
    val speed: Double?,
    @Json(name = "recorded_at") val recordedAt: Long,
    @Json(name = "received_at") val receivedAt: Long,
)
