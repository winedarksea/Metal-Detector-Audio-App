package com.metaldetectoraudioapp.web

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.metaldetectoraudioapp.app.audio.AudioPlayer
import com.metaldetectoraudioapp.app.export.DatasetBundleManager
import com.metaldetectoraudioapp.app.recording.RecordingRepository
import com.metaldetectoraudioapp.app.ui.SharedInferenceViewModel
import com.metaldetectoraudioapp.app.ui.screen.SharedInferenceScreen
import com.metaldetectoraudioapp.app.ui.theme.MetalDetectorAudioTheme
import com.metaldetectoraudioapp.web.audio.WebAudioPlayer
import com.metaldetectoraudioapp.web.export.WebZipCodec
import com.metaldetectoraudioapp.web.inference.WebInferenceControllerFactory
import com.metaldetectoraudioapp.web.platform.WebFileDownloader
import com.metaldetectoraudioapp.web.platform.WebFilePicker
import com.metaldetectoraudioapp.web.storage.IndexedDbDatasetStore
import com.metaldetectoraudioapp.web.ui.screen.MicSelector
import com.metaldetectoraudioapp.web.ui.screen.WebRecordingScreen
import com.metaldetectoraudioapp.web.ui.screen.WebReviewScreen
import com.metaldetectoraudioapp.web.viewmodel.WebRecordingViewModel
import com.metaldetectoraudioapp.web.viewmodel.WebReviewViewModel
import kotlinx.browser.document

private enum class WebDestination(val label: String) {
    DETECT("Detect"),
    RECORD("Record"),
    REVIEW("Review"),
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        MetalDetectorAudioTheme {
            val store = remember { IndexedDbDatasetStore() }
            val zipCodec = remember { WebZipCodec() }
            val audioPlayer: AudioPlayer = remember { WebAudioPlayer() }
            val fileDownloader = remember { WebFileDownloader() }
            val filePicker = remember { WebFilePicker() }
            val recordingRepository = remember { RecordingRepository(store) }
            val bundleManager = remember { DatasetBundleManager(recordingRepository, zipCodec) }

            var inferenceViewModel by remember { mutableStateOf<SharedInferenceViewModel?>(null) }
            var inferenceError by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(Unit) {
                runCatching {
                    val controller = WebInferenceControllerFactory.create()
                    inferenceViewModel = SharedInferenceViewModel(controller)
                }.onFailure { e ->
                    inferenceError = "Failed to load model: ${e.message}"
                }
            }

            val recordingViewModel = remember {
                WebRecordingViewModel(recordingRepository, audioPlayer)
            }
            val reviewViewModel = remember {
                WebReviewViewModel(recordingRepository, bundleManager, audioPlayer, fileDownloader, filePicker)
            }

            var selected by remember { mutableStateOf(WebDestination.DETECT) }

            DisposableEffect(Unit) {
                onDispose {
                    inferenceViewModel?.close()
                    recordingViewModel.close()
                    reviewViewModel.close()
                }
            }

            Scaffold(
                bottomBar = {
                    NavigationBar {
                        WebDestination.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = selected == dest,
                                onClick = { selected = dest },
                                icon = {},
                                label = { Text(dest.label) }
                            )
                        }
                    }
                }
            ) { padding ->
                when (selected) {
                    WebDestination.DETECT -> {
                        val vm = inferenceViewModel
                        if (vm != null) {
                            val uiState by vm.uiState.collectAsState()
                            val passthrough by vm.passthroughEnabled.collectAsState()
                            val modelOptions by vm.availableModelOptions.collectAsState()
                            val selectedModelId by vm.selectedModelOptionId.collectAsState()
                            Column(modifier = Modifier.padding(padding)) {
                                MicSelector(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .padding(top = 16.dp)
                                )
                                SharedInferenceScreen(
                                    uiState = uiState,
                                    ribbon = vm.ribbon,
                                    passthroughEnabled = passthrough,
                                    availableModelOptions = modelOptions,
                                    selectedModelOptionId = selectedModelId,
                                    onStart = vm::start,
                                    onStop = vm::stop,
                                    onThresholdChange = vm::updateThreshold,
                                    onPassthroughChange = vm::setPassthroughEnabled,
                                    onModelOptionSelected = vm::selectModelOption,
                                    contentPadding = PaddingValues(16.dp),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        } else {
                            val err = inferenceError
                            if (err != null) {
                                Text(err)
                            } else {
                                Text("Loading model…")
                            }
                        }
                    }
                    WebDestination.RECORD -> WebRecordingScreen(recordingViewModel, padding)
                    WebDestination.REVIEW -> WebReviewScreen(reviewViewModel, padding)
                }
            }
        }
    }
}
