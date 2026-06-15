package com.metaldetectoraudioapp.web.platform

interface WebLocationProvider {
    fun captureCurrentLocation(onResult: (WebLocationResult) -> Unit)
}

sealed interface WebLocationResult {
    data class Captured(
        val latitude: Double,
        val longitude: Double,
    ) : WebLocationResult

    data object PermissionDenied : WebLocationResult
    data object Timeout : WebLocationResult
    data object Unavailable : WebLocationResult
    data object Unsupported : WebLocationResult
    data class Failed(val message: String) : WebLocationResult
}

class BrowserWebLocationProvider : WebLocationProvider {
    override fun captureCurrentLocation(onResult: (WebLocationResult) -> Unit) {
        requestCurrentLocation { status, latitude, longitude, message ->
            val result = when (status) {
                LOCATION_STATUS_CAPTURED -> WebLocationResult.Captured(latitude, longitude)
                LOCATION_STATUS_PERMISSION_DENIED -> WebLocationResult.PermissionDenied
                LOCATION_STATUS_TIMEOUT -> WebLocationResult.Timeout
                LOCATION_STATUS_UNAVAILABLE -> WebLocationResult.Unavailable
                LOCATION_STATUS_UNSUPPORTED -> WebLocationResult.Unsupported
                else -> WebLocationResult.Failed(message.ifBlank { "Unable to capture location" })
            }
            onResult(result)
        }
    }
}

private const val LOCATION_STATUS_CAPTURED = 1
private const val LOCATION_STATUS_PERMISSION_DENIED = 2
private const val LOCATION_STATUS_TIMEOUT = 3
private const val LOCATION_STATUS_UNAVAILABLE = 4
private const val LOCATION_STATUS_UNSUPPORTED = 5

private fun requestCurrentLocation(
    onCompleted: (Int, Double, Double, String) -> Unit,
) {
    js("""
        if (!navigator.geolocation) {
            onCompleted(5, 0, 0, 'Geolocation is not supported by this browser');
        } else {
            navigator.geolocation.getCurrentPosition(
                function(position) {
                    onCompleted(1, position.coords.latitude, position.coords.longitude, '');
                },
                function(error) {
                    var status = error.code === 1 ? 2 : (error.code === 3 ? 3 : 4);
                    onCompleted(status, 0, 0, error.message || '');
                },
                {
                    enableHighAccuracy: true,
                    maximumAge: 0,
                    timeout: 15000
                }
            );
        }
    """)
}
