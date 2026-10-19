# Privacy and backups

Daybook is a personal app with no account, analytics SDK, or app-operated server.

## What stays on the phone

Original recordings, original transcripts, edits, photos, goals, confirmed activity, and generated review history live in app-private storage. Android protects that storage with the device's normal app isolation and device encryption; Daybook does not claim a separately encrypted Room database.

Voice transcription uses whisper.cpp and a local English base.en model. A one-time model download contacts Hugging Face. The app verifies SHA-256 `a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002` before installing the 147,964,211-byte file. The model uses CPU inference, with no dependency on Samsung's speech-service availability.

Photos are copied into private storage. Originals remain intact; upload copies are orientation-corrected, resized to at most 1280 pixels on the long edge, and encoded as JPEG without original EXIF metadata.

Original audio is retained until the associated note is deleted. There is no passive always-listening mode. Recordings start from a visible user action, show a foreground notification, and stop after at most 30 minutes. Interrupted captures preserve saved audio and offer transcription retry.

## What goes to OpenAI

After you enable cloud reviews, a requested day review sends that day's current note text, attached photo upload copies, active goals, and confirmed daily activity to `https://api.openai.com/v1/responses`. All attached daily photos are included, including for automatic nightly reviews. The app does not read the rest of your gallery. Audio is never included.

The default model is configurable `gpt-5.4-mini`. The request uses structured outputs, `store: false`, and no tools. Journal text/images are treated as source material rather than instructions. Referenced goal, photo, and entry IDs are validated locally. Generated goal updates require human confirmation.

Disabling cloud consent prevents new cleanup requests from starting. It cannot retract content from requests already sent. OpenAI's provider retention and abuse-monitoring rules can still apply; `store: false` is not a promise of zero retention. See [OpenAI's data controls](https://developers.openai.com/api/docs/guides/your-data) and [the model documentation](https://developers.openai.com/api/docs/models/gpt-5.4-mini).

The API key is entered by the user and encrypted at rest using an AES-GCM key generated in Android Keystore. It is sent only as the OpenAI authorization header and is excluded from exports/backups. It is not hardcoded, logged, or committed. This personal-key setup is for a private installation; a public app should use an authenticated server rather than distributing a shared API key.

## App lock

The optional app lock uses a strong biometric or device credential. It covers interactive access when the app returns from the background and blocks screenshots while enabled. It does not bind the credential-encryption key to biometric authentication, so configured background cleanup can work after the device's first unlock.

## Encrypted backups

A full backup contains a versioned JSON manifest and exact copies of referenced media inside an encrypted ZIP stream. Settings are included; API keys, Keystore keys, downloaded models, caches, and temporary files are not.

Format version 1 uses PBKDF2-HMAC-SHA256 (600,000 rounds and a random 16-byte salt), a 256-bit AES key, and independently authenticated 64 KiB AES-GCM segments. A random 8-byte nonce prefix plus the segment counter produces each 12-byte nonce. Counters and the format marker are authenticated. A mandatory authenticated terminal segment rejects truncated files. Decryption processes bounded segments rather than buffering an entire media archive in memory.

Restore authenticates the complete archive into private cache, validates the version, IDs, references, paths, media lengths and available checksums, then copies media to new filenames before replacing database records in a transaction. Invalid credentials, corrupted archives, unsupported versions, or unsafe paths are rejected before database replacement. App lock is disabled after restore so it can be configured on the new device. Existing local API credentials are retained separately.

Safeguards limit the manifest to 32 MiB, each attachment to 1 GiB, total extracted media/archive to 8 GiB, and ZIP entries to 100,000. These are implementation limits, not silently truncated backups.

Deleting a note removes its original media, revisions, and generated reviews for that day. Confirmed goal activity remains, with deleted source links removed. Exported files and previously created backups are independent copies and are not deleted by the app.

Android cloud backup and device-transfer rules exclude app data. Uninstalling therefore removes the local journal. Make your own encrypted backups regularly and retain their passphrases.
