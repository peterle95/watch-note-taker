package com.peterle95.watchnotetaker.watch

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.Executors

class WearRecordingTransfer(
    context: Context,
    private val audioQueue: WatchAudioQueue,
    private val onStatus: (String) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    fun sendQueuedRecordings() {
        executor.execute {
            runCatching {
                val nodes = Tasks.await(Wearable.getNodeClient(applicationContext).connectedNodes)
                check(nodes.isNotEmpty()) { "Phone is not connected" }
                val recordings = audioQueue.entries()
                recordings.forEach { recording ->
                    nodes.forEach { node ->
                        val client = Wearable.getChannelClient(applicationContext)
                        val channel = Tasks.await(
                            client.openChannel(node.id, "/recordings/${recording.noteId}/${recording.durationSeconds}"),
                        )
                        Tasks.await(client.getOutputStream(channel)).use { output ->
                            recording.file.inputStream().use { input -> input.copyTo(output) }
                        }
                        Tasks.await(client.close(channel))
                    }
                }
                "Sent ${recordings.size} recording(s); waiting for phone confirmation"
            }.getOrElse { "Could not reach phone. Recordings stay queued." }
                .also { message -> mainHandler.post { onStatus(message) } }
        }
    }
}
