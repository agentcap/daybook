# Google Play release preparation

The local beta can be built and tested now. Production submission remains gated on publisher details, hosted privacy policy, phone testing, and the Play Console closed test.

1. Copy release.properties.example to release.properties and fill your public developer name, support email, and HTTPS privacy-policy URL.
2. Run `python3 scripts/prepare-play-release.py --prepare-site`. Host the generated `publishing/site/` files at your chosen website, then confirm the policy URL opens publicly.
3. Build the signed APK and Android App Bundle:
   `bash scripts/build.sh :core:test :app:testVerificationUnitTest :app:lintDebug :app:assembleRelease :app:bundleRelease`.
4. Run `python3 scripts/prepare-play-release.py --check` to validate metadata, policy, source configuration, and release artifacts.
5. Follow device-testing.md, Play-declarations.md, and store-listing.md. Record actual device results and tester feedback. Do not substitute emulator checks for physical microphone, fingerprint, Bluetooth, or Samsung scheduling checks.
6. Confirm the public app name and availability of the current package ID, `dev.daybook.app`, in your publisher account. Changing package ID creates a separate installation; export/restore is then needed for personal data.
7. Enroll the app in Play App Signing. Keep the current signing key safe so existing personal installs can upgrade; choose the signing-key migration strategy deliberately before first upload.
8. Upload the AAB to an internal/closed test after reviewing the package. This repository does not upload or publish automatically.

Official references (checked October 19, 2026):
- [App Bundles](https://developer.android.com/guide/app-bundle)
- [User data and privacy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)
- [New personal account testing](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en)
- [AI policy scope](https://support.google.com/googleplay/android-developer/answer/14094294?hl=en)

For personal developer accounts created after November 13, 2023, Google currently requires at least 12 testers continuously opted in for 14 days before applying for production access. Gather meaningful feedback and address defects during that test. App review and approval are external steps, not guaranteed by a successful build.
