package com.peterle95.watchnotetaker.watch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.material.Text
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.ScalingLazyColumn

class MainActivity : ComponentActivity() {
    private var recording by mutableStateOf(false)
    private var status by mutableStateOf("Ready to record")
    private lateinit var recordingController: RecordingController
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else status = "Microphone permission is required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordingController = RecordingController(
            context = this,
            onFinished = { _, duration ->
                recording = false
                status = "Recorded ${duration}s"
            },
            onFailure = { message ->
                recording = false
                status = message
            },
        )
        setContent {
            MaterialTheme {
                ScalingLazyColumn {
                    item { Text("Watch Note Taker") }
                    item {
                        Button(onClick = { if (recording) recordingController.stop() else requestRecording() }) {
                            Text(if (recording) "Stop" else "Record")
                        }
                    }
                    item { Text(status) }
                }
            }
        }
    }

    override fun onDestroy() {
        recordingController.release()
        super.onDestroy()
    }

    private fun requestRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        runCatching { recordingController.start() }
            .onSuccess {
                recording = true
                status = "Recording (stops after 120s)"
            }
            .onFailure { status = "Recording failed. Try again." }
    }
}
