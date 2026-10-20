# Daybook beta artifacts

`daybook-0.5.1-beta.1.apk` is the signed, non-debuggable installable Android beta. `daybook-0.5.1-beta.1.aab` is the signed Google Play upload bundle; an AAB is not directly installable. Both include ARM64 and x86-64 native libraries. Binaries remain local and ignored by Git; the source and SHA256SUMS record are committed.

Install the APK over an earlier signed personal version to preserve data. Keep the signing key at `.tools/signing/daybook.jks` and its password file on the build machine for updates. Changing signing identity requires uninstalling the existing app; export a full encrypted backup first.

Models download separately. Speech needs about 141 MB; optional text-only local reviews need about 1.1 GB. OpenAI reviews require your own API key and explicit cloud consent.

New beta features include daily encrypted folder backups, 30-day Recently Deleted, recording pause/resume and audio bookmarks, timestamped transcript playback, weekly reflections, private-note/photo controls, cloud previews, processing retries and reduced animations. See [verification](../docs/verification.md), [setup](../README.md) and [publishing requirements](../publishing/README.md).

This is not yet a public store release. Developer contact details, a hosted final privacy policy, physical Galaxy testing and applicable Play Console testing/declarations remain outstanding.

0.5.1 adds confirmed recording discard and clearer daily reflection terminology. Install over 0.5.0-beta.1 without uninstalling to preserve notes and settings.
