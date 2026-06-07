package com.metaldetectoraudioapp.app.ui

import android.app.Application
import android.media.AudioDeviceInfo
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.metaldetectoraudioapp.app.audio.source.AudioDeviceManager
import com.metaldetectoraudioapp.app.inference.InferenceController
import com.metaldetectoraudioapp.app.inference.InferenceControllerFactory
import com.metaldetectoraudioapp.app.inference.InferenceBackendPreference
import com.metaldetectoraudioapp.app.inference.InferenceModelOption
import com.metaldetectoraudioapp.app.inference.InferenceUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class InferenceViewModel(application: Application) : AndroidViewModel(application) {
    private val controller: InferenceController

    val deviceManager: AudioDeviceManager

    private val defaultModelOption: InferenceModelOption

    private val _uiState: MutableStateFlow<InferenceUiState>
    val uiState: StateFlow<InferenceUiState>

    /** Tone-quality ribbon visual state, read directly by the ribbon renderer. */
    val ribbon: com.metaldetectoraudioapp.app.audio.ribbon.RibbonAnalyzer
        get() = controller.ribbon

    private val _passthroughEnabled = MutableStateFlow(false)
    val passthroughEnabled: StateFlow<Boolean> = _passthroughEnabled.asStateFlow()

    private val _availableModelOptions: MutableStateFlow<List<InferenceModelOption>>
    val availableModelOptions: StateFlow<List<InferenceModelOption>>

    private val _selectedModelOptionId: MutableStateFlow<String>
    val selectedModelOptionId: StateFlow<String>

    private val _selectedInputDevice = MutableStateFlow<AudioDeviceInfo?>(null)
    val selectedInputDevice: StateFlow<AudioDeviceInfo?> = _selectedInputDevice.asStateFlow()

    private val _selectedOutputDevice = MutableStateFlow<AudioDeviceInfo?>(null)
    val selectedOutputDevice: StateFlow<AudioDeviceInfo?> = _selectedOutputDevice.asStateFlow()

    init {
        Log.i(TAG, "Initializing InferenceViewModel...")
        controller = InferenceControllerFactory.create(
            application.applicationContext
        )
        Log.i(TAG, "InferenceController created (model=${controller.uiState.value.modelName})")
        deviceManager = AudioDeviceManager(application.applicationContext)
        Log.i(TAG, "AudioDeviceManager created")

        val models = controller.getAvailableModels()
        val initialModel = models.firstOrNull {
            it.modelName == controller.uiState.value.modelName &&
            it.modelVersion == controller.uiState.value.modelVersion
        }
        defaultModelOption = InferenceModelOption(
            id = initialModel?.assetId ?: "unknown",
            label = "${controller.uiState.value.modelName} v${controller.uiState.value.modelVersion}"
        )
        _uiState = MutableStateFlow(controller.uiState.value)
        uiState = _uiState.asStateFlow()

        _availableModelOptions = MutableStateFlow(models.map {
            InferenceModelOption(
                id = it.assetId,
                label = "${it.modelName} v${it.modelVersion}"
            )
        })
        availableModelOptions = _availableModelOptions.asStateFlow()

        _selectedModelOptionId = MutableStateFlow(defaultModelOption.id)
        selectedModelOptionId = _selectedModelOptionId.asStateFlow()

        viewModelScope.launch {
            controller.uiState.collectLatest { state ->
                _uiState.value = state
            }
        }
        Log.i(TAG, "InferenceViewModel initialized successfully")
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

    fun setHardwareAccelerationEnabled(enabled: Boolean) {
        val preference = if (enabled) {
            InferenceBackendPreference.HARDWARE_ACCELERATION
        } else {
            InferenceBackendPreference.CPU
        }
        viewModelScope.launch {
            controller.setBackendPreference(preference, getApplication())
        }
    }

    fun setPassthroughEnabled(enabled: Boolean) {
        _passthroughEnabled.value = enabled
        controller.setPassthroughEnabled(enabled)
    }

    fun selectModelOption(optionId: String) {
        if (_selectedModelOptionId.value == optionId) return

        val models = controller.getAvailableModels()
        val targetModel = models.find { it.assetId == optionId }

        if (targetModel != null) {
            _selectedModelOptionId.value = optionId
            viewModelScope.launch {
                controller.switchModel(targetModel, getApplication())
            }
        }
    }

    /** Manually re-enumerate audio devices (e.g. after plugging in a USB adapter). */
    fun refreshAudioDevices() {
        deviceManager.refresh()
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

    companion object {
        private const val TAG = "InferenceViewModel"
    }
}
