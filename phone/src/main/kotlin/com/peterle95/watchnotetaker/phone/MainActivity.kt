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
import com.peterle95.watchnotetaker.notes.NoteStatus
import com.peterle95.watchnotetaker.notes.TransitionDisposition

class MainActivity : ComponentActivity() {
    private lateinit var vaultDelivery: VaultDelivery
    private var onVaultSelection: ((Result<Unit>) -> Unit)? = null
    private val vaultFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onVaultSelection?.invoke(runCatching { vaultDelivery.select(uri) })
    }
    private var recordings by mutableStateOf(emptyList<ReceivedRecording>())
    private var vaultState by mutableStateOf(VaultFolderState.NONE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = PhoneAudioStore(this)
        val deviceTokenStore = DeviceTokenStore(this)
        vaultDelivery = VaultDelivery(this)
        vaultState = vaultDelivery.folderState()
        recordings = store.recordings()
        TranscriptionWork.enqueue(this)
        DeliveryWork.enqueue(this)
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("phone-transcription").observe(this) {
            recordings = store.recordings()
        }
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(DeliveryWork.UNIQUE_NAME).observe(this) {
            recordings = store.recordings()
            vaultState = vaultDelivery.folderState()
        }
        setContent {
            var recordingForTranscript by remember { mutableStateOf<ReceivedRecording?>(null) }
            var transcript by remember { mutableStateOf("") }
            var reviewDecision by remember { mutableStateOf<Pair<ReceivedRecording, Boolean>?>(null) }
            var message by remember { mutableStateOf<String?>(null) }
            var deviceToken by remember { mutableStateOf("") }
            var deviceTokenConfigured by remember { mutableStateOf(deviceTokenStore.get().isNotBlank()) }
            onVaultSelection = { result ->
                result.onSuccess {
                    vaultState = vaultDelivery.folderState()
                    message = "Vault folder connected."
                    DeliveryWork.enqueue(this@MainActivity)
                }.onFailure {
                    vaultState = vaultDelivery.folderState()
                    message = "Could not connect to that vault folder."
                }
            }
            Column {
                Text("Watch Note Taker")
                Text("${recordings.count { it.transcript == null }} recordings waiting for transcription")
                Text(if (BuildConfig.BACKEND_URL.isBlank()) "Backend URL is not configured" else "Backend configured")
                Text(if (deviceTokenConfigured) "Device token configured" else "Device token required")
                Text("Vault: ${vaultState.label}")
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
                    Text(if (vaultState == VaultFolderState.CONNECTED) "Change vault folder" else "Choose vault folder")
                }
                if (recordings.any { it.status == NoteStatus.APPROVED || it.status == NoteStatus.DELIVERY_FAILED }) {
                    Button(onClick = {
                        DeliveryWork.enqueue(this@MainActivity, replace = true)
                        message = "Markdown delivery retry requested."
                    }) { Text("Retry Markdown delivery") }
                }
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
                            NoteStatus.READY_FOR_REVIEW -> {
                                Button(onClick = {
                                    reviewDecision = recording to true
                                }) { Text("Approve") }
                                Button(onClick = {
                                    reviewDecision = recording to false
                                }) { Text("Reject") }
                            }
                            NoteStatus.APPROVED -> Text("Approved for Markdown delivery")
                            NoteStatus.REJECTED -> Text("Rejected")
                            NoteStatus.DELIVERED -> Text("Delivered")
                            NoteStatus.DELIVERY_FAILED -> Text("Markdown delivery failed")
                            NoteStatus.TRANSCRIBING -> Text("Transcribing")
                        }
                    }
                }
                reviewDecision?.let { (recording, approved) ->
                    Text("${if (approved) "Approve" else "Reject"} this transcript?")
                    Button(onClick = {
                        val decision = if (approved) store.approve(recording.noteId) else store.reject(recording.noteId)
                        recordings = store.recordings()
                        reviewDecision = null
                        if (decision.disposition == TransitionDisposition.INVALID) {
                            message = "This note is no longer ready for review."
                        } else if (approved) {
                            DeliveryWork.enqueue(this@MainActivity)
                        }
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

private val VaultFolderState.label: String
    get() = when (this) {
        VaultFolderState.CONNECTED -> "Connected"
        VaultFolderState.PERMISSION_REVOKED -> "Permission revoked"
        VaultFolderState.UNAVAILABLE -> "Unavailable"
        VaultFolderState.NONE -> "None"
    }
