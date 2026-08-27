package com.peterle95.watchnotetaker.watch

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class RecordingAcknowledgementService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        val noteId = messageEvent.path.removePrefix("/recordings/ack/")
        if (messageEvent.path == "/recordings/ack/$noteId") {
            WatchAudioQueue(this).remove(noteId)
        }
    }
}
