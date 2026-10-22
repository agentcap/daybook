# Architecture

The Android app is `dev.daybook.app`, minimum API 31 and target/compile API 37. UI uses Compose Material 3, Kotlin 2.4, AGP 9.1.1, and Gradle 9.3.1.

## Data

Room stores typed domain documents in an indexed `documents` table with stable identifiers, kind, day, and JSON. `JournalRepository` serializes mutations with a mutex and a Room transaction, validates cross-document references, and writes only changed rows. Room's version-1 schema is checked in. DataStore stores non-secret settings; a separate Keystore-backed vault stores the API key.

An entry retains original text separately from editable current text. Edits append previous versions. Attachments have stable IDs and private relative paths. Reviews retain their own model name, creation time, source fingerprint, journal, summary, and captions. The source fingerprint detects edited notes, transcription changes, new attachments, and changed active goals.

Activities are the authority for goal progress. Only explicit manual logs or confirmed suggestions create activities. Undo marks activity inactive. Daily counts use distinct goal IDs, including partial effort with amount zero. Weekly progress uses Monday-based periods and excludes events after the viewed date; one-time progress accumulates through that date.

Confirmed AI activity carries entry evidence. Overlapping evidence for the same goal cannot count twice. Regenerating a review supersedes old pending suggestions while preserving handled decisions. Stale suggestions cannot be confirmed.

List items and proposed creations are additional typed documents in the same schema. Proposals carry source entry IDs, a source-only fingerprint, a stable deduplication key, and their approval status. Approval creates a goal, to-do, or idea in a repository transaction without logging goal activity. Regeneration retains prior decisions. Undo refuses to delete a goal with activity or a list item that has since changed. Backups include both collections; restoring media paths remaps the fingerprints of current proposals. Older manifests default the added collections to empty.

## Capture

A microphone foreground service records mono 16 kHz PCM WAV to app-private storage, checkpoints its header regularly, and holds a bounded wake lock for screen-off recording. A process interruption changes unfinished entries to interrupted on next startup. WAV repair reconciles the header with actual captured bytes.

WorkManager serializes transcription per entry. A process-wide mutex serializes native inference calls. SpeechCatalog pins model components, checksums, sizes, engine IDs, and download job names. Each recording and queued worker pins its speech model. SpeechEngines routes to whisper.cpp, Moonshine Voice 0.1.5, or Sherpa-onnx 1.13.8 (Parakeet TDT int8). Models use bounded 30-second audio windows; Whisper additionally uses an energy gate. Originals are never sent to a speech service. Quiet/noisy input may still need manual correction, particularly at chunk boundaries.

Live previews use a reusable whisper.cpp context with three CPU threads, three-second initial previews, and six-second segments. A bounded channel drops older queued windows when inference falls behind, so transcription cannot block PCM capture. New previews replace the same segment’s earlier draft. The recording session exposes elapsed audio time, RMS level, text, and status through StateFlow. Drafts are persisted while recording; final transcription preserves the previous draft as a revision before replacing it. Preview failure leaves capture available, and final transcription failure retains draft text and the WAV.

The photo picker grants access to selected files; the app copies them. Camera capture uses a narrowly scoped FileProvider for temporary camera outputs. Neither broad gallery permission nor camera permission is requested by Daybook; the selected camera app handles capture.

## Jobs and AI

AlarmManager schedules inexact check-ins and snoozes. A tagged nightly dispatch work request enqueues a date-specific, network-constrained cleanup request and the following night's dispatcher. Settings, reboot, time changes, and package updates reschedule dispatches without changing historical journal dates. Before replacing dispatches, due unfinished date-tagged reviews are preserved as independent cleanup jobs.

Cleanup snapshots entries, photos, active goals, and confirmed activity. Incomplete voice notes block review generation until resolved. OkHttp sends a strict JSON-schema Responses request. The app validates the completed result and its source IDs before storing it; if the source fingerprint changed while the request was in flight, it refuses to restore obsolete/deleted content.

Cleanup also supplies open, unarchived list titles and their kinds to avoid duplicate proposals. Structured discoveries distinguish aspirations from tasks and ideas, require valid evidence, and never create records automatically. Totals and streaks are calculated locally. Streaks count distinct saved journal dates; the history calendar and statistics use the same snapshot as the journal.

Offline jobs wait for connectivity. Network/rate-limit/server failures use bounded exponential retries. Authentication, missing model access, validation, and provider refusal errors are surfaced without overwriting originals. Reviews are limited to a 40 MiB JSON upload and 10,000 output tokens; oversized/incomplete results fail explicitly.

## Testing and distribution

A `verification` build type has a separate application ID and its own storage. Android instrumentation only targets this package. Unit tests cover pure rules, archive integrity, and HTTP request/response handling with MockWebServer.

Real speech and screenshot tours are opt-in. The speech fixture is copied from the pinned upstream repository into instrumentation assets only. Real speech tests temporarily enable airplane mode on an emulator and restore connectivity in a finally block. Synthetic screenshot data is never seeded into the personal APK.

The signed personal release is non-debuggable and includes ARM64 and x86-64. It is directly installable and is not a Play Store release. Preserve the ignored `.tools/signing/` folder for future in-place updates. Debug and isolated verification APKs remain available for development.

## Interface and capture routing (0.2.0)

The shared Compose theme supplies lavender light/dark colors, typography, rounded cards, and a gradient backdrop. Settings has focused category pages, immediate saves for switches/theme, and explicit schedule/key/model saves. Save errors are shown inline and API-key status refreshes after the encrypted write completes.

A retained JournalLockSession tracks successful authentication and a single automatic attempt per foreground visit. MainActivity attaches AndroidX BiometricPrompt callbacks on creation, waits for resume, and locks access when stopped. Credential authentication and configuration changes do not start duplicate prompts; cancellation leaves a manual retry. A full-screen secure lock overlay retains pending photo/backup result handlers beneath it.

Both home-screen providers use immutable activity PendingIntents. The dedicated RecordWidget and the existing capture widget's Record button request direct capture. MainActivity validates a private widget token before accepting direct recording through its exported launcher, waits for onboarding, resume, and app unlock, then requests microphone permission and starts the microphone foreground service. Intents are consumed once and pending capture survives configuration changes. The service guards against concurrent recordings. A widget never starts a microphone service directly from a background broadcast.

## Interface additions (0.3.0)

The compact typography follows system font scaling. Navigation uses short fade/slide transitions; cards and list changes animate size and placement. A full-screen recording dialog shows live text, real microphone energy, elapsed time, save/error state, and background controls. The existing biometric gate remains above the recording UI. Today links to statistics, and History offers Journal, Calendar, and Stats tabs. Lists separates manual to-dos and ideas from reviewable AI proposals.

## On-device review provider (0.4.0)

Settings records an explicit `openai` or `local` review provider. Old Settings and Review JSON initializes the provider to OpenAI. Reviews retain their provider and model identity. Automatic dispatch uses the provider policy, and local cleanup has no connectivity constraint or cloud-consent dependency. Changing the provider replaces unfinished jobs, including untagged jobs from earlier releases. Workers check the selected provider and consent again before generation and check for cancellation/settings changes before committing results.

`LocalCleanupClient` supplies text only to a `LocalTextEngine`; OpenAI retains its existing image-aware Responses client. The local GBNF grammar enforces the review shape and constrains source/goal IDs to input records. The local output evaluates every existing goal with an explicit worked/not-worked decision and checks new goals, tasks, and ideas separately. A typed adapter converts supported observations to the canonical review format; unsupported frequency defaults fall back to one-time goals. Both paths then use ReviewProtocol validation and the same repository approval/deduplication rules. There is no cloud fallback. A photo-only local review fails with an explicit request to add text, and generated local reviews are labeled as text-only.

The separate `local-llm` Android library pins llama.cpp b11724 / `99db719bdf07f7d537431ca556a0512bf3d995b6`. Its CMake project isolates ggml from whisper.cpp; the shared library exports only its JNI entry point and binds internal symbols locally. Native resources are released after each review, CPU inference is serialized and uses up to four CPUs from the process’s allowed affinity, and generation is bounded to 8,192 context tokens, 2,048 output tokens, and eight minutes with cooperative cancellation. These limits fail explicitly rather than saving incomplete JSON. Native source/output bytes use UTF-8. A distinct worked-example conversation teaches the compact extraction format. Greedy candidates are checked against the grammar first; a full vocabulary mask is only needed when the preferred token is invalid. The x86-64 development build enables AVX2/FMA/F16C after a runtime capability check; ARM64 retains its portable baseline.

The pinned Qwen2.5-1.5B-Instruct Q4_K_M model is downloaded separately with a resumable worker, exact size/SHA verification, storage checks, and private installation. Model files are excluded from backups. The APK includes the MIT runtime license; the model is Apache 2.0 and is obtained directly from its official host. No arbitrary model import or remote model code is executed.


## Recovery and publishing beta (0.5.0)

Entries add privacy choices, a deletion timestamp, audio bookmarks and approximate transcript segments with backward-compatible defaults. Weekly reflections are separate typed documents. The Room table schema stays at version 1; full backup manifests advance to version 2. New backups include recoverable trash, and older clients reject their newer manifest.

The repository's shared raw snapshot preserves recovery data; interactive views filter trashed notes, media and source-linked reviews. Confirmed activities remain authoritative. The 30-day maintenance worker permanently removes expired entries and media. Full exports include trash, while Markdown exports use only visible notes.

Media operations use two locks with a consistent order. Export and permanent deletion hold the operations lock. Restore takes restoreGate then operations; photo import and new recording creation briefly take restoreGate. This prevents restore from removing newly captured media while allowing a new recording during an export's frozen snapshot. Native transcription verifies the source path before committing so stale work cannot overwrite a restored note.

Pause/resume stops and restarts AudioRecord without adding paused time to the WAV. Bookmarks use captured-audio offsets. Final Whisper segments retain chunk offsets through silent windows; playback seeks the original recording. JNI checks coroutine cancellation and the Kotlin wrapper preserves CancellationException. The recording callback pauses capture when Android reports that this client is silenced; real calls and Bluetooth routing still require phone tests.

Automatic backups use a persisted Storage Access Framework folder grant. A separate Keystore AES-GCM secret is saved only with consent and is never exported. A new document is written for each backup; old tracked backups are removed only after a successful export. The last seven tracked successes are retained. Configuration generation IDs prevent an older running job from replacing reconfigured state. Disabling removes the saved passphrase and scheduled jobs, leaving already exported files intact.

Cloud workers and manual previews share privacy selection. On-device entries and excluded photos are omitted; source-linked activity, list context and accepted goal proposals from private sources are also filtered. Independent manual goal/list titles remain context. Existing sent content cannot be retracted. On-device cleanup has no cloud fallback.

The Processing screen exposes retry/cancel and download state. Download policy applies to starting/resuming work; cleanup charging constraints are reapplied to unfinished jobs. Reduced animations follow the app setting or Android's disabled animator setting. Feedback uses an editable system-share draft with optional visible diagnostics, without a telemetry SDK.

Signed APK and AAB artifacts are prepared locally, with policy/notice assets and reproducible publishing files. Public developer metadata is read from an ignored release.properties file. The release checker refuses incomplete public metadata and a draft policy site; physical-device and Play Console requirements are tracked separately. No store upload occurs as part of the build.


## Recording discard and reflection wording (0.5.1 beta)

A service-owned DISCARD action stops capture, cancels the live preview, and removes only the current unfinished voice entry, draft revisions, and audio after closing the recorder. It does not enqueue final transcription or delete earlier daily reviews. The repository rejects discarding a saved voice entry. A confirmation sheet keeps the recording active until the user confirms.

Terminal save/discard state is serialized on the main dispatcher with service commands; an immediate next capture cannot be ignored while the prior worker coroutine is finishing. The view closes only after discard finishes. Widget intents received during recording are routed to reopen that capture, preventing a delayed UI effect from replaying it after saving. Saved entries still use the existing Recently Deleted flow.

User-facing generation is called a daily reflection: Create daily reflection, Refresh reflection, AI reflections, and nightly/charging options use matching language. Persisted settings fields, WorkManager names/tags and internal provider interfaces retain their old identifiers for compatibility.

## Speech choices

Per-model locks serialize downloads, inference, and removal. A separate shared inference lock serializes final speech tasks; each native engine closes/releases its sessions after each note. Moonshine loads its bundled dynamic ONNX Runtime; Sherpa statically links ONNX Runtime to avoid competing shared-library versions. Existing Whisper paths and queued jobs remain compatible. Ready markers require all expected files and sizes, and downloads hash each component before writing the manifest marker. Work input and entry metadata retain stable model IDs across restarts. Backups include model preferences and entry IDs, but never downloaded weights. Explicit retranscription preserves prior original text and adds the replaced current text to revisions.

Speech-engine provenance is excluded from review and proposal content fingerprints so pre-upgrade reviews remain current when their source content has not changed. Tests pin known 0.5.3 source hashes.

## Text entry

JournalTextEditor is the full-screen composer used for new notes and saved-entry edits. It keeps a saveable TextFieldValue (text and selection), a baseline for discard detection, a pinned Save action, inline save errors, and a single in-flight request. Save callbacks suspend until the repository commits; originals and revisions still use the existing repository operations. The parent keeps the open composer, selected day and entry across activity recreation. Insets account for system bars and the IME, and the compact layout hides secondary chrome in short landscape windows. Keyboard opening waits for the dialog window's focus instead of racing its creation. No database, backup or AI-provider changes were required.
