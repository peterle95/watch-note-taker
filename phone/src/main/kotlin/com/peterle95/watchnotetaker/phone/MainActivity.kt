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
                        Text("Transcript ready")
                    }
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
