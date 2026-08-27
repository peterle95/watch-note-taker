package com.peterle95.watchnotetaker.watch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchAppInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun watchUiExposesRecordAndQueueControls() {
        compose.onNodeWithText("Record").assertIsDisplayed()
        compose.onNodeWithText("Send queued").assertIsDisplayed()
    }

    @Test
    fun queuedRecordingSurvivesQueueRecreation() {
        val context = compose.activity
        val id = UUID.randomUUID().toString()
        val source = context.cacheDir.resolve("$id.m4a").apply { writeText("audio") }
        WatchAudioQueue(context).enqueue(source, 10)

        assertEquals(id, WatchAudioQueue(context).entries().first { it.noteId == id }.noteId)
    }
}
