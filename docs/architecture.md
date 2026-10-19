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

WorkManager serializes transcription per entry. A process-wide mutex serializes native inference calls. JNI loads the pinned whisper.cpp model and handles 30-second audio chunks with a basic silence-energy gate. Originals are never sent to a speech service. Quiet/noisy input may still need manual correction, particularly at chunk boundaries.

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
