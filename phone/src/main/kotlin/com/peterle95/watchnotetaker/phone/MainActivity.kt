package com.peterle95.watchnotetaker.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = PhoneAudioStore(this)
        setContent {
            var recordings by remember { mutableStateOf(store.recordings()) }
            var recordingForTranscript by remember { mutableStateOf<ReceivedRecording?>(null) }
            var transcript by remember { mutableStateOf("") }
            var reviewDecision by remember { mutableStateOf<Pair<ReceivedRecording, Boolean>?>(null) }
            var message by remember { mutableStateOf<String?>(null) }
            Column {
                Text("Watch Note Taker")
                Text("${recordings.count { it.transcript == null }} recordings waiting for transcription")
                message?.let { Text(it) }
                recordings.forEach { recording ->
                    Text("${recording.noteId.take(8)} (${recording.durationSeconds}s)")
                    if (recording.transcript == null) {
                        Button(onClick = {
                            recordingForTranscript = recording
                            transcript = ""
                        }) { Text("Enter transcript") }
                    } else {
                        Text(recording.transcript)
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
                    }) { Text("Confirm") }
                    Button(onClick = { reviewDecision = null }) { Text("Cancel") }
                }
                recordingForTranscript?.let { recording ->
                    OutlinedTextField(
                        value = transcript,
                        onValueChange = { transcript = it },
                        label = { Text("Developer transcript") },
                    )
                    Button(onClick = {
                        runCatching { store.saveTranscript(recording.noteId, transcript) }
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
