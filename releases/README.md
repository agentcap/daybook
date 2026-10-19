# Personal installation artifact

`daybook-0.1.0.apk` is copied here after successful build and verification. APK binaries are local artifacts and are ignored by Git; source and the checksum record are committed.

This is a signed, non-debuggable personal APK for personal installation, with ARM64 and x86-64 native libraries. Keep the signing key at `.tools/signing/daybook.jks` and `.tools/signing/keystore.pass` on the original build machine for future updates. Changing the signing key requires uninstalling the old app, which removes its journal unless you export a full encrypted backup first.

The app downloads its speech model separately and accepts the user's own OpenAI key in Settings.
