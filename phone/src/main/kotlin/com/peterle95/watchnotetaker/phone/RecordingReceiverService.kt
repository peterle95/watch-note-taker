package com.peterle95.watchnotetaker.phone

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.peterle95.watchnotetaker.transfer.WearDataProtocol
import java.util.concurrent.Executors

class RecordingReceiverService : WearableListenerService() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        executor.execute {
            try {
                if (channel.path != WearDataProtocol.RECORDING_CHANNEL_PATH) return@execute
                val noteId = runCatching {
                    Tasks.await(Wearable.getChannelClient(this).getInputStream(channel)).use { input ->
                        val metadata = WearDataProtocol.readMetadata(input) ?: error("Invalid recording metadata")
                        PhoneAudioStore(this).receive(
                            metadata.noteId,
                            metadata.durationSeconds,
                            input,
                            metadata.audioBytes,
                        )
                        metadata.noteId
                    }
                }.getOrNull()
                if (noteId != null) {
                    TranscriptionWork.enqueue(this).result.get()
                    Tasks.await(
                        Wearable.getMessageClient(this).sendMessage(
                            channel.nodeId,
                            WearDataProtocol.ACKNOWLEDGEMENT_MESSAGE_PATH,
                            WearDataProtocol.encodeAcknowledgement(noteId),
                        ),
                    )
                }
            } finally {
                runCatching { Tasks.await(Wearable.getChannelClient(this).close(channel)) }
            }
        }
    }
}
