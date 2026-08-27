# watch-note-taker

Kotlin/JVM domain core for the Wear OS voice-note MVP.

## Implemented

- Fold-authoritative note states and idempotent review/delivery commands
- Read-only transcript pagination for watch review
- Explicit approve/reject confirmation flow
- Durable, retryable review-decision outbox
- Crash-safe file-backed outbox persistence, using atomic replacement where supported
- Idempotent Markdown delivery with stable note filenames

The implementation is intentionally transport- and UI-independent. `ReviewDecisionTransport`,
`FoldReviewableNoteSource`, and `MarkdownDeliveryTransport` are the integration seams for the
eventual Wear OS, Fold, transcription backend, and vault adapters.

## Verify

```text
gradlew.bat test
```

## Current scope

Debug Android modules now exist for the phone and watch, but they are still shell applications.
The parent MVP still needs real microphone capture, Wear Data Layer transport, backend
authentication and bounded transcription, vault configuration, permissions/setup, and end-to-end
release validation.
