package com.noesolution.gtracker.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector

/** The app's four main destinations, shown as bottom-navigation tabs. */
enum class AppDestination(val label: String, val icon: ImageVector) {
    Home("Beranda", Icons.Filled.Home),
    Pickup("Jemput", Icons.Filled.DirectionsCar),
    Emergency("Darurat", Icons.Filled.Warning),
    Settings("Setting", Icons.Filled.Settings),
}
