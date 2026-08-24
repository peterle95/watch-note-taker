package com.peterle95.watchnotetaker.notes

import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptReviewControlsTest {
    @Test
    fun `approval requires confirmation before queuing a durable disconnected command`() {
        val transcript = ReviewableTranscript(
            id = "note-123",
            transcript = "Buy coffee beans and oatmeal.",
            status = NoteStatus.READY_FOR_REVIEW,
        )
        val outbox = ReviewDecisionOutbox(InMemoryReviewDecisionStore(), DisconnectedTransport)
        val controls = TranscriptReviewControls(outbox)

        controls.open(transcript)
        controls.requestApproval()

        assertEquals(
            TranscriptReviewControlState.ConfirmationRequired(transcript, ReviewDecision.Approve),
            controls.snapshot(),
        )
        assertEquals(emptyList(), outbox.pendingDecisions())

        controls.confirm()

        assertEquals(
            TranscriptReviewControlState.PendingSynchronization(
                transcript,
                ReviewDecision.Approve,
                ReviewDecisionSynchronizationState.Disconnected,
            ),
            controls.snapshot(),
        )
        assertEquals(
            listOf(PendingReviewDecision(transcript.id, ReviewDecision.Approve, transcript.transcript)),
            outbox.pendingDecisions(),
        )
    }

    @Test
    fun `rejection confirmation reports the authoritative result after synchronization`() {
        val transcript = ReviewableTranscript(
            id = "note-123",
            transcript = "Buy coffee beans and oatmeal.",
            status = NoteStatus.READY_FOR_REVIEW,
        )
        val status = AuthoritativeReviewStatus(transcript.id, transcript.transcript, NoteStatus.REJECTED)
        val controls = TranscriptReviewControls(
            ReviewDecisionOutbox(InMemoryReviewDecisionStore(), DeliveredTransport(status)),
        )

        controls.open(transcript)
        controls.requestRejection()
        controls.confirm()

        assertEquals(TranscriptReviewControlState.DecisionAccepted(status), controls.snapshot())
    }

    @Test
    fun `a conflicting Fold result is reported without inviting a retry`() {
        val transcript = ReviewableTranscript(
            id = "note-123",
            transcript = "Buy coffee beans and oatmeal.",
            status = NoteStatus.READY_FOR_REVIEW,
        )
        val conflictingStatus = AuthoritativeReviewStatus(transcript.id, transcript.transcript, NoteStatus.REJECTED)
        val controls = TranscriptReviewControls(
            ReviewDecisionOutbox(InMemoryReviewDecisionStore(), DeliveredTransport(conflictingStatus)),
        )

        controls.open(transcript)
        controls.requestApproval()
        controls.confirm()

        assertEquals(
            TranscriptReviewControlState.ConflictingAuthoritativeStatus(
                transcript,
                ReviewDecision.Approve,
                conflictingStatus,
            ),
            controls.snapshot(),
        )
    }

    @Test
    fun `cancelling confirmation keeps the review open without changing note state`() {
        val transcript = ReviewableTranscript(
            id = "note-123",
            transcript = "Buy coffee beans and oatmeal.",
            status = NoteStatus.READY_FOR_REVIEW,
        )
        val outbox = ReviewDecisionOutbox(InMemoryReviewDecisionStore(), DisconnectedTransport)
        val controls = TranscriptReviewControls(outbox)

        controls.open(transcript)
        controls.requestRejection()
        controls.cancelConfirmation()

        assertEquals(TranscriptReviewControlState.Reviewing(transcript), controls.snapshot())
        assertEquals(emptyList(), outbox.pendingDecisions())
    }

    private object DisconnectedTransport : ReviewDecisionTransport {
        override fun send(decision: PendingReviewDecision): ReviewDecisionDelivery = ReviewDecisionDelivery.Disconnected
    }

    private class DeliveredTransport(
        private val status: AuthoritativeReviewStatus,
    ) : ReviewDecisionTransport {
        override fun send(decision: PendingReviewDecision): ReviewDecisionDelivery = ReviewDecisionDelivery.Delivered(status)
    }
}
