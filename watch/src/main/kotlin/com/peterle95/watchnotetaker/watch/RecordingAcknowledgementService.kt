package com.peterle95.watchnotetaker.watch

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.WearableListenerService
import com.peterle95.watchnotetaker.transfer.WearDataProtocol

class RecordingAcknowledgementService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WearDataProtocol.ACKNOWLEDGEMENT_MESSAGE_PATH) return
        val acknowledgement = WearDataProtocol.decodeAcknowledgement(messageEvent.data) ?: return
        if (WatchAudioQueue(this).acknowledge(acknowledgement.noteId, messageEvent.sourceNodeId)) {
            TransferStatus.update(TransferState.Delivered)
        }
    }

    override fun onPeerConnected(peer: Node) {
        val queue = WatchAudioQueue(this)
        if (queue.entries().isNotEmpty()) WatchTransferWork.enqueue(this, replace = true)
    }

    override fun onPeerDisconnected(peer: Node) {
        TransferStatus.update(TransferState.Disconnected)
    }
}
