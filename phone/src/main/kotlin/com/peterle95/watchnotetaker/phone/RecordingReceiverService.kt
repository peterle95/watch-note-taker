package com.peterle95.watchnotetaker.phone

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.Executors

class RecordingReceiverService : WearableListenerService() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        executor.execute {
            try {
                val recording = channel.recording() ?: return@execute
                val received = runCatching {
                    Tasks.await(Wearable.getChannelClient(this).getInputStream(channel)).use { input ->
                        PhoneAudioStore(this).receive(recording.noteId, recording.durationSeconds, input)
                    }
                }.isSuccess
                if (received) {
                    Tasks.await(
                        Wearable.getMessageClient(this).sendMessage(
                            channel.nodeId,
                            "/recordings/ack/${recording.noteId}",
                            ByteArray(0),
                        ),
                    )
                }
            } finally {
                runCatching { Tasks.await(Wearable.getChannelClient(this).close(channel)) }
            }
        }
    }

    private fun ChannelClient.Channel.recording(): IncomingRecording? {
        val parts = path.split('/')
        if (parts.size != 4 || parts[1] != "recordings") return null
        val durationSeconds = parts[3].toIntOrNull() ?: return null
        return IncomingRecording(parts[2], durationSeconds)
    }

    private data class IncomingRecording(
        val noteId: String,
        val durationSeconds: Int,
    )
}
