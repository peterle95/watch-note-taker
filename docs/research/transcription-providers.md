# Cloud Transcription Provider Research

Research date: 2026-08-23. Sources are official provider documentation.

## Recommendation

Use Google Cloud Speech-to-Text V1 Standard for the MVP, behind a small backend
that owns the credential. It has a documented 60-minute/month free allowance,
supports OGG Opus, WebM Opus, FLAC, LINEAR16, AMR, and AMR-WB, and defaults to
not logging customer audio or transcripts. Ten two-minute notes per day is 20
minutes/day; therefore the product must enforce its own daily/monthly limit and
stop before the free allowance is exhausted. Disable project billing for a
strict $0 guarantee, or use a project-scoped spend cap where available.

## Comparison

| Provider | Limits and cost | Audio/API fit | Billing and retention risk |
| --- | --- | --- | --- |
| [Google Cloud STT pricing](https://cloud.google.com/speech-to-text/pricing) | V1 Standard: first 60 minutes/month free; then $0.016/min with data logging or $0.024/min without. Billing rounds up to seconds. | [REST/client APIs](https://cloud.google.com/speech-to-text/docs/v1/speech-to-text-requests); [OGG/WebM Opus, FLAC, LINEAR16, AMR/AMR-WB](https://cloud.google.com/speech-to-text/docs/encoding). Two-minute notes fit async recognition; use backend credentials. | [Default is no audio/transcript logging](https://cloud.google.com/speech-to-text/docs/v1/data-logging). [Budget alerts do not cap spend](https://cloud.google.com/billing/docs/how-to/budgets); disable billing or automate project shutdown before the hard ceiling. |
| [Deepgram pricing](https://deepgram.com/pricing) | PAYG advertises a $200 credit, then usage pricing; pre-recorded Nova-3 monolingual is $0.0043/min. No credit card required at signup. | REST pre-recorded transcription and WebSocket streaming; suitable for short uploaded recordings. Confirm exact Wear-generated codec and endpoint limits during spike. | Credit expiry/overage and auto-load settings need explicit account configuration. [Privacy policy](https://deepgram.com/privacy-policy) and [model-improvement documentation](https://developers.deepgram.com/docs/the-deepgram-model-improvement-partnership-program) require reviewing opt-in/retention settings. Less predictable hard-$0 control than a disabled Google project. |
| [OpenAI file transcription](https://developers.openai.com/api/docs/guides/speech-to-text) | No included transcription free tier in [API pricing](https://openai.com/api/pricing/); files up to 25 MB. | Multipart REST endpoint; accepts mp3, mp4, mpeg, mpga, m4a, wav, and webm. A two-minute compressed note is well below 25 MB. | Requires paid API billing and server-side API key. [API data policy](https://openai.com/policies/api-data-usage-policies) is acceptable only after verifying current retention settings; no natural free-tier hard stop. Not recommended for this MVP's no-charge constraint. |

## Required guardrails

- Enforce max recording duration of 120 seconds on device and server.
- Enforce 10 accepted notes/day per account, plus a monthly minute ceiling below
  60 minutes if the MVP must remain within Google's free allowance.
- Reject requests before upload when the account's budget is exhausted; do not
  rely on provider budget email alerts.
- Keep provider credentials off Android/Wear OS. A backend also permits quota,
  authentication, abuse, and provider switching controls.
- Treat failed/transient requests carefully: retry limits must not bypass the
  note count or minute budget. Show a local-save/retry-later fallback.

## Decision

Google STT V1 is the recommendation. Deepgram is the fastest experimental
alternative if a $200 promotional credit is acceptable, but it is less suitable
for a permanent hard-$0 promise. OpenAI is technically compatible but fails the
free/no-charge requirement.
