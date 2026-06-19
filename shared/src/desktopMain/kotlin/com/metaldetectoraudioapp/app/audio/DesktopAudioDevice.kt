package com.metaldetectoraudioapp.app.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

data class DesktopAudioDevice(
    val mixerInfo: Mixer.Info,
    val displayName: String,
)

object DesktopAudioDeviceManager {
    private val recordingFormat = AudioFormat(48_000f, 16, 1, true, false)
    private val playbackFormat = AudioFormat(48_000f, 16, 1, true, false)

    fun listRecordingDevices(): List<DesktopAudioDevice> =
        AudioSystem.getMixerInfo()
            .filter { mixerSupportsLine(it, DataLine.Info(TargetDataLine::class.java, recordingFormat)) }
            .map(::toDesktopAudioDevice)

    fun listPlaybackDevices(): List<DesktopAudioDevice> =
        AudioSystem.getMixerInfo()
            .filter {
                mixerSupportsLine(it, DataLine.Info(Clip::class.java, playbackFormat)) ||
                    mixerSupportsLine(it, DataLine.Info(SourceDataLine::class.java, playbackFormat))
            }
            .map(::toDesktopAudioDevice)

    private fun mixerSupportsLine(mixerInfo: Mixer.Info, lineInfo: DataLine.Info): Boolean =
        runCatching { AudioSystem.getMixer(mixerInfo).isLineSupported(lineInfo) }.getOrDefault(false)

    private fun toDesktopAudioDevice(mixerInfo: Mixer.Info): DesktopAudioDevice {
        val description = mixerInfo.description.takeIf { it.isNotBlank() && it != "No details available" }
        val vendor = mixerInfo.vendor.takeIf { it.isNotBlank() && it != "Unknown Vendor" }
        val suffix = listOfNotNull(description, vendor).distinct().joinToString(" - ")
        val displayName = if (suffix.isBlank()) mixerInfo.name else "${mixerInfo.name} ($suffix)"
        return DesktopAudioDevice(mixerInfo = mixerInfo, displayName = displayName)
    }
}
