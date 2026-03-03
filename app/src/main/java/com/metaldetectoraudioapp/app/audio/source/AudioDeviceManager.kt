package com.metaldetectoraudioapp.app.audio.source

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioDeviceManager(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _inputDevices = MutableStateFlow<List<AudioDeviceInfo>>(emptyList())
    val inputDevices: StateFlow<List<AudioDeviceInfo>> = _inputDevices.asStateFlow()

    private val _outputDevices = MutableStateFlow<List<AudioDeviceInfo>>(emptyList())
    val outputDevices: StateFlow<List<AudioDeviceInfo>> = _outputDevices.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
        }
    }

    init {
        refreshDevices()
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
    }

    private fun refreshDevices() {
        _inputDevices.value = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource }
            .toList()
        _outputDevices.value = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }
            .toList()
    }

    fun release() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    companion object {
        fun deviceDisplayName(device: AudioDeviceInfo): String {
            val productName = device.productName?.toString()?.takeIf { it.isNotBlank() }
            val typeName = when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Headset"
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE Speaker"
                else -> "Audio Device"
            }
            return if (productName != null && productName != typeName) {
                "$typeName: $productName"
            } else {
                typeName
            }
        }
    }
}
