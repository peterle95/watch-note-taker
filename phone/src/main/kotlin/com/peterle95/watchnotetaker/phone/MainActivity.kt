package com.peterle95.watchnotetaker.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.work.WorkManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private lateinit var vaultDelivery: VaultDelivery
    private var onVaultSelection: ((Result<Unit>) -> Unit)? = null
    private val vaultFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onVaultSelection?.invoke(runCatching { vaultDelivery.select(uri) })
    }
    private var recordings by mutableStateOf(emptyList<ReceivedRecording>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = PhoneAudioStore(this)
        val deviceTokenStore = DeviceTokenStore(this)
        vaultDelivery = VaultDelivery(this)
        recordings = store.recordings()
        TranscriptionWork.enqueue(this)
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("phone-transcription").observe(this) {
            recordings = store.recordings()
        }
        setContent {
            var recordingForTranscript by remember { mutableStateOf<ReceivedRecording?>(null) }
            var transcript by remember { mutableStateOf("") }
            var reviewDecision by remember { mutableStateOf<Pair<ReceivedRecording, Boolean>?>(null) }
            var message by remember { mutableStateOf<String?>(null) }
            var vaultSelected by remember { mutableStateOf(vaultDelivery.isSelected()) }
            var deviceToken by remember { mutableStateOf("") }
            var deviceTokenConfigured by remember { mutableStateOf(deviceTokenStore.get().isNotBlank()) }
            fun deliverApproved() {
                var failed = false
                recordings.filter { it.status == PhoneNoteStatus.APPROVED || it.status == PhoneNoteStatus.DELIVERY_FAILED }
                    .forEach { recording ->
                        runCatching { vaultDelivery.deliver(recording) }
                            .onSuccess { store.markDelivery(recording.noteId, delivered = true) }
                            .onFailure {
                                store.markDelivery(recording.noteId, delivered = false)
                                failed = true
                            }
                    }
                recordings = store.recordings()
                message = if (failed) "Vault unavailable. Pick the folder again or retry delivery." else "Approved notes delivered."
            }
            onVaultSelection = { result ->
                result.onSuccess {
                    vaultSelected = vaultDelivery.isSelected()
                    message = "Vault folder connected."
                    deliverApproved()
                }.onFailure {
                    message = "Could not connect to that vault folder."
                }
            }
            Column {
                Text("Watch Note Taker")
                Text("${recordings.count { it.transcript == null }} recordings waiting for transcription")
                Text(if (BuildConfig.BACKEND_URL.isBlank()) "Backend URL is not configured" else "Backend configured")
                Text(if (deviceTokenConfigured) "Device token configured" else "Device token required")
                OutlinedTextField(
                    value = deviceToken,
                    onValueChange = { deviceToken = it },
                    label = { Text("Device token") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                Button(onClick = {
                    runCatching { deviceTokenStore.set(deviceToken) }
                        .onSuccess {
                            deviceToken = ""
                            deviceTokenConfigured = true
                            recordings.forEach { recording ->
                                if (recording.transcript == null) {
                                    store.retryTranscription(recording.noteId, System.currentTimeMillis())
                                }
                            }
                            TranscriptionWork.enqueue(this@MainActivity)
                        }
                        .onFailure { message = "Could not save device token." }
                }) { Text("Save device token") }
                message?.let { Text(it) }
                Button(onClick = { vaultFolderPicker.launch(null) }) {
                    Text(if (vaultSelected) "Change vault folder" else "Select vault folder")
                }
                if (vaultSelected) Button(onClick = ::deliverApproved) { Text("Retry Markdown delivery") }
                recordings.forEach { recording ->
                    Text("${recording.noteId.take(8)} (${recording.durationSeconds}s)")
                    recording.lastTranscriptionError?.let { error ->
                        Text("Transcription failed: $error")
                        Button(onClick = {
                            store.retryTranscription(recording.noteId, System.currentTimeMillis())
                            recordings = store.recordings()
                            TranscriptionWork.enqueue(this@MainActivity)
                        }) { Text("Retry transcription") }
                    }
                    if (recording.transcript == null && BuildConfig.MANUAL_TRANSCRIPTION_ENABLED) {
                        Button(onClick = {
                            recordingForTranscript = recording
                            transcript = ""
                        }) { Text("Enter transcript") }
                    } else {
                        Text(recording.transcript.orEmpty())
                        when (recording.status) {
                            PhoneNoteStatus.READY_FOR_REVIEW -> {
                                Button(onClick = {
                                    reviewDecision = recording to true
                                }) { Text("Approve") }
                                Button(onClick = {
                                    reviewDecision = recording to false
                                }) { Text("Reject") }
                            }
                            PhoneNoteStatus.APPROVED -> Text("Approved for Markdown delivery")
                            PhoneNoteStatus.REJECTED -> Text("Rejected")
                            PhoneNoteStatus.DELIVERED -> Text("Delivered")
                            PhoneNoteStatus.DELIVERY_FAILED -> Text("Markdown delivery failed")
                            PhoneNoteStatus.TRANSCRIBING -> Text("Transcribing")
                        }
                    }
                }
                reviewDecision?.let { (recording, approved) ->
                    Text("${if (approved) "Approve" else "Reject"} this transcript?")
                    Button(onClick = {
                        store.decide(recording.noteId, approved)
                        recordings = store.recordings()
                        reviewDecision = null
                        if (approved && vaultSelected) deliverApproved()
                    }) { Text("Confirm") }
                    Button(onClick = { reviewDecision = null }) { Text("Cancel") }
                }
                recordingForTranscript?.let { recording ->
                    OutlinedTextField(
                        value = transcript,
                        onValueChange = { transcript = it },
                        label = { Text("Developer-only transcript") },
                    )
                    Button(onClick = {
                        runCatching {
                            store.retryTranscription(recording.noteId, System.currentTimeMillis())
                            TranscriptionRepository(store, ManualTranscriptionClient(transcript)).run(recording.noteId)
                        }
                            .onSuccess {
                                recordings = store.recordings()
                                recordingForTranscript = null
                            }
                            .onFailure { message = "Could not save transcript." }
                    }) { Text("Save transcript") }
                }
            }
        }
    }
}
