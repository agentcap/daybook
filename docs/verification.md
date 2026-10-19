# Verification

Current release: 0.4.0. Earlier release results are retained below for context.

## 0.4.0 on-device review verification

Completed on 2026-10-19 in the isolated Android 16 x86-64 emulator:

| Check | Result |
| --- | --- |
| JVM rules, local protocol, legacy data, client failures, archive, HTTP, and authentication lifecycle | 40 tests passed (31 core, 9 app) |
| Regular Android workflows | 21 passed (runner reports 30 including 9 skipped opt-in cases) |
| Actual local model | All 4 opt-in checks passed: verified Qwen download; airplane-mode cleanup with new goal, to-do, and idea proposals; completed goal activity; future plans excluded from progress |
| Original content and confirmation | Original text retained; suggestions remain pending and do not change confirmed goal counts automatically |
| Runtime coexistence | Actual whisper.cpp transcription passed after local LLM inference in the same process |
| Provider switching | Local mode needs neither a key nor cloud consent; queued cloud work is replaced with the selected provider and appropriate network constraints; missing models fail clearly without changing originals |
| Visual review | 19 app screenshots refreshed; the two new provider/model settings screens inspected; earlier actual home-screen widget screenshot retained |
| Android lint | 0 errors, 31 warnings |
| Signed release | Non-debuggable, same signing certificate as earlier releases; ARM64 and x86-64; 16 KiB ZIP and all native ELF load alignment verified |
| Native isolation | Both local LLM libraries export only their JNI entry point, keeping their ggml symbols separate from whisper.cpp |
| APK contents | Includes the runtime license; excludes downloaded models, backup archives, and signing credentials |
| Update installation | Installed over the personal 0.3.0 emulator package and cold-launched successfully |
| Installer | Windows installer syntax passed |

APK: `releases/daybook-0.4.0.apk` (66,736,393 bytes).

SHA-256: `96f7cf899dff5c73522f4dcf4b753c6c704db99c5f8a390123596c3e69e066bc`.

The actual mixed-note fixture completed its local review in about 17.6 seconds on this emulator with airplane mode and no active network. All four real-model cases completed in 55.064 seconds with models already available for the final run. These are small synthetic fixtures, not phone performance or broad model-quality measurements. The settings screenshots show the actual downloaded model state; other displayed journal content and review suggestions in the screenshot tour remain fixtures.

The on-device model accepts text only. Photos remain attached but are not analyzed; a photo-only day needs a caption or an explicit switch to OpenAI. Local generation has input/output and time limits and can take minutes on larger days. Failures preserve originals and previous reviews. Follow [the on-device setup and opt-in test instructions](local-model.md) to repeat the real-model checks.

Physical S26 Ultra inference speed, memory use, thermals, microphone behavior, fingerprint hardware, One UI widgets, and Samsung background restrictions still require phone testing. No live OpenAI request was made.


## 0.3.0 update verification

Completed on 2026-10-18 in the isolated Android 16 x86-64 emulator:

| Check | Result |
| --- | --- |
| JVM rules, archive, mocked HTTP, and authentication lifecycle | 31 tests passed (24 core, 7 app) |
| Regular Android journal, planner, history, backup, recording, Settings, and widget checks | 17 passed (runner reports 22 including 5 skipped opt-in cases) |
| Real base.en model download/checksum and offline inference | All 3 opt-in speech checks passed, including full WAV transcription and reusable live-window inference in airplane mode |
| Recording interface | Timer/live status, minimize/reopen without a duplicate recording, stop/save, and retaining draft text after transcription failure passed |
| New goals, to-dos, and ideas | Manual list management; edited approval of all three types; no automatic goal progress; deduplication; stale evidence; undo; repeat intentions after a completed task passed |
| History | Calendar date navigation, source-note access, search, streak calculations, and statistics passed |
| Encrypted backup | Round trip with list items, proposal source fingerprints, audio, and photos passed; incorrect password and unsafe paths rejected |
| Visual review | 17 refreshed app screenshots inspected, including recording, calendar, stats, Lists, and light/dark themes; earlier actual home-screen widget screenshot retained |
| Android lint | 0 errors, 29 warnings |
| Signed release | Non-debuggable, ARM64 and x86-64, same signing certificate as 0.2.0; 16 KiB ZIP and native ELF alignment verified |
| Update installation | Installed over the existing personal emulator package and cold-launched successfully |

APK: `releases/daybook-0.3.0.apk` (56,511,253 bytes).

SHA-256: `697fdb65398352b20c660b3c067e560b64700d072973e8f3b7118ca6dba322e3`.

Live previews use three-second initial windows and six-second segments; their arrival depends on device performance. The actual native speech checks completed in 41.303 seconds in total with the upstream JFK fixture. That is not a measurement of physical microphone accuracy or Samsung latency. The recording screenshot uses synthetic transcript text, and the screenshot review/proposals use fixtures rather than a live OpenAI response.

Automatic authentication and direct widget behavior from 0.2.0 remain in place. The regular direct-record intent and configuration-recreation checks passed again. Physical Samsung fingerprint hardware, One UI launcher behavior, battery restrictions, and an actual paid OpenAI request still require user device testing.


## 0.2.0 update verification

Completed on 2026-10-18 in the isolated Android 16 x86-64 emulator:

| Check | Result |
| --- | --- |
| JVM rules, archive, HTTP, and authentication lifecycle | 22 tests passed |
| Regular Android journal, storage, recording, scheduling, Settings, and widget checks | 12 passed (runner reports 16 including 4 skipped opt-in cases) |
| Direct record widget intent | Passed: recording begins without a Start dialog; repeated taps do not duplicate audio; recreation does not replay capture |
| Automatic system authentication | Passed using a disposable emulator PIN: prompt appears automatically, cancellation stays locked, retry works, widget waits for unlock |
| Settings | Schedule and theme saves passed; API key status updates correctly on save and removal |
| Visual review | 11 app screenshots plus the actual 3 × 1 home-screen widget; light/dark contrast inspected |
| Actual launcher widget | Added through the system pin dialog; tapped on the home screen to start recording; stopped and saved from Today |
| Android lint | 0 errors, 29 warnings |
| Signed release | Signature and 16 KiB ZIP alignment verified; same signing certificate as 0.1.0 |
| Update installation | Installed over the existing personal package and cold-launched successfully |

The automatic prompt uses AndroidX BiometricPrompt with strong biometrics and device-credential fallback. The system credential flow was exercised; a physical Samsung fingerprint sensor and One UI launcher still require phone testing. Live OpenAI and phone-specific speech/performance limits from the initial release remain unchanged.

APK: `releases/daybook-0.2.0.apk` (56,295,541 bytes).

SHA-256: `602065aabc269b565e374d7f5c412146b94d59e98793adda828292f7f931c2a8`.

To repeat the opt-in authentication test, configure a six-digit test PIN on a **disposable emulator only**, then run `BiometricFlowTest` with instrumentation arguments `biometric=true` and `testPin=<test PIN>`. Clear the emulator's test PIN afterwards. The test cancels/retries actual Android credential screens and checks a locked widget launch; it never changes a phone's lock setup.

## Phone checks still required

The app has not been tested on a physical Galaxy S26 Ultra. Before relying on it daily:

1. Download the English model, enable airplane mode, and record several English notes with names, numbers, and background noise. Play back the preserved audio and compare the live draft and final transcript. Check how quickly previews appear and whether extended recording affects heat or battery life.
2. Start recording, lock the screen, and stop using the notification. Confirm capture and transcription survive normal background use.
3. Check reminders during waking hours, snooze, quiet hours, a reboot, and Samsung battery-saving/sleeping-app configurations. Approximate scheduling can be delayed by the OS.
4. Enter your own OpenAI API key, enable cloud reviews, attach a food photo, and run a daily review. Confirm source preservation and review a calorie suggestion before applying it.
5. Enable the app lock, switch away and return, and verify that the fingerprint prompt appears automatically. Add Daybook Record from the One UI widget picker and check one-tap recording with the app unlocked and locked. Confirm scheduled cleanup still works after the device's first unlock.
6. Select **Settings → AI daily reviews → On-device**, download the separate review model, and run cleanup in airplane mode. Check short and long days, goal/to-do/idea suggestions, battery use, and heat. Confirm photo-only days request text and that no cloud request happens automatically.
7. Export a full encrypted backup and test restoring it in the isolated verification app or after safeguarding existing data.

No live OpenAI key was supplied for development. Provider integration is checked with mocked HTTP responses; an actual paid cleanup request remains a user setup check.

## Reproduction

Normal checks:

```sh
bash scripts/build.sh :core:test :app:testVerificationUnitTest :app:lintDebug :app:assembleDebug
bash scripts/build.sh :app:connectedVerificationAndroidTest
```

The instrumentation package is `dev.daybook.app.verification`. Gradle may uninstall it after a connected run. To retain screenshots or the downloaded test model, install the verification APK and its test APK manually, then invoke instrumentation directly.

Screenshot tour:

```sh
bash scripts/build.sh :app:assembleVerification :app:assembleVerificationAndroidTest
adb -e install -r app/build/outputs/apk/verification/app-verification.apk
adb -e install -r app/build/outputs/apk/androidTest/verification/app-verification-androidTest.apk
adb -e shell am instrument -w -e screenshots true \
  -e class dev.daybook.app.ScreenshotTour \
  dev.daybook.app.verification.test/androidx.test.runner.AndroidJUnitRunner
adb -e pull /sdcard/Android/data/dev.daybook.app.verification/files/screenshots/ verification/screenshots/
```

Real speech checks:

```sh
adb -e shell am instrument -w -e realSpeech true \
  -e class dev.daybook.app.RealSpeechTest \
  dev.daybook.app.verification.test/androidx.test.runner.AndroidJUnitRunner
```

These checks download the actual 141 MB model if absent, verify its hash, disable emulator connectivity, transcribe the upstream JFK fixture locally using both full-recording and live-window paths, and restore connectivity. They are restricted to an emulator because they change airplane mode.

## Initial 0.1.0 result record

Completed on 2026-10-18:

| Check | Result |
| --- | --- |
| JVM rules, archive integrity, review validation, and mocked HTTP integration | 19 tests passed |
| Android workflow, storage, Keystore, recording, and scheduling integration | 9 regular checks passed on Android 16 x86-64 emulator |
| Actual speech model download and SHA-256 verification | Passed |
| Actual base.en transcription with airplane mode and no active network | Passed with the upstream JFK fixture |
| Screen-off recording and stop from foreground notification | Passed with emulator audio capture; physical microphone accuracy still needs phone testing |
| Missed nightly dispatch preservation during rescheduling | Passed regression check |
| Synthetic light/dark UI tour | Passed; 7 screenshots saved in docs/screenshots |
| Android lint | 0 errors, 29 warnings |
| Signed personal release | Built, signature verified, non-debuggable manifest confirmed |
| Native compatibility | ARM64 and x86-64 packaged; 16 KiB ZIP/ELF alignment verified |
| Release install/start | Installed and cold-launched successfully on the emulator |
| Bootstrap and installer | Bootstrap checked with existing SDK/JDK; shell/Python/PowerShell syntax checks passed |

The normal Android run reports 12 cases: nine regular checks and three skipped optional cases. The two optional real-speech cases and the screenshot case passed in separate opt-in runs.

Final APK: `releases/daybook-0.1.0.apk` (56,208,901 bytes).

SHA-256: `4cbe757692ebd1bedd72aa324240d5ae59210803d6ede2f00fc4cda7285b8fb4`.

Local logs are retained under ignored `verification/`. Screenshots use synthetic journal data and a drawn meal illustration; the displayed review is a fixture, not a live OpenAI response.

Physical S26 Ultra behavior and an actual OpenAI cleanup request remain unverified. The checks above do not establish phone-specific speed, thermal behavior, Samsung battery-policy timing, or live model quality.
