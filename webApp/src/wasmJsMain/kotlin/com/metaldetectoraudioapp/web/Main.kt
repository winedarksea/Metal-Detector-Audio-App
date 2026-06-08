package com.metaldetectoraudioapp.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
import com.metaldetectoraudioapp.web.audio.WebPassthroughMonitor
import com.metaldetectoraudioapp.web.audio.ensureMicPermission
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
        val systemUsesDarkTheme = isSystemInDarkTheme()
        var themeOverride by remember { mutableStateOf<Boolean?>(null) }
        val useDarkTheme = themeOverride ?: systemUsesDarkTheme

        MetalDetectorAudioTheme(useDarkTheme = useDarkTheme) {
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
                ensureMicPermission()
            }

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

            // The review list is built once on init; re-read the store each time the tab is shown
            // so a recording just saved on the Record tab appears here.
            LaunchedEffect(selected) {
                if (selected == WebDestination.REVIEW) reviewViewModel.refresh()
            }

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
                            val icon = when (dest) {
                                WebDestination.DETECT -> Icons.Default.GraphicEq
                                WebDestination.RECORD -> Icons.Default.Mic
                                WebDestination.REVIEW -> Icons.Default.List
                            }
                            NavigationBarItem(
                                selected = selected == dest,
                                onClick = { selected = dest },
                                icon = { Icon(icon, contentDescription = dest.label) },
                                label = { Text(dest.label) }
                            )
                        }
                    }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize()) {
                    when (selected) {
                        WebDestination.DETECT -> {
                            val vm = inferenceViewModel
                            if (vm != null) {
                                val uiState by vm.uiState.collectAsState()
                                val passthrough by vm.passthroughEnabled.collectAsState()
                                val modelOptions by vm.availableModelOptions.collectAsState()
                                val selectedModelId by vm.selectedModelOptionId.collectAsState()
                                SharedInferenceScreen(
                                    uiState = uiState,
                                    ribbon = vm.ribbon,
                                    passthroughEnabled = passthrough,
                                    availableModelOptions = modelOptions,
                                    selectedModelOptionId = selectedModelId,
                                    onStart = vm::start,
                                    onStop = vm::stop,
                                    onThresholdChange = vm::updateThreshold,
                                    onPassthroughChange = { enabled ->
                                        vm.setPassthroughEnabled(enabled)
                                        WebPassthroughMonitor.setEnabled(enabled)
                                    },
                                    onModelOptionSelected = vm::selectModelOption,
                                    contentPadding = PaddingValues(16.dp),
                                    modifier = Modifier.padding(padding),
                                    micSelector = {
                                        MicSelector(
                                            modifier = Modifier.fillMaxWidth(),
                                            passthroughEnabled = passthrough,
                                        )
                                    },
                                )
                            } else {
                                val err = inferenceError
                                if (err != null) {
                                    Text(err)
                                } else {
                                    Text("Loading model…")
                                }
                            }
                        }
                        // Form screens cap width so fields stay readable on wide monitors,
                        // but still fill the screen on small devices (widthIn is a maximum only).
                        WebDestination.RECORD -> Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .widthIn(max = 800.dp)
                                .fillMaxSize()
                        ) {
                            WebRecordingScreen(recordingViewModel, padding)
                        }
                        WebDestination.REVIEW -> Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .widthIn(max = 800.dp)
                                .fillMaxSize()
                        ) {
                            WebReviewScreen(reviewViewModel, padding)
                        }
                    }

                    SmallFloatingActionButton(
                        onClick = {
                            themeOverride = !useDarkTheme
                        },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, bottom = padding.calculateBottomPadding() + 16.dp),
                    ) {
                        Icon(
                            imageVector = if (useDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = if (useDarkTheme) "Switch to light mode" else "Switch to dark mode",
                        )
                    }
                }
            }
        }
    }
}
