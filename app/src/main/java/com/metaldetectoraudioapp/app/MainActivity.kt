package com.metaldetectoraudioapp.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.metaldetectoraudioapp.app.ui.MetalDetectorAudioRoot
import com.metaldetectoraudioapp.app.ui.theme.MetalDetectorAudioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val hasMicPermission = remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                hasMicPermission.value = granted
            }

            MetalDetectorAudioTheme {
                MetalDetectorAudioRoot(
                    hasMicrophonePermission = hasMicPermission.value,
                    onRequestMicrophonePermission = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )
            }
        }
    }
}
