package com.metaldetectoraudioapp.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.metaldetectoraudioapp.app.audio.source.DesktopPassthroughPlayer
import com.metaldetectoraudioapp.app.inference.DesktopInferenceControllerFactory
import com.metaldetectoraudioapp.app.ui.SharedInferenceViewModel
import com.metaldetectoraudioapp.app.ui.screen.SharedInferenceScreen
import com.metaldetectoraudioapp.app.ui.theme.MetalDetectorAudioTheme

fun main() = application {
    val windowState = rememberWindowState(width = 480.dp, height = 820.dp)

    Window(
        onCloseRequest = ::exitApplication,
        title = "Metal Detector Audio",
        state = windowState,
    ) {
        val passthroughPlayer = remember { DesktopPassthroughPlayer() }
        val viewModel = remember {
            val controller = DesktopInferenceControllerFactory.create(
                passthroughSink = passthroughPlayer,
            )
            SharedInferenceViewModel(controller)
        }

        DisposableEffect(Unit) {
            onDispose {
                viewModel.close()
                passthroughPlayer.release()
            }
        }

        val uiState by viewModel.uiState.collectAsState()
        val passthroughEnabled by viewModel.passthroughEnabled.collectAsState()

        MetalDetectorAudioTheme {
            SharedInferenceScreen(
                uiState = uiState,
                passthroughEnabled = passthroughEnabled,
                onStart = viewModel::start,
                onStop = viewModel::stop,
                onThresholdChange = viewModel::updateThreshold,
                onPassthroughChange = viewModel::setPassthroughEnabled,
            )
        }
    }
}
