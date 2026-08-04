package com.noesolution.gtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noesolution.gtracker.ui.EmergencyAudioScreen
import com.noesolution.gtracker.ui.HomeScreen
import com.noesolution.gtracker.ui.MainViewModel
import com.noesolution.gtracker.ui.SettingsScreen
import com.noesolution.gtracker.ui.TrackerScreen
import com.noesolution.gtracker.ui.ViewerScreen

enum class Screen { Home, Tracker, Viewer, Settings, Emergency }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val vm: MainViewModel = viewModel()
                // Dashboard is the landing page; resume sending on launch if it
                // was active before (reboot is handled by BootReceiver).
                var screen by remember { mutableStateOf(Screen.Home) }
                LaunchedEffect(Unit) { vm.resumeTrackingIfEnabled() }

                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    when (screen) {
                        Screen.Home -> HomeScreen(
                            modifier = Modifier.padding(padding),
                            vm = vm,
                            onOpenSettings = { screen = Screen.Settings },
                        )
                        Screen.Tracker -> TrackerScreen(
                            modifier = Modifier.padding(padding),
                            vm = vm,
                            onBack = { screen = Screen.Settings },
                        )
                        Screen.Viewer -> ViewerScreen(
                            modifier = Modifier.padding(padding),
                            vm = vm,
                            onBack = { screen = Screen.Settings },
                        )
                        Screen.Settings -> SettingsScreen(
                            modifier = Modifier.padding(padding),
                            vm = vm,
                            onBack = { screen = Screen.Home },
                            onOpenTracker = { screen = Screen.Tracker },
                            onOpenViewer = { screen = Screen.Viewer },
                            onOpenEmergency = { screen = Screen.Emergency },
                        )
                        Screen.Emergency -> EmergencyAudioScreen(
                            modifier = Modifier.padding(padding),
                            vm = vm,
                            onBack = { screen = Screen.Settings },
                        )
                    }
                }
            }
        }
    }
}
