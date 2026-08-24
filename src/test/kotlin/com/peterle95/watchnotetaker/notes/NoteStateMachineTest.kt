package com.peterle95.watchnotetaker.notes

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class NoteStateMachineTest {
    @Test
    fun `completed transcription enters the review queue exactly once`() {
        val transcribingNote = note(status = NoteStatus.TRANSCRIBING)

        val ready = NoteStateMachine.execute(transcribingNote, NoteCommand.MarkReadyForReview)
        val repeatedCompletion = NoteStateMachine.execute(ready.note, NoteCommand.MarkReadyForReview)

        assertEquals(TransitionDisposition.APPLIED, ready.disposition)
        assertEquals(NoteStatus.READY_FOR_REVIEW, ready.note.status)
        assertEquals(transcribingNote.transcript, ready.note.transcript)
        assertEquals(TransitionDisposition.ALREADY_APPLIED, repeatedCompletion.disposition)
        assertEquals(ready.note, repeatedCompletion.note)
    }

    @Test
    fun `approving a ready note preserves its transcript and is idempotent`() {
        val readyNote = note(status = NoteStatus.READY_FOR_REVIEW)

        val approved = NoteStateMachine.execute(readyNote, NoteCommand.Approve)
        val repeatedApproval = NoteStateMachine.execute(approved.note, NoteCommand.Approve)

        assertEquals(TransitionDisposition.APPLIED, approved.disposition)
        assertEquals(NoteStatus.APPROVED, approved.note.status)
        assertEquals(readyNote.transcript, approved.note.transcript)
        assertEquals(TransitionDisposition.ALREADY_APPLIED, repeatedApproval.disposition)
        assertEquals(approved.note, repeatedApproval.note)
    }

    @Test
    fun `rejecting a ready note is idempotent and prevents later approval`() {
        val readyNote = note(status = NoteStatus.READY_FOR_REVIEW)

        val rejected = NoteStateMachine.execute(readyNote, NoteCommand.Reject)
        val attemptedApproval = NoteStateMachine.execute(rejected.note, NoteCommand.Approve)
        val repeatedRejection = NoteStateMachine.execute(rejected.note, NoteCommand.Reject)

        assertEquals(NoteStatus.REJECTED, rejected.note.status)
        assertEquals(TransitionDisposition.INVALID, attemptedApproval.disposition)
        assertEquals(rejected.note, attemptedApproval.note)
        assertEquals(TransitionDisposition.ALREADY_APPLIED, repeatedRejection.disposition)
        assertEquals(rejected.note, repeatedRejection.note)
    }

    @Test
    fun `only active delivery attempts can produce delivery states`() {
        val readyNote = note(status = NoteStatus.READY_FOR_REVIEW)
        val attemptedDelivery = NoteStateMachine.execute(readyNote, NoteCommand.MarkDelivered(attempt = 1))

        assertEquals(TransitionDisposition.INVALID, attemptedDelivery.disposition)
        assertEquals(readyNote, attemptedDelivery.note)
    }

    @Test
    fun `delivery failure preserves transcript through retry and delivery`() {
        val approvedNote = note(status = NoteStatus.APPROVED)
        val firstAttempt = NoteStateMachine.execute(approvedNote, NoteCommand.BeginDelivery)
        val failed = NoteStateMachine.execute(firstAttempt.note, NoteCommand.MarkDeliveryFailed(attempt = 1))
        val retried = NoteStateMachine.execute(failed.note, NoteCommand.RetryDelivery)
        val secondAttempt = NoteStateMachine.execute(retried.note, NoteCommand.BeginDelivery)
        val delivered = NoteStateMachine.execute(secondAttempt.note, NoteCommand.MarkDelivered(attempt = 2))

        assertEquals(NoteStatus.DELIVERY_FAILED, failed.note.status)
        assertEquals(NoteStatus.APPROVED, retried.note.status)
        assertEquals(NoteStatus.DELIVERED, delivered.note.status)
        assertEquals(approvedNote.transcript, failed.note.transcript)
        assertEquals(approvedNote.transcript, retried.note.transcript)
        assertEquals(approvedNote.transcript, delivered.note.transcript)
    }

    @Test
    fun `stale delivery callbacks cannot overwrite a retried attempt`() {
        val approvedNote = note(status = NoteStatus.APPROVED)
        val firstAttempt = NoteStateMachine.execute(approvedNote, NoteCommand.BeginDelivery)
        val failed = NoteStateMachine.execute(firstAttempt.note, NoteCommand.MarkDeliveryFailed(attempt = 1))
        val retried = NoteStateMachine.execute(failed.note, NoteCommand.RetryDelivery)
        val secondAttempt = NoteStateMachine.execute(retried.note, NoteCommand.BeginDelivery)

        val staleSuccess = NoteStateMachine.execute(secondAttempt.note, NoteCommand.MarkDelivered(attempt = 1))
        val staleFailure = NoteStateMachine.execute(secondAttempt.note, NoteCommand.MarkDeliveryFailed(attempt = 1))
        val delivered = NoteStateMachine.execute(secondAttempt.note, NoteCommand.MarkDelivered(attempt = 2))

        assertEquals(TransitionDisposition.INVALID, staleSuccess.disposition)
        assertEquals(TransitionDisposition.INVALID, staleFailure.disposition)
        assertEquals(secondAttempt.note, staleSuccess.note)
        assertEquals(secondAttempt.note, staleFailure.note)
        assertEquals(NoteStatus.DELIVERED, delivered.note.status)
    }

    @Test
    fun `review commands cannot move a transcribing note into a decision state`() {
        val transcribingNote = note(status = NoteStatus.TRANSCRIBING)

        val approval = NoteStateMachine.execute(transcribingNote, NoteCommand.Approve)
        val rejection = NoteStateMachine.execute(transcribingNote, NoteCommand.Reject)

        assertEquals(TransitionDisposition.INVALID, approval.disposition)
        assertEquals(TransitionDisposition.INVALID, rejection.disposition)
        assertEquals(transcribingNote, approval.note)
        assertEquals(transcribingNote, rejection.note)
    }

    private fun note(status: NoteStatus) = ReviewableNote(
        id = "note-123",
        transcript = "Remember to buy coffee beans.",
        status = status,
        createdAt = Instant.parse("2026-08-24T15:00:00Z"),
    )
}
