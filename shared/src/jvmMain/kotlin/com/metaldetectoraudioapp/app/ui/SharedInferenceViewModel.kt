package com.metaldetectoraudioapp.app.ui

import com.metaldetectoraudioapp.app.inference.InferenceController
import com.metaldetectoraudioapp.app.inference.InferenceUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * KMP-clean ViewModel for inference. Uses constructor-injected scope
 * instead of AndroidViewModel / viewModelScope.
 */
class SharedInferenceViewModel(
    private val controller: InferenceController,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _uiState = MutableStateFlow(controller.uiState.value)
    val uiState: StateFlow<InferenceUiState> = _uiState.asStateFlow()

    private val _passthroughEnabled = MutableStateFlow(false)
    val passthroughEnabled: StateFlow<Boolean> = _passthroughEnabled.asStateFlow()

    init {
        scope.launch {
            controller.uiState.collectLatest { state ->
                _uiState.value = state
            }
        }
    }

    fun start() { controller.start() }
    fun stop() { controller.stop() }

    fun updateThreshold(value: Float) { controller.setThreshold(value) }

    fun setPassthroughEnabled(enabled: Boolean) {
        _passthroughEnabled.value = enabled
        controller.setPassthroughEnabled(enabled)
    }

    fun close() {
        controller.release()
        scope.cancel()
    }
}
