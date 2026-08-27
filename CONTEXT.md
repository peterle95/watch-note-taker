# Domain Context

## Note

A captured voice recording with a stable ID. It remains available until the Fold has durably
accepted it into its queue.

## Fold

The Android phone companion is authoritative for durable queueing, transcription status, review
decisions, and Markdown delivery.

## Watch

The Wear OS device captures recordings, buffers them while disconnected, and presents transcripts
for read-only review. It does not finalize vault delivery.

## Review Decision

An explicit approve or reject action for a ready transcript. Decisions may be queued offline and
converge to the Fold-authoritative status after reconnection.
