package com.metaldetectoraudioapp.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.metaldetectoraudioapp.app.ui.screen.InferenceScreen
import com.metaldetectoraudioapp.app.ui.screen.RecordingScreen
import com.metaldetectoraudioapp.app.ui.screen.ReviewScreen

private enum class AppDestination(val route: String, val label: String) {
    INFERENCE("inference", "Detect"),
    RECORD("record", "Record"),
    REVIEW("review", "Review")
}

@Composable
fun MetalDetectorAudioRoot(
    hasMicrophonePermission: Boolean,
    onRequestMicrophonePermission: () -> Unit
) {
    val navController = rememberNavController()
    val inferenceViewModel: InferenceViewModel = viewModel()
    val recordingViewModel: RecordingViewModel = viewModel()
    val reviewViewModel: ReviewViewModel = viewModel()

    val destinations = AppDestination.entries
    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            NavigationBar {
                destinations.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {},
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { padding ->
        if (!hasMicrophonePermission) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Microphone permission is required.")
                Button(onClick = onRequestMicrophonePermission, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Grant Permission")
                }
            }
        } else {
            NavHost(
                navController = navController,
                startDestination = AppDestination.INFERENCE.route,
                modifier = Modifier.padding(padding)
            ) {
                composable(AppDestination.INFERENCE.route) {
                    InferenceScreen(
                        viewModel = inferenceViewModel,
                        contentPadding = PaddingValues(16.dp)
                    )
                }
                composable(AppDestination.RECORD.route) {
                    RecordingScreen(
                        viewModel = recordingViewModel,
                        contentPadding = PaddingValues(16.dp)
                    )
                }
                composable(AppDestination.REVIEW.route) {
                    ReviewScreen(
                        viewModel = reviewViewModel,
                        contentPadding = PaddingValues(16.dp)
                    )
                }
            }
        }
    }
}
