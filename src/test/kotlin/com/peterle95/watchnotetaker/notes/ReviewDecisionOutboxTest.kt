package com.peterle95.watchnotetaker.notes

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewDecisionOutboxTest {
    @Test
    fun `queues an approval while disconnected and restores it after restart`() {
        val store = InMemoryReviewDecisionStore()
        val firstWatchSession = ReviewDecisionOutbox(store, DisconnectedTransport)

        val queued = firstWatchSession.queue("note-123", ReviewDecision.Approve)
        val restartedWatchSession = ReviewDecisionOutbox(store, DisconnectedTransport)

        assertEquals(ReviewDecisionQueueResult.Queued, queued)
        assertEquals(
            listOf(PendingReviewDecision("note-123", ReviewDecision.Approve)),
            restartedWatchSession.pendingDecisions(),
        )
    }

    @Test
    fun `keeps transcript content available when delivery fails`() {
        val store = InMemoryReviewDecisionStore()
        val outbox = ReviewDecisionOutbox(store, RetryableFailureTransport)
        val transcript = ReviewableTranscript(
            id = "note-123",
            transcript = "Buy coffee beans and oatmeal.",
            status = NoteStatus.READY_FOR_REVIEW,
        )

        outbox.queue(transcript, ReviewDecision.Reject)
        outbox.synchronize()

        assertEquals(transcript, outbox.retainedTranscript("note-123"))
        assertEquals(
            listOf(PendingReviewDecision("note-123", ReviewDecision.Reject, transcript.transcript)),
            outbox.pendingDecisions(),
        )
    }

    @Test
    fun `file store preserves a queued rejection across a process restart`() {
        val storage = Files.createTempFile("review-decision-outbox", ".properties")
        Files.deleteIfExists(storage)
        val transcript = ReviewableTranscript(
            id = "note-123",
            transcript = "Buy coffee beans and oatmeal.",
            status = NoteStatus.READY_FOR_REVIEW,
        )
        ReviewDecisionOutbox(FileReviewDecisionStore(storage), DisconnectedTransport)
            .queue(transcript, ReviewDecision.Reject)

        val restartedOutbox = ReviewDecisionOutbox(FileReviewDecisionStore(storage), DisconnectedTransport)

        assertEquals(
            listOf(PendingReviewDecision("note-123", ReviewDecision.Reject, transcript.transcript)),
            restartedOutbox.pendingDecisions(),
        )
        assertEquals(transcript, restartedOutbox.retainedTranscript("note-123"))
    }

    @Test
    fun `restart reconnect and retry deliver a queued decision once`() {
        val storage = Files.createTempFile("review-decision-lifecycle", ".properties")
        Files.deleteIfExists(storage)
        val transcript = ReviewableTranscript("note-123", "Buy coffee beans and oatmeal.", NoteStatus.READY_FOR_REVIEW)
        val disconnectedSession = ReviewDecisionOutbox(FileReviewDecisionStore(storage), DisconnectedTransport)
        disconnectedSession.queue(transcript, ReviewDecision.Approve)
        disconnectedSession.synchronize()
        val reconnectingTransport = SequencedTransport(
            ReviewDecisionDelivery.RetryableFailure,
            ReviewDecisionDelivery.Delivered(
                AuthoritativeReviewStatus("note-123", transcript.transcript, NoteStatus.APPROVED),
            ),
        )
        val restartedSession = ReviewDecisionOutbox(FileReviewDecisionStore(storage), reconnectingTransport)

        restartedSession.synchronize()
        restartedSession.synchronize()

        assertEquals(emptyList(), restartedSession.pendingDecisions())
        assertEquals(NoteStatus.APPROVED, restartedSession.authoritativeStatus("note-123")?.status)
        assertEquals(2, reconnectingTransport.deliveries)
    }

    @Test
    fun `reconnection delivers a duplicated approval once and retains the authoritative status`() {
        val store = InMemoryReviewDecisionStore()
        val transport = RecordingDeliveryTransport(
            AuthoritativeReviewStatus(
                noteId = "note-123",
                transcript = "Buy coffee beans and oatmeal.",
                status = NoteStatus.APPROVED,
            ),
        )
        val outbox = ReviewDecisionOutbox(store, transport)
        val transcript = ReviewableTranscript(
            id = "note-123",
            transcript = "Buy coffee beans and oatmeal.",
            status = NoteStatus.READY_FOR_REVIEW,
        )

        assertEquals(ReviewDecisionQueueResult.Queued, outbox.queue(transcript, ReviewDecision.Approve))
        assertEquals(ReviewDecisionQueueResult.AlreadyQueued, outbox.queue(transcript, ReviewDecision.Approve))
        outbox.synchronize()

        assertEquals(emptyList(), outbox.pendingDecisions())
        assertEquals(
            AuthoritativeReviewStatus("note-123", transcript.transcript, NoteStatus.APPROVED),
            outbox.authoritativeStatus("note-123"),
        )
        assertEquals(ReviewDecisionQueueResult.AlreadyApplied, outbox.queue(transcript, ReviewDecision.Approve))
        outbox.synchronize()
        assertEquals(1, transport.deliveries)
    }

    @Test
    fun `mismatched delivery status leaves the durable command queued`() {
        val store = InMemoryReviewDecisionStore()
        val outbox = ReviewDecisionOutbox(
            store,
            ReviewDecisionTransport {
                ReviewDecisionDelivery.Delivered(
                    AuthoritativeReviewStatus("different-note", "Wrong transcript", NoteStatus.APPROVED),
                )
            },
        )
        val transcript = ReviewableTranscript(
            id = "note-123",
            transcript = "Buy coffee beans and oatmeal.",
            status = NoteStatus.READY_FOR_REVIEW,
        )

        outbox.queue(transcript, ReviewDecision.Approve)
        outbox.synchronize()

        assertEquals(listOf(PendingReviewDecision("note-123", ReviewDecision.Approve, transcript.transcript)), outbox.pendingDecisions())
        assertEquals(null, outbox.authoritativeStatus("note-123"))
        assertEquals(transcript, outbox.retainedTranscript("note-123"))
    }

    @Test
    fun `conflicting authoritative status leaves the pending decision queued`() {
        val store = InMemoryReviewDecisionStore()
        val outbox = ReviewDecisionOutbox(
            store,
            ReviewDecisionTransport {
                ReviewDecisionDelivery.Delivered(
                    AuthoritativeReviewStatus("note-123", "Buy coffee beans and oatmeal.", NoteStatus.APPROVED),
                )
            },
        )
        val transcript = ReviewableTranscript("note-123", "Buy coffee beans and oatmeal.", NoteStatus.READY_FOR_REVIEW)

        outbox.queue(transcript, ReviewDecision.Reject)
        outbox.synchronize()

        assertEquals(listOf(PendingReviewDecision("note-123", ReviewDecision.Reject, transcript.transcript)), outbox.pendingDecisions())
        assertEquals(null, outbox.authoritativeStatus("note-123"))
    }

    private class SequencedTransport(
        vararg outcomes: ReviewDecisionDelivery,
    ) : ReviewDecisionTransport {
        private val outcomes = outcomes.toList().iterator()
        var deliveries = 0
            private set

        override fun send(decision: PendingReviewDecision): ReviewDecisionDelivery {
            deliveries += 1
            return outcomes.next()
        }
    }

    private class RecordingDeliveryTransport(
        private val status: AuthoritativeReviewStatus,
    ) : ReviewDecisionTransport {
        var deliveries = 0
            private set

        override fun send(decision: PendingReviewDecision): ReviewDecisionDelivery {
            deliveries += 1
            return ReviewDecisionDelivery.Delivered(status)
        }
    }

    private object DisconnectedTransport : ReviewDecisionTransport {
        override fun send(decision: PendingReviewDecision): ReviewDecisionDelivery =
            ReviewDecisionDelivery.Disconnected
    }

    private object RetryableFailureTransport : ReviewDecisionTransport {
        override fun send(decision: PendingReviewDecision): ReviewDecisionDelivery =
            ReviewDecisionDelivery.RetryableFailure
    }
}
