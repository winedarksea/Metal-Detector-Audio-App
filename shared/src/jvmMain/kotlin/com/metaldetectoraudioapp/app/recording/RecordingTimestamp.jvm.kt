package com.metaldetectoraudioapp.app.recording

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

actual fun formatRecordingTimestamp(epochMs: Long): String {
    val dateTime = Instant.fromEpochMilliseconds(epochMs)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    return buildString {
        append(dateTime.year.toString().padStart(4, '0'))
        append(dateTime.monthNumber.toString().padStart(2, '0'))
        append(dateTime.dayOfMonth.toString().padStart(2, '0'))
        append('_')
        append(dateTime.hour.toString().padStart(2, '0'))
        append(dateTime.minute.toString().padStart(2, '0'))
        append(dateTime.second.toString().padStart(2, '0'))
        append('_')
        append((dateTime.nanosecond / 1_000_000).toString().padStart(3, '0'))
    }
}
