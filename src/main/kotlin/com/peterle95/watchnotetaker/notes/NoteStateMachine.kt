package com.peterle95.watchnotetaker.notes

import java.time.Instant

/**
 * The Fold-authoritative lifecycle of a transcribed note.
 *
 * The transcript is immutable across all transitions. A command that cannot be
 * applied returns the original note, so callers cannot lose content by retrying
 * or issuing an out-of-order command.
 */
enum class NoteStatus {
    TRANSCRIBING,
    READY_FOR_REVIEW,
    APPROVED,
    REJECTED,
    DELIVERED,
    DELIVERY_FAILED,
}

data class ReviewableNote(
    val id: String,
    val transcript: String,
    val status: NoteStatus,
    val createdAt: Instant = Instant.EPOCH,
    val nextDeliveryAttempt: Long = 1,
    val activeDeliveryAttempt: Long? = null,
)

sealed interface NoteCommand {
    data object MarkReadyForReview : NoteCommand
    data object Approve : NoteCommand
    data object Reject : NoteCommand
    data object BeginDelivery : NoteCommand
    data class MarkDelivered(val attempt: Long) : NoteCommand
    data class MarkDeliveryFailed(val attempt: Long) : NoteCommand
    data object RetryDelivery : NoteCommand
}

enum class TransitionDisposition {
    APPLIED,
    ALREADY_APPLIED,
    INVALID,
}

data class Transition(
    val note: ReviewableNote,
    val disposition: TransitionDisposition,
)

/**
 * Applies idempotent review and delivery commands to a note.
 *
 * Approval and rejection are only valid from [NoteStatus.READY_FOR_REVIEW]. A
 * delivery worker starts an attempt before it can report an outcome; only the
 * active attempt's outcome is accepted. Retried delivery receives a new attempt
 * number, so delayed callbacks from prior attempts cannot overwrite its state.
 */
object NoteStateMachine {
    fun execute(note: ReviewableNote, command: NoteCommand): Transition = when (command) {
        NoteCommand.MarkReadyForReview -> note.transition(
            expected = NoteStatus.TRANSCRIBING,
            target = NoteStatus.READY_FOR_REVIEW,
            alreadyApplied = setOf(NoteStatus.READY_FOR_REVIEW),
        )

        NoteCommand.Approve -> note.transition(
            expected = NoteStatus.READY_FOR_REVIEW,
            target = NoteStatus.APPROVED,
            alreadyApplied = setOf(NoteStatus.APPROVED, NoteStatus.DELIVERED),
        )

        NoteCommand.Reject -> note.transition(
            expected = NoteStatus.READY_FOR_REVIEW,
            target = NoteStatus.REJECTED,
            alreadyApplied = setOf(NoteStatus.REJECTED),
        )

        NoteCommand.BeginDelivery -> note.beginDelivery()
        is NoteCommand.MarkDelivered -> note.completeDelivery(command.attempt, NoteStatus.DELIVERED)
        is NoteCommand.MarkDeliveryFailed -> note.completeDelivery(command.attempt, NoteStatus.DELIVERY_FAILED)

        NoteCommand.RetryDelivery -> note.transition(
            expected = NoteStatus.DELIVERY_FAILED,
            target = NoteStatus.APPROVED,
            alreadyApplied = emptySet(),
        )
    }

    private fun ReviewableNote.beginDelivery(): Transition = when {
        status != NoteStatus.APPROVED -> Transition(this, TransitionDisposition.INVALID)
        activeDeliveryAttempt != null -> Transition(this, TransitionDisposition.ALREADY_APPLIED)
        else -> Transition(
            copy(
                nextDeliveryAttempt = nextDeliveryAttempt + 1,
                activeDeliveryAttempt = nextDeliveryAttempt,
            ),
            TransitionDisposition.APPLIED,
        )
    }

    private fun ReviewableNote.completeDelivery(attempt: Long, target: NoteStatus): Transition = when {
        status == NoteStatus.APPROVED && activeDeliveryAttempt == attempt -> Transition(
            copy(status = target, activeDeliveryAttempt = null),
            TransitionDisposition.APPLIED,
        )

        status == target -> Transition(this, TransitionDisposition.ALREADY_APPLIED)
        else -> Transition(this, TransitionDisposition.INVALID)
    }

    private fun ReviewableNote.transition(
        expected: NoteStatus,
        target: NoteStatus,
        alreadyApplied: Set<NoteStatus>,
    ): Transition = when (status) {
        expected -> Transition(copy(status = target), TransitionDisposition.APPLIED)
        in alreadyApplied -> Transition(this, TransitionDisposition.ALREADY_APPLIED)
        else -> Transition(this, TransitionDisposition.INVALID)
    }
}
