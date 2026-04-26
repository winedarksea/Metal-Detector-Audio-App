package com.metaldetectoraudioapp.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
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

private enum class AppDestination(val route: String, val label: String, val description: String) {
    INFERENCE("inference", "Detect", "Real-time AI detection"),
    RECORD("record", "Record", "Capture training samples"),
    REVIEW("review", "Review", "Manage recordings")
}

@Composable
fun MetalDetectorAudioRoot(
    hasMicrophonePermission: Boolean,
    onRequestMicrophonePermission: () -> Unit
) {
    if (!hasMicrophonePermission) {
        PermissionGate(onRequestMicrophonePermission)
    } else {
        MetalDetectorAudioAppContent()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveNavigationSuiteApi::class)
@Composable
private fun MetalDetectorAudioAppContent() {
    val navController = rememberNavController()
    val inferenceViewModel: InferenceViewModel = viewModel()
    val recordingViewModel: RecordingViewModel = viewModel()
    val reviewViewModel: ReviewViewModel = viewModel()

    val destinations = AppDestination.entries
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentDest = destinations.find { dest ->
        currentDestination?.hierarchy?.any { it.route == dest.route } == true
    }

    val navigate: (AppDestination) -> Unit = { destination ->
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            destinations.forEach { destination ->
                val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                item(
                    selected = selected,
                    onClick = { navigate(destination) },
                    icon = {
                        Icon(
                            imageVector = when (destination) {
                                AppDestination.INFERENCE -> Icons.Default.GraphicEq
                                AppDestination.RECORD -> Icons.Default.Mic
                                AppDestination.REVIEW -> Icons.Default.FolderOpen
                            },
                            contentDescription = destination.label
                        )
                    },
                    label = { Text(destination.label) }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(currentDest?.label ?: "")
                            Text(
                                currentDest?.description ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = AppDestination.INFERENCE.route,
                modifier = Modifier.padding(padding)
            ) {
                composable(AppDestination.INFERENCE.route) {
                    InferenceScreen(viewModel = inferenceViewModel, contentPadding = PaddingValues(16.dp))
                }
                composable(AppDestination.RECORD.route) {
                    RecordingScreen(viewModel = recordingViewModel, contentPadding = PaddingValues(16.dp))
                }
                composable(AppDestination.REVIEW.route) {
                    ReviewScreen(viewModel = reviewViewModel, contentPadding = PaddingValues(16.dp))
                }
            }
        }
    }
}

@Composable
private fun PermissionGate(onRequestMicrophonePermission: () -> Unit) {
    Scaffold { padding ->
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
    }
}
