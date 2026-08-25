package com.peterle95.watchnotetaker.notes

/**
 * Watch-facing feedback for a decision that has been durably queued but has not
 * yet received the Fold's authoritative response.
 */
enum class ReviewDecisionSynchronizationState {
    Disconnected,
    RetryableFailure,
}

/**
 * Presentation state for explicit approve and reject controls on a transcript
 * already open in the read-only review flow.
 */
sealed interface TranscriptReviewControlState {
    data object Closed : TranscriptReviewControlState

    data class Reviewing(
        val transcript: ReviewableTranscript,
    ) : TranscriptReviewControlState

    data class ConfirmationRequired(
        val transcript: ReviewableTranscript,
        val decision: ReviewDecision,
    ) : TranscriptReviewControlState

    data class PendingSynchronization(
        val transcript: ReviewableTranscript,
        val decision: ReviewDecision,
        val synchronization: ReviewDecisionSynchronizationState,
    ) : TranscriptReviewControlState

    data class ConflictingAuthoritativeStatus(
        val transcript: ReviewableTranscript,
        val decision: ReviewDecision,
        val status: AuthoritativeReviewStatus,
    ) : TranscriptReviewControlState

    data class DecisionAccepted(
        val status: AuthoritativeReviewStatus,
    ) : TranscriptReviewControlState
}

/**
 * Explicit confirmation seam for review decisions.
 *
 * A requested control action is inert until [confirm] persists it to the
 * watch-side outbox. The Fold remains authoritative: a queued action shows its
 * synchronization state until the matching authoritative result arrives.
 */
class TranscriptReviewControls(
    private val outbox: ReviewDecisionOutbox,
) {
    private var state: TranscriptReviewControlState = TranscriptReviewControlState.Closed

    fun open(transcript: ReviewableTranscript) {
        require(transcript.status == NoteStatus.READY_FOR_REVIEW) {
            "Only ready transcripts can enter review controls"
        }
        state = TranscriptReviewControlState.Reviewing(transcript)
    }

    fun requestApproval() = request(ReviewDecision.Approve)

    fun requestRejection() = request(ReviewDecision.Reject)

    fun cancelConfirmation() {
        val confirmation = state as? TranscriptReviewControlState.ConfirmationRequired
            ?: error("No review decision is awaiting confirmation")
        state = TranscriptReviewControlState.Reviewing(confirmation.transcript)
    }

    fun confirm() {
        val confirmation = state as? TranscriptReviewControlState.ConfirmationRequired
            ?: error("No review decision is awaiting confirmation")
        when (outbox.queue(confirmation.transcript, confirmation.decision)) {
            ReviewDecisionQueueResult.Queued,
            ReviewDecisionQueueResult.AlreadyQueued,
            -> synchronize(confirmation.transcript, confirmation.decision)

            ReviewDecisionQueueResult.AlreadyApplied -> {
                val status = outbox.authoritativeStatus(confirmation.transcript.id)
                    ?: error("An applied review decision must have an authoritative status")
                state = TranscriptReviewControlState.DecisionAccepted(status)
            }
        }
    }

    fun refreshSynchronization() {
        val pending = state as? TranscriptReviewControlState.PendingSynchronization
            ?: error("No review decision is awaiting synchronization")
        synchronize(pending.transcript, pending.decision)
    }

    fun snapshot(): TranscriptReviewControlState = state

    private fun request(decision: ReviewDecision) {
        val reviewing = state as? TranscriptReviewControlState.Reviewing
            ?: error("A review decision can only be requested for an open transcript")
        state = TranscriptReviewControlState.ConfirmationRequired(reviewing.transcript, decision)
    }

    private fun synchronize(transcript: ReviewableTranscript, decision: ReviewDecision) {
        val synchronization = outbox.synchronize()
            .firstOrNull { it.decision.noteId == transcript.id && it.decision.decision == decision }
            ?: error("Queued review decision was not available for synchronization")

        val authoritativeStatus = outbox.authoritativeStatus(transcript.id)
        if (authoritativeStatus?.status?.matches(decision) == true) {
            state = TranscriptReviewControlState.DecisionAccepted(authoritativeStatus)
            return
        }

        if (synchronization.delivery is ReviewDecisionDelivery.Delivered) {
            state = TranscriptReviewControlState.ConflictingAuthoritativeStatus(
                transcript,
                decision,
                synchronization.delivery.authoritativeStatus,
            )
            return
        }

        state = TranscriptReviewControlState.PendingSynchronization(
            transcript,
            decision,
            synchronization.delivery.toSynchronizationState(),
        )
    }

    private fun ReviewDecisionDelivery.toSynchronizationState(): ReviewDecisionSynchronizationState = when (this) {
        ReviewDecisionDelivery.Disconnected -> ReviewDecisionSynchronizationState.Disconnected
        ReviewDecisionDelivery.RetryableFailure -> ReviewDecisionSynchronizationState.RetryableFailure
        is ReviewDecisionDelivery.Delivered -> error("Delivered responses must be handled as authoritative results")
    }
}
