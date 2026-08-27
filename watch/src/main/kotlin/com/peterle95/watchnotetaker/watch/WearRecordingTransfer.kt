package com.peterle95.watchnotetaker.watch

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.CapabilityClient
import com.peterle95.watchnotetaker.transfer.RecordingTransferMetadata
import com.peterle95.watchnotetaker.transfer.WearDataProtocol
import java.util.concurrent.Executors

class WearRecordingTransfer(
    context: Context,
    private val audioQueue: WatchAudioQueue,
    private val onStatus: (String) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val runner = RecordingTransferRunner(
        queuedRecordings = audioQueue::entries,
        transport = WearRecordingTransport(applicationContext, audioQueue),
        onState = { state -> mainHandler.post { onStatus(state.message) } },
    )

    fun sendQueuedRecordings() {
        runner.schedule(executor)
    }

    private val TransferState.message: String
        get() = when (this) {
            TransferState.Idle -> "No recordings queued"
            TransferState.Connecting -> "Connecting to phone..."
            TransferState.Sending -> "Sending recordings..."
            TransferState.WaitingForAcknowledgement -> "Sent recordings; waiting for phone confirmation"
            TransferState.Delivered -> "Phone received recording"
            TransferState.Disconnected -> "Phone is not connected. Recordings stay queued."
            TransferState.Failed -> "Could not reach phone. Recordings stay queued."
        }

    private companion object {
        val executor = Executors.newSingleThreadExecutor()
    }
}

internal class WearRecordingTransport(
    private val context: Context,
    private val audioQueue: WatchAudioQueue,
) : RecordingTransport {
    override fun connectedNodes(): List<TransferNode> =
        Tasks.await(
            Wearable.getCapabilityClient(context).getCapability(
                WearDataProtocol.PHONE_CAPABILITY,
                CapabilityClient.FILTER_REACHABLE,
            ),
        ).nodes.map { TransferNode(it.id, it.isNearby) }

    override fun send(node: TransferNode, recording: QueuedWatchAudio) {
        val client = Wearable.getChannelClient(context)
        audioQueue.markSent(recording.noteId, node.id)
        val channel = Tasks.await(client.openChannel(node.id, WearDataProtocol.RECORDING_CHANNEL_PATH))
        try {
            Tasks.await(client.getOutputStream(channel)).use { output ->
                WearDataProtocol.writeMetadata(
                    output,
                    RecordingTransferMetadata(recording.noteId, recording.durationSeconds, recording.file.length()),
                )
                recording.file.inputStream().use { input -> input.copyTo(output) }
            }
        } finally {
            runCatching { Tasks.await(client.close(channel)) }
        }
    }
}
