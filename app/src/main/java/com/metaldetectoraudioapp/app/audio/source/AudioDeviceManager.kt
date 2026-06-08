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
        refresh()
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
    }

    /**
     * Re-enumerates input and output devices. The [deviceCallback] calls this on hot-plug, but it is
     * also exposed publicly so the UI can offer a manual "refresh sources" button — the explicit,
     * user-trusted path for the field, where a device may be plugged in after the app is already open.
     */
    fun refresh() = refreshDevices()

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
        private fun isUsb(device: AudioDeviceInfo): Boolean =
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

        /**
         * True when Android exposes a USB audio output but no corresponding USB input. The app
         * cannot reinterpret an output endpoint as an input, even when the attached analog device
         * is physically capable of sending audio.
         */
        fun usbOutputWithoutInput(
            inputs: List<AudioDeviceInfo>,
            outputs: List<AudioDeviceInfo>
        ): Boolean = outputs.any { isUsb(it) } && inputs.none { isUsb(it) }

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
