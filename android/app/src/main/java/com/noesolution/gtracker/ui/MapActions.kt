package com.noesolution.gtracker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens turn-by-turn navigation to (lat, lng) in the Google Maps app. Falls
 * back to the Maps website (in any browser) if the app isn't installed.
 */
fun openNavigation(context: Context, lat: Double, lng: Double) {
    val gmmUri = Uri.parse("google.navigation:q=$lat,$lng")
    val mapIntent = Intent(Intent.ACTION_VIEW, gmmUri).apply {
        setPackage("com.google.android.apps.maps")
    }
    try {
        context.startActivity(mapIntent)
    } catch (_: Exception) {
        val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng")
        context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
    }
}

/**
 * Opens the Android share sheet with a Google Maps link to (lat, lng), so the
 * user can send it via WhatsApp, SMS, etc.
 */
fun shareLocation(context: Context, deviceName: String, lat: Double, lng: Double) {
    val link = "https://maps.google.com/?q=$lat,$lng"
    val text = "Posisi $deviceName: $link"
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(sendIntent, "Bagikan lokasi"))
}
