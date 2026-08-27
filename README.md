# watch-note-taker

Kotlin/JVM domain core for the Wear OS voice-note MVP.

## Implemented

- Fold-authoritative note states and idempotent review/delivery commands
- Read-only transcript pagination for watch review
- Explicit approve/reject confirmation flow
- Durable, retryable review-decision outbox
- Crash-safe file-backed outbox persistence, using atomic replacement where supported
- Idempotent Markdown delivery with stable note filenames
- Watch microphone capture capped at 120 seconds, with a durable ten-recording queue
- Wear Data Layer audio transfer with phone-side durable acknowledgement
- Phone developer transcript entry, review decisions, and persisted vault-folder delivery

The implementation is intentionally transport- and UI-independent. `ReviewDecisionTransport`,
`FoldReviewableNoteSource`, and `MarkdownDeliveryTransport` are the integration seams for the
eventual Wear OS, Fold, transcription backend, and vault adapters.

## Verify

```text
gradlew.bat test
```

## Current scope

The Android apps support a local developer transcript workflow. Production transcription still
requires the authenticated, bounded backend described in `docs/research/transcription-providers.md`.
