package com.peterle95.watchnotetaker.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.peterle95.watchnotetaker.notes.NoteStatus
import java.io.ByteArrayInputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneAppInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun phoneUiExposesBackendVaultAndReviewWorkflow() {
        compose.onNodeWithText("Watch Note Taker").assertIsDisplayed()
        compose.onNodeWithText("Device token", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Choose vault folder").assertIsDisplayed()
    }

    @Test
    fun receivedRecordingAndTranscriptSurviveStoreRecreation() {
        val id = UUID.randomUUID().toString()
        val context = compose.activity
        PhoneAudioStore(context).receive(id, 10, ByteArrayInputStream("audio".encodeToByteArray()))
        PhoneAudioStore(context).markReadyForReview(id, "Transcript")

        val restored = PhoneAudioStore(context).recordings().first { it.noteId == id }
        assertEquals("Transcript", restored.transcript)
        assertEquals(NoteStatus.READY_FOR_REVIEW, restored.status)
    }
}
