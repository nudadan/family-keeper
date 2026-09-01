package com.noesolution.gtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noesolution.gtracker.ui.AppDestination
import com.noesolution.gtracker.ui.EmergencyAudioScreen
import com.noesolution.gtracker.ui.HomeScreen
import com.noesolution.gtracker.ui.MainViewModel
import com.noesolution.gtracker.ui.PickupScreen
import com.noesolution.gtracker.ui.SettingsScreen
import com.noesolution.gtracker.ui.theme.GardeniaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GardeniaTheme {
                val vm: MainViewModel = viewModel()
                // Dashboard is the landing tab; resume sending on launch if it
                // was active before (reboot itself is handled by BootReceiver).
                var destination by remember { mutableStateOf(AppDestination.Home) }
                LaunchedEffect(Unit) { vm.resumeTrackingIfEnabled() }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            AppDestination.entries.forEach { dest ->
                                NavigationBarItem(
                                    selected = destination == dest,
                                    onClick = { destination = dest },
                                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                                    label = { Text(dest.label) },
                                    colors = NavigationBarItemDefaults.colors(),
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                        when (destination) {
                            AppDestination.Home -> HomeScreen(vm = vm)
                            AppDestination.Pickup -> PickupScreen(vm = vm)
                            AppDestination.Emergency -> EmergencyAudioScreen(vm = vm)
                            AppDestination.Settings -> SettingsScreen(vm = vm)
                        }
                    }
                }
            }
        }
    }
}
