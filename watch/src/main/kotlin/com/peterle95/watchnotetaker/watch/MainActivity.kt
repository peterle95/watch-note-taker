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
import androidx.work.WorkManager

class MainActivity : ComponentActivity() {
    private var recording by mutableStateOf(false)
    private var status by mutableStateOf("Ready to record")
    private var queuedRecordings by mutableStateOf(0)
    private lateinit var recordingController: RecordingController
    private lateinit var audioQueue: WatchAudioQueue
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else status = "Microphone permission is required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioQueue = WatchAudioQueue(this)
        queuedRecordings = audioQueue.entries().size
        if (queuedRecordings > 0) WatchTransferWork.enqueue(this)
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(WatchTransferWork.UNIQUE_NAME).observe(this) {
            queuedRecordings = audioQueue.entries().size
            status = when (TransferStatus.state) {
                TransferState.Idle -> "Ready to record"
                TransferState.Connecting -> "Connecting to phone..."
                TransferState.Sending -> "Sending recordings..."
                TransferState.WaitingForAcknowledgement -> "Waiting for phone confirmation"
                TransferState.Delivered -> "Phone received recording"
                TransferState.Disconnected -> "Phone disconnected. Recordings stay queued."
                TransferState.Failed -> "Transfer failed. Recordings stay queued."
            }
        }
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(WatchTransferWork.REFRESH_NAME).observe(this) {
            queuedRecordings = audioQueue.entries().size
            if (TransferStatus.state == TransferState.Delivered) status = "Phone received recording"
        }
        recordingController = RecordingController(
            context = this,
            onFinished = { recordingFile, duration ->
                recording = false
                runCatching { audioQueue.enqueue(recordingFile, duration) }
                    .onSuccess { queued ->
                        if (queued) {
                            queuedRecordings = audioQueue.entries().size
                            status = "Queued ${duration}s recording"
                            WatchTransferWork.enqueue(this@MainActivity)
                        } else {
                            recordingFile.delete()
                            status = "Queue full. Send recordings first."
                        }
                    }
                    .onFailure {
                        status = "Could not queue recording. Audio remains on watch."
                    }
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
                    item { Text("$queuedRecordings recordings queued") }
                    item {
                        Button(onClick = ::sendQueuedRecordings) { Text("Send queued") }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        recordingController.release()
        super.onDestroy()
    }

    private fun requestRecording() {
        if (audioQueue.entries().size >= WatchAudioQueue.CAPACITY) {
            status = "Queue full. Send recordings first."
            return
        }
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

    private fun sendQueuedRecordings() {
        if (queuedRecordings == 0) {
            status = "No recordings queued"
        } else {
            status = "Sending recordings in background..."
            WatchTransferWork.enqueue(this, replace = true)
        }
    }
}
