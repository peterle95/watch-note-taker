package com.peterle95.watchnotetaker.watch

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

enum class TransferState {
    Idle,
    Connecting,
    Sending,
    WaitingForAcknowledgement,
    Delivered,
    Disconnected,
    Failed,
}

object TransferStatus {
    @Volatile
    var state: TransferState = TransferState.Idle
        private set

    fun update(state: TransferState) {
        this.state = state
    }
}

data class TransferNode(val id: String, val isNearby: Boolean)

interface RecordingTransport {
    fun connectedNodes(): List<TransferNode>
    fun send(node: TransferNode, recording: QueuedWatchAudio)
}

fun selectPhoneNode(nodes: List<TransferNode>): TransferNode? =
    nodes.sortedWith(compareByDescending<TransferNode> { it.isNearby }.thenBy { it.id }).firstOrNull()

class RecordingTransferRunner(
    private val queuedRecordings: () -> List<QueuedWatchAudio>,
    private val transport: RecordingTransport,
    private val onState: (TransferState) -> Unit = {},
) {
    fun run(): Boolean {
        if (!transferInProgress.compareAndSet(false, true)) {
            rerunRequested.set(true)
            return false
        }
        drainRuns()
        return true
    }

    fun schedule(executor: Executor): Boolean {
        if (!transferInProgress.compareAndSet(false, true)) {
            rerunRequested.set(true)
            return false
        }
        try {
            executor.execute {
                drainRuns()
            }
        } catch (error: Exception) {
            transferInProgress.set(false)
            throw error
        }
        return true
    }

    private fun drainRuns() {
        while (true) {
            rerunRequested.set(false)
            perform()
            transferInProgress.set(false)
            if (!rerunRequested.getAndSet(false) || !transferInProgress.compareAndSet(false, true)) return
        }
    }

    private fun perform() {
        try {
            emit(TransferState.Connecting)
            val node = selectPhoneNode(transport.connectedNodes())
            if (node == null) {
                emit(TransferState.Disconnected)
                return
            }
            val recordings = queuedRecordings()
            if (recordings.isEmpty()) {
                emit(TransferState.Idle)
                return
            }
            recordings.forEach { recording ->
                emit(TransferState.Sending)
                transport.send(node, recording)
            }
            emit(TransferState.WaitingForAcknowledgement)
        } catch (_: Exception) {
            emit(TransferState.Failed)
        }
    }

    private fun emit(state: TransferState) {
        TransferStatus.update(state)
        onState(state)
    }

    private companion object {
        val transferInProgress = AtomicBoolean()
        val rerunRequested = AtomicBoolean()
    }
}
