package com.metaldetectoraudioapp.app.ui

import android.app.Application
import android.media.AudioDeviceInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.metaldetectoraudioapp.app.audio.source.AudioDeviceManager
import com.metaldetectoraudioapp.app.inference.InferenceController
import com.metaldetectoraudioapp.app.inference.InferenceControllerFactory
import com.metaldetectoraudioapp.app.inference.InferenceModelOption
import com.metaldetectoraudioapp.app.inference.InferenceUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class InferenceViewModel(application: Application) : AndroidViewModel(application) {
    private val controller: InferenceController = InferenceControllerFactory.create(application.applicationContext)
    val deviceManager = AudioDeviceManager(application.applicationContext)
    private val defaultModelOption = InferenceModelOption(
        id = "${controller.uiState.value.modelName}:${controller.uiState.value.modelVersion}",
        label = "${controller.uiState.value.modelName} v${controller.uiState.value.modelVersion}"
    )

    private val _uiState = MutableStateFlow(controller.uiState.value)
    val uiState: StateFlow<InferenceUiState> = _uiState.asStateFlow()

    private val _passthroughEnabled = MutableStateFlow(false)
    val passthroughEnabled: StateFlow<Boolean> = _passthroughEnabled.asStateFlow()

    private val _availableModelOptions = MutableStateFlow(listOf(defaultModelOption))
    val availableModelOptions: StateFlow<List<InferenceModelOption>> = _availableModelOptions.asStateFlow()

    private val _selectedModelOptionId = MutableStateFlow(defaultModelOption.id)
    val selectedModelOptionId: StateFlow<String> = _selectedModelOptionId.asStateFlow()

    private val _selectedInputDevice = MutableStateFlow<AudioDeviceInfo?>(null)
    val selectedInputDevice: StateFlow<AudioDeviceInfo?> = _selectedInputDevice.asStateFlow()

    private val _selectedOutputDevice = MutableStateFlow<AudioDeviceInfo?>(null)
    val selectedOutputDevice: StateFlow<AudioDeviceInfo?> = _selectedOutputDevice.asStateFlow()

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

    fun selectModelOption(optionId: String) {
        if (_availableModelOptions.value.any { it.id == optionId }) {
            _selectedModelOptionId.value = optionId
        }
    }

    fun setInputDevice(device: AudioDeviceInfo?) {
        _selectedInputDevice.value = device
        controller.setInputDevice(device)
    }

    fun setOutputDevice(device: AudioDeviceInfo?) {
        _selectedOutputDevice.value = device
        controller.setOutputDevice(device)
    }

    override fun onCleared() {
        super.onCleared()
        controller.release()
        deviceManager.release()
    }
}
