package com.peterle95.watchnotetaker.notes

enum class ReviewDecision {
    Approve,
    Reject,
}

data class PendingReviewDecision(
    val noteId: String,
    val decision: ReviewDecision,
    val transcript: String? = null,
)

data class AuthoritativeReviewStatus(
    val noteId: String,
    val transcript: String,
    val status: NoteStatus,
)

data class ReviewDecisionOutboxSnapshot(
    val pendingDecisions: List<PendingReviewDecision> = emptyList(),
    val authoritativeStatuses: Map<String, AuthoritativeReviewStatus> = emptyMap(),
)

fun interface ReviewDecisionStore {
    fun read(): ReviewDecisionOutboxSnapshot
}

interface WritableReviewDecisionStore : ReviewDecisionStore {
    fun write(snapshot: ReviewDecisionOutboxSnapshot)
}

class InMemoryReviewDecisionStore : WritableReviewDecisionStore {
    private var snapshot = ReviewDecisionOutboxSnapshot()

    override fun read(): ReviewDecisionOutboxSnapshot = snapshot

    override fun write(snapshot: ReviewDecisionOutboxSnapshot) {
        this.snapshot = snapshot
    }
}

sealed interface ReviewDecisionDelivery {
    data object Disconnected : ReviewDecisionDelivery
    data object RetryableFailure : ReviewDecisionDelivery
    data class Delivered(val authoritativeStatus: AuthoritativeReviewStatus) : ReviewDecisionDelivery
}

fun interface ReviewDecisionTransport {
    fun send(decision: PendingReviewDecision): ReviewDecisionDelivery
}

enum class ReviewDecisionQueueResult {
    Queued,
    AlreadyQueued,
    AlreadyApplied,
}

/**
 * Durable watch-side outbox for review decisions.
 *
 * Commands remain in [ReviewDecisionStore] until the Fold returns the resulting
 * authoritative note status. Repeated watch submissions are deduplicated by
 * note and decision, and retries never remove transcript-bearing state.
 */
class ReviewDecisionOutbox(
    private val store: WritableReviewDecisionStore,
    private val transport: ReviewDecisionTransport,
) {
    fun queue(noteId: String, decision: ReviewDecision): ReviewDecisionQueueResult =
        enqueue(PendingReviewDecision(noteId, decision))

    fun queue(transcript: ReviewableTranscript, decision: ReviewDecision): ReviewDecisionQueueResult {
        require(transcript.status == NoteStatus.READY_FOR_REVIEW) {
            "Only ready transcripts can receive a review decision"
        }
        return enqueue(PendingReviewDecision(transcript.id, decision, transcript.transcript))
    }

    fun pendingDecisions(): List<PendingReviewDecision> = store.read().pendingDecisions

    fun retainedTranscript(noteId: String): ReviewableTranscript? {
        val snapshot = store.read()
        snapshot.authoritativeStatuses[noteId]?.let { status ->
            return ReviewableTranscript(status.noteId, status.transcript, status.status)
        }
        return snapshot.pendingDecisions
            .firstOrNull { decision -> decision.noteId == noteId && decision.transcript != null }
            ?.let { decision -> ReviewableTranscript(decision.noteId, decision.transcript!!, NoteStatus.READY_FOR_REVIEW) }
    }

    fun authoritativeStatus(noteId: String): AuthoritativeReviewStatus? =
        store.read().authoritativeStatuses[noteId]

    fun synchronize() {
        store.read().pendingDecisions.forEach { pendingDecision ->
            when (val delivery = transport.send(pendingDecision)) {
                ReviewDecisionDelivery.Disconnected,
                ReviewDecisionDelivery.RetryableFailure,
                -> Unit

                is ReviewDecisionDelivery.Delivered -> recordAuthoritativeStatus(pendingDecision, delivery.authoritativeStatus)
            }
        }
    }

    private fun enqueue(pendingDecision: PendingReviewDecision): ReviewDecisionQueueResult {
        val snapshot = store.read()
        if (snapshot.authoritativeStatuses[pendingDecision.noteId]?.status?.matches(pendingDecision.decision) == true) {
            return ReviewDecisionQueueResult.AlreadyApplied
        }
        if (snapshot.pendingDecisions.any { queued ->
                queued.noteId == pendingDecision.noteId && queued.decision == pendingDecision.decision
            }
        ) {
            return ReviewDecisionQueueResult.AlreadyQueued
        }

        store.write(snapshot.copy(pendingDecisions = snapshot.pendingDecisions + pendingDecision))
        return ReviewDecisionQueueResult.Queued
    }

    private fun NoteStatus.matches(decision: ReviewDecision): Boolean = when (decision) {
        ReviewDecision.Approve -> this == NoteStatus.APPROVED || this == NoteStatus.DELIVERED
        ReviewDecision.Reject -> this == NoteStatus.REJECTED
    }

    private fun recordAuthoritativeStatus(
        pendingDecision: PendingReviewDecision,
        authoritativeStatus: AuthoritativeReviewStatus,
    ) {
        if (
            authoritativeStatus.noteId != pendingDecision.noteId ||
            !authoritativeStatus.status.matches(pendingDecision.decision)
        ) {
            return
        }
        val snapshot = store.read()
        store.write(
            snapshot.copy(
                pendingDecisions = snapshot.pendingDecisions - pendingDecision,
                authoritativeStatuses = snapshot.authoritativeStatuses + (authoritativeStatus.noteId to authoritativeStatus),
            ),
        )
    }
}
