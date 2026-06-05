package com.metaldetectoraudioapp.desktop

import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.metaldetectoraudioapp.app.DesktopAppContainer
import com.metaldetectoraudioapp.app.audio.source.DesktopPassthroughPlayer
import com.metaldetectoraudioapp.app.inference.DesktopInferenceControllerFactory
import com.metaldetectoraudioapp.app.ui.SharedInferenceViewModel
import com.metaldetectoraudioapp.app.ui.screen.SharedInferenceScreen
import com.metaldetectoraudioapp.app.ui.theme.MetalDetectorAudioTheme
import com.metaldetectoraudioapp.desktop.ui.screen.DesktopRecordingScreen
import com.metaldetectoraudioapp.desktop.ui.screen.DesktopReviewScreen
import com.metaldetectoraudioapp.desktop.viewmodel.DesktopRecordingViewModel
import com.metaldetectoraudioapp.desktop.viewmodel.DesktopReviewViewModel
import java.io.File

private enum class DesktopDestination(val label: String) {
    INFERENCE("Detect"),
    RECORD("Record"),
    REVIEW("Review")
}

fun main() = application {
    val windowState = rememberWindowState(width = 480.dp, height = 820.dp)

    Window(
        onCloseRequest = ::exitApplication,
        title = "Metal Detector Audio",
        state = windowState,
    ) {
        val appContainer = remember { DesktopAppContainer() }
        val passthroughPlayer = remember { DesktopPassthroughPlayer() }
        val inferenceViewModel = remember {
            val controller = DesktopInferenceControllerFactory.create(
                passthroughSink = passthroughPlayer,
            )
            SharedInferenceViewModel(controller)
        }
        val recordingViewModel = remember {
            DesktopRecordingViewModel(
                recordingRepository = appContainer.recordingRepository,
                audioPlayer = appContainer.audioPlayer,
                recordingSessionCacheDirectoryPath = File(
                    appContainer.appDataDirectory,
                    "cache"
                ).absolutePath,
                datasetDirectoryPath = appContainer.datasetDirectoryPath,
            )
        }
        val reviewViewModel = remember {
            DesktopReviewViewModel(
                recordingRepository = appContainer.recordingRepository,
                bundleManager = appContainer.datasetBundleManager,
                audioPlayer = appContainer.audioPlayer,
                datasetDirectoryPath = appContainer.datasetDirectoryPath,
            )
        }
        var selectedDestination by remember { mutableStateOf(DesktopDestination.INFERENCE) }

        DisposableEffect(Unit) {
            onDispose {
                inferenceViewModel.close()
                recordingViewModel.close()
                reviewViewModel.close()
                passthroughPlayer.release()
            }
        }

        val inferenceUiState by inferenceViewModel.uiState.collectAsState()
        val passthroughEnabled by inferenceViewModel.passthroughEnabled.collectAsState()
        val availableModelOptions by inferenceViewModel.availableModelOptions.collectAsState()
        val selectedModelOptionId by inferenceViewModel.selectedModelOptionId.collectAsState()
        val destinations = DesktopDestination.entries

        MetalDetectorAudioTheme {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        destinations.forEach { destination ->
                            NavigationBarItem(
                                selected = selectedDestination == destination,
                                onClick = { selectedDestination = destination },
                                icon = {},
                                label = { Text(destination.label) }
                            )
                        }
                    }
                }
            ) { contentPadding ->
                when (selectedDestination) {
                    DesktopDestination.INFERENCE -> {
                        SharedInferenceScreen(
                            uiState = inferenceUiState,
                            passthroughEnabled = passthroughEnabled,
                            availableModelOptions = availableModelOptions,
                            selectedModelOptionId = selectedModelOptionId,
                            onStart = inferenceViewModel::start,
                            onStop = inferenceViewModel::stop,
                            onThresholdChange = inferenceViewModel::updateThreshold,
                            onPassthroughChange = inferenceViewModel::setPassthroughEnabled,
                            onModelOptionSelected = inferenceViewModel::selectModelOption,
                            contentPadding = contentPadding
                        )
                    }

                    DesktopDestination.RECORD -> {
                        DesktopRecordingScreen(
                            viewModel = recordingViewModel,
                            contentPadding = contentPadding
                        )
                    }

                    DesktopDestination.REVIEW -> {
                        DesktopReviewScreen(
                            viewModel = reviewViewModel,
                            contentPadding = contentPadding
                        )
                    }
                }
            }
        }
    }
}
