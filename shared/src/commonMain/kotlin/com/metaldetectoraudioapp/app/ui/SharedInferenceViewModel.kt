package com.metaldetectoraudioapp.app.ui

import com.metaldetectoraudioapp.app.audio.ribbon.RibbonAnalyzer
import com.metaldetectoraudioapp.app.inference.InferenceController
import com.metaldetectoraudioapp.app.inference.InferenceModelOption
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
    private val availableModels = controller.getAvailableModels()
    private val initialModel = availableModels.firstOrNull {
        it.modelName == controller.uiState.value.modelName &&
            it.modelVersion == controller.uiState.value.modelVersion
    } ?: availableModels.first()
    private val defaultModelOption = InferenceModelOption(
        id = initialModel.assetId,
        label = initialModel.modelVariantDisplayName,
    )

    private val _uiState = MutableStateFlow(controller.uiState.value)
    val uiState: StateFlow<InferenceUiState> = _uiState.asStateFlow()

    /** Tone-quality ribbon visual state, read directly by the ribbon renderer. */
    val ribbon: RibbonAnalyzer = controller.ribbon

    private val _passthroughEnabled = MutableStateFlow(false)
    val passthroughEnabled: StateFlow<Boolean> = _passthroughEnabled.asStateFlow()

    private val _availableModelOptions = MutableStateFlow(
        availableModels.map { InferenceModelOption(it.assetId, it.modelVariantDisplayName) }
    )
    val availableModelOptions: StateFlow<List<InferenceModelOption>> = _availableModelOptions.asStateFlow()

    private val _selectedModelOptionId = MutableStateFlow(defaultModelOption.id)
    val selectedModelOptionId: StateFlow<String> = _selectedModelOptionId.asStateFlow()

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

    fun selectModelOption(optionId: String) {
        if (_selectedModelOptionId.value == optionId) return
        val model = availableModels.firstOrNull { it.assetId == optionId } ?: return
        controller.switchModel(model)
        _selectedModelOptionId.value = optionId
    }

    fun close() {
        controller.release()
        scope.cancel()
    }
}
