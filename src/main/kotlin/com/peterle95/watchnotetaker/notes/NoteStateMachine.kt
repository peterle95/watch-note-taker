package com.peterle95.watchnotetaker.notes

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
)

sealed interface NoteCommand {
    data object Approve : NoteCommand
    data object Reject : NoteCommand
    data object MarkDelivered : NoteCommand
    data object MarkDeliveryFailed : NoteCommand
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
 * Approval and rejection are only valid from [NoteStatus.READY_FOR_REVIEW].
 * Delivery commands only act on approved notes, while a failed delivery can be
 * retried by returning to [NoteStatus.APPROVED]. Terminal states never permit a
 * contradictory review decision.
 */
object NoteStateMachine {
    fun execute(note: ReviewableNote, command: NoteCommand): Transition = when (command) {
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

        NoteCommand.MarkDelivered -> note.transition(
            expected = NoteStatus.APPROVED,
            target = NoteStatus.DELIVERED,
            alreadyApplied = setOf(NoteStatus.DELIVERED),
        )

        NoteCommand.MarkDeliveryFailed -> note.transition(
            expected = NoteStatus.APPROVED,
            target = NoteStatus.DELIVERY_FAILED,
            alreadyApplied = setOf(NoteStatus.DELIVERY_FAILED),
        )

        NoteCommand.RetryDelivery -> note.transition(
            expected = NoteStatus.DELIVERY_FAILED,
            target = NoteStatus.APPROVED,
            alreadyApplied = emptySet(),
        )
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
