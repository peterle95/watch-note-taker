package com.peterle95.watchnotetaker.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val receivedRecordings = PhoneAudioStore(this).recordings().size
        setContent {
            Text(
                "Watch Note Taker\n$receivedRecordings recordings waiting for transcription.\n" +
                    "Select an Obsidian vault folder to begin.",
            )
        }
    }
}
