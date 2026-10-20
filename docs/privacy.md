# Privacy and backups

Daybook is a personal app with no account, analytics SDK, or app-operated server.

## What stays on the phone

Original recordings, original transcripts, edits, photos, goals, confirmed activity, and generated review history live in app-private storage. Android protects that storage with the device's normal app isolation and device encryption; Daybook does not claim a separately encrypted Room database.

Live transcript drafts, to-dos, ideas, and proposed new items are also stored locally. Live speech processes short audio windows on the phone; it does not stream audio to a provider.

Voice transcription uses whisper.cpp and a local English base.en model. A one-time model download contacts Hugging Face. The app verifies SHA-256 `a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002` before installing the 147,964,211-byte file. The model uses CPU inference, with no dependency on Samsung's speech-service availability.

Photos are copied into private storage. Originals remain intact; upload copies are orientation-corrected, resized to at most 1280 pixels on the long edge, and encoded as JPEG without original EXIF metadata.

Original audio is retained until the associated note is deleted. There is no passive always-listening mode. Recordings start from a visible user action, show a foreground notification, and stop after at most 30 minutes. Interrupted captures preserve saved audio and offer transcription retry.

## On-device daily reviews

Selecting **On-device** uses Qwen 2.5 1.5B through llama.cpp on the CPU. It processes journal text, existing goals, confirmed daily activity, and open list titles within the app process. No API key is read and no journal content is sent to a provider in this mode. Photos remain attached and are not analyzed. A photo-only day needs text or a caption. The app never falls back to cloud review automatically.

The separate 1,117,320,736-byte review model is downloaded from Qwen’s public Hugging Face repository after you tap Download. Its pinned SHA-256 is `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e`. Downloading contacts the host but does not send journal content. The model and partial downloads are excluded from encrypted backups and Git, and can be removed in Settings without deleting notes or reviews.

## What goes to OpenAI

When OpenAI is the selected provider and you enable cloud reviews, a requested day review sends that day's current note text, attached photo upload copies, active goals, and confirmed daily activity to `https://api.openai.com/v1/responses`. All attached daily photos are included, including for automatic nightly reviews. The app does not read the rest of your gallery. Audio is never included.

The request also includes the title and kind of open, unarchived to-dos and ideas to help avoid duplicate suggestions. Their details are not sent as list context, although details included in that day’s notes are part of the journal text. New goals, to-dos, and ideas require approval before creation, separately from confirming goal progress or calorie estimates.

The default model is configurable `gpt-5.4-mini`. The request uses structured outputs, `store: false`, and no tools. Journal text/images are treated as source material rather than instructions. Referenced goal, photo, and entry IDs are validated locally. Generated goal updates require human confirmation.

Disabling cloud consent prevents new OpenAI reflections requests from starting. Selecting on-device reviews reconfigures unfinished jobs without a network requirement. It cannot retract content from requests already sent. OpenAI's provider retention and abuse-monitoring rules can still apply; `store: false` is not a promise of zero retention. See [OpenAI's data controls](https://developers.openai.com/api/docs/guides/your-data) and [the model documentation](https://developers.openai.com/api/docs/models/gpt-5.4-mini).

The API key is entered by the user and encrypted at rest using an AES-GCM key generated in Android Keystore. It is sent only as the OpenAI authorization header and is excluded from exports/backups. It is not hardcoded, logged, or committed. This personal-key setup is for a private installation; a public app should use an authenticated server rather than distributing a shared API key.

## App lock

The optional app lock uses a strong biometric or device credential. It covers interactive access when the app returns from the background and blocks screenshots while enabled. It does not bind the credential-encryption key to biometric authentication, so configured background cleanup can work after the device's first unlock.

## Encrypted backups

A full backup contains a versioned JSON manifest and exact copies of referenced media inside an encrypted ZIP stream. Settings are included; API keys, Keystore keys, downloaded models, caches, and temporary files are not.

Lists, proposal decisions, and their supporting entry references are included. Daily Markdown export includes list items linked to the exported day’s notes, but is not a full list or media backup.

Format version 1 uses PBKDF2-HMAC-SHA256 (600,000 rounds and a random 16-byte salt), a 256-bit AES key, and independently authenticated 64 KiB AES-GCM segments. A random 8-byte nonce prefix plus the segment counter produces each 12-byte nonce. Counters and the format marker are authenticated. A mandatory authenticated terminal segment rejects truncated files. Decryption processes bounded segments rather than buffering an entire media archive in memory.

Restore authenticates the complete archive into private cache, validates the version, IDs, references, paths, media lengths and available checksums, then copies media to new filenames before replacing database records in a transaction. Invalid credentials, corrupted archives, unsupported versions, or unsafe paths are rejected before database replacement. App lock is disabled after restore so it can be configured on the new device. Existing local API credentials are retained separately.

Safeguards limit the manifest to 32 MiB, each attachment to 1 GiB, total extracted media/archive to 8 GiB, and ZIP entries to 100,000. These are implementation limits, not silently truncated backups.

Deleting a note removes its original media, revisions, and generated reviews for that day. Confirmed goal activity remains, with deleted source links removed. Exported files and previously created backups are independent copies and are not deleted by the app.

Android cloud backup and device-transfer rules exclude app data. Uninstalling therefore removes the local journal. Make your own encrypted backups regularly and retain their passphrases.

## Beta recovery and publishing changes

Per-entry on-device and photo-inclusion choices now control future OpenAI reflections. Manual cleanup previews its eligible content, timestamps, and context. Context derived from private source links is excluded; independently entered goal/list titles remain context. These controls cannot retract an already sent request.

Recently Deleted hides notes/media and source-linked reviews for 30 days. Restoration recovers originals and links; permanent deletion removes media and that day's generated reviews. Confirmed goal activity remains. Expired trash is purged by approximate background maintenance. Full backups include trash; independent backup/export/provider copies are not deleted by live-journal deletion.

Scheduled backups use the same passphrase-encrypted archive as manual full backups and a persisted, user-granted document-tree URI. The explicitly remembered passphrase uses a separate Android Keystore secret; schedule state, folder permissions, and that secret are not exported. Background work may be delayed; the UI records last success and a clear failure. Each backup creates a new document and previous successful backups are retained on failure. Seven tracked successful files are retained.

Backup manifests now use version 2; version 1 remains readable. Credentials and model files remain excluded. Restore remaps private media paths before its database commit; concurrent recording creation, imports, permanent deletion, and backup/restore share a media operation lock. Transcription rejects stale attachment paths after restore.

Feedback is a draft shared through a user-selected app. No diagnostic upload is automatic. A public release needs real developer/contact details and a hosted policy; the local beta includes the readable policy and a publishing gate that rejects missing metadata.

Recording discard is permanent for the current unfinished capture: its audio, live text and draft revisions are removed after the microphone closes. Earlier notes and reflections remain. Ordinary deletion of a saved note still uses Recently Deleted.
