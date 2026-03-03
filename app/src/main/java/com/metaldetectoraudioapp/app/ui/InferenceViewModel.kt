package com.metaldetectoraudioapp.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.metaldetectoraudioapp.app.inference.InferenceController
import com.metaldetectoraudioapp.app.inference.InferenceControllerFactory
import com.metaldetectoraudioapp.app.inference.InferenceUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class InferenceViewModel(application: Application) : AndroidViewModel(application) {
    private val controller: InferenceController = InferenceControllerFactory.create(application.applicationContext)

    private val _uiState = MutableStateFlow(controller.uiState.value)
    val uiState: StateFlow<InferenceUiState> = _uiState.asStateFlow()

    private val _passthroughEnabled = MutableStateFlow(false)
    val passthroughEnabled: StateFlow<Boolean> = _passthroughEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            controller.uiState.collectLatest { state ->
                _uiState.value = state
            }
        }
    }

    fun start() {
        controller.start()
    }

    fun stop() {
        controller.stop()
    }

    fun updateThreshold(value: Float) {
        controller.setThreshold(value)
    }

    fun setPassthroughEnabled(enabled: Boolean) {
        _passthroughEnabled.value = enabled
        controller.setPassthroughEnabled(enabled)
    }

    override fun onCleared() {
        super.onCleared()
        controller.release()
    }
}
