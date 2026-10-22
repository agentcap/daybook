# Verification

Current release: **0.6.1-beta.1**. Earlier release results are retained below for context.

## 0.6.1 A focused writing view

Completed on 2026-10-21 in the isolated Android 16/API 36 x86-64 emulator:

- New notes use a full-screen composer with a date, spacious scrollable text area, sentence keyboard options, pinned Save, word count, and keyboard show/hide control. Secondary chrome and the field label collapse in short landscape windows so the active line remains usable above the IME.
- The editor waits for its own dialog window to gain focus before showing the keyboard. Its controls use the dialog's keyboard connection. Text/cursor, the selected date, and an open composer survive activity recreation. Close asks before discarding changed drafts.
- Saved entries open the same composer through Edit note or Edit photo note; the action appears above long content. Repository saves preserve originals and revisions. Save callbacks await completion, disable repeat submission, and keep text visible with an inline error after failure. Ctrl+Enter is also handled for hardware keyboards.
- All 15 affected Android workflows passed together in 57.272 seconds: four new editor tests, existing journal/goal/search and original-note access, both widget shortcuts, all three recording-transcript editor cases, and calendar/stats/list workflows. After the compact layout adjustment, all four editor cases passed again in 19.747 seconds, including actual typing at the retained cursor after rotation.
- Checks cover multiline draft/recreation/rotation, an actually visible software keyboard, show/hide controls, accessible Save, unsaved close/keep/discard, corrected saved notes and retained originals, and inline error recovery with duplicate in-flight save prevention. The save-error case is a simulated callback failure, not an actual low-storage test.
- Screenshots 34–36 show portrait typing, landscape typing, and a dark-theme simulated save failure with synthetic text. Rotation/window animations were allowed to finish before capture. Physical Samsung/Pixel keyboards, hardware Ctrl+Enter, TalkBack and extreme font scaling were not independently verified in this update.
- All 55 JVM tests passed (38 core, 17 app). Android lint reports 0 errors and the existing 43 warnings. Verification/test APK, signed personal APK and signed AAB builds passed.
- APK/AAB signatures, bundletool validation, private-file exclusions, ZIP alignment and all 16 native libraries' 16 KiB ELF alignment passed. The signed APK upgraded personal 0.6.0-beta.1 without uninstalling and passed a cold launch.
- No changes to speech engines, model downloads, AI providers, storage schema, backup format, or schedules. Prior model/backup results apply and were not repeated. GitHub remains source-only.

APK: releases/daybook-0.6.1-beta.1.apk, 158,635,654 bytes; SHA-256 80d511bb514c8ad48ad35e24074ecc3f525f32e2240af8eabf03508dfcbae333.

AAB: releases/daybook-0.6.1-beta.1.aab, 65,183,323 bytes; SHA-256 f01f1f3a0b8849a906f5f930ad8383873233e51984bcd93e29b28f4ecb4e4613.

## 0.6.0 Selectable offline speech

Completed on 2026-10-21 in the isolated Android 16/API 36 x86-64 emulator:

- Added Moonshine v2 Small, Moonshine v2 Medium, and Parakeet TDT 0.6B v2 int8 alongside Whisper base.en. Settings saves the selection immediately; each has separate download progress, resume, readiness and removal controls. Only Whisper has optional live previews. All choices transcribe after saving.
- Every component was downloaded on the build host and verified. Moonshine files matched the upstream catalog's CRC32C before SHA-256 pinning; Parakeet binaries matched upstream LFS hashes. The Android production downloader verified every component before inference. A missing Moonshine tokenizer was resumed from a partial file against its actual HTTPS host.
- Real production transcription workers ran all four choices twice in opposite loading orders with airplane mode enabled and no active network. All recognized the JFK fixture, kept its source audio, preserved original/current revisions, honored their queued model despite a different default setting, and refused to overwrite subsequently saved corrections.
- New engines passed two-window audio-offset checks, cancellation, native session cleanup and reuse. The longer fixture contains speech before and after the 30-second boundary. These are correctness checks, not accent/noise accuracy or sustained phone benchmarks.
- The combined instrumentation run passed 41 enabled workflows (54 reported cases, 13 opt-in cases skipped) in 179.828 seconds. It included the three actual-model cases, four speech-choice cases, recording/discard/edit controls, widgets, goals, search/calendar/stats, privacy/trash, and encrypted backup/restore. A recording kept Moonshine after Settings switched to Parakeet. Backup/restore retained the selected model and live-preview preference while excluding model files and credentials.
- Initial diagnostic runs caught stale shared state in the immediate-discard test and a physical-click race in history; tests now reset that request state and use the semantic click. Network-dependent opt-in checks wait for validated connectivity after airplane-mode restoration.
- Final compatibility review excluded engine provenance from content fingerprints, preserving existing 0.5.3 review and proposal hashes. All 55 JVM tests passed (38 core, 17 app), including known pre-upgrade hash fixtures. Seventeen relevant Android workflows were rechecked afterwards; one had a transient Espresso/Compose measure-layout exception, and all five workflows in that class passed an isolated rerun (12.471 seconds). The other sixteen checks passed.
- Android lint: 0 errors, 43 warnings. Verification/test APK, signed personal APK, and signed AAB builds passed. APK/AAB signatures, bundletool validation, credential/model exclusion, ZIP alignment and all 16 native ELF libraries' 16 KiB alignment passed.
- The signed APK installed over personal 0.5.3-beta.1 without uninstalling and launched successfully. Screenshot 33 shows the actual model chooser and downloaded Parakeet status. GitHub remains source-only.

APK: releases/daybook-0.6.0-beta.1.apk, 158,586,506 bytes; SHA-256 b90ab837e16850eb88911f76a0a23b1d769e539d487540c072a744badcf72b53.

AAB: releases/daybook-0.6.0-beta.1.aab, 65,166,688 bytes; SHA-256 218cb6e0600e31273cc2a6d14b5cd6253aac2fcc8fdd1aae358eda89b5f0f8c1.

Physical Galaxy/Pixel ARM64 loading, microphone quality, memory/thermal/battery behavior, sustained long-note speed, actual phone downloads and scheduling remain unverified. This release did not repeat live OpenAI calls, physical biometrics, or local Qwen inference; review providers and their native code are unchanged. See [model pins and verification instructions](speech-models.md) and the [phone checklist](../publishing/device-testing.md).

## 0.5.3 Edit the saved recording transcript

Completed on 2026-10-19 in the isolated Android 16/API 36 x86-64 emulator:

- After Save recording, Edit transcript opens a keyboard-aware editor on the same screen. Save changes commits corrections; Cancel leaves the saved note unchanged. Closing with unsaved edits asks before discarding them. An empty or failed transcript can be filled in manually.
- The editor keeps its own draft, including across activity recreation, so a final transcript arriving during typing does not reset the field. Saving uses the existing revision-preserving repository operation and its ready-status protection against subsequent transcription.
- All nine affected Android workflows passed together in 27.357 seconds: three new editor cases, three discard cases, background/reopened recording controls, and both Note & Voice widget actions. The three editor cases passed again with enabled-button assertions and synchronized screenshots (14.059 seconds).
- Tests checked correction persistence, unchanged audio digest, retained original transcript and revision, stale reflection fingerprint, queued-worker protection, incoming final text during editing, activity recreation, cancel/keep-editing behavior, blank-text save prevention, and adding text when transcription is unavailable.
- New editor tests use actual emulator microphone capture and Save recording, then synthetic transcript fixtures to isolate editing from native inference latency. Their incoming-transcript case simulates the repository update. Native inference behavior and phone accuracy are not established by these UI fixtures.
- All 47 JVM tests passed (38 core, 9 app). Android lint reports 0 errors and the existing 40 warnings. Verification/test APK, signed personal APK, and signed AAB builds passed.
- APK/AAB signatures, bundletool validation, private-file exclusions, and 16 KiB ZIP/native ELF alignment passed. The signed release upgraded personal 0.5.2-beta.1 and cold-launched successfully.
- Screenshots 31 and 32 show the actual inline editor with the keyboard and the saved correction, using synthetic text. No changes to transcription/native engines, scheduling, providers, backup formats, or database schema; prior real-model/backup results remain applicable and were not repeated here.

APK: releases/daybook-0.5.3-beta.1.apk, 67,082,864 bytes; SHA-256 9afb13310710afdc24f8ad8f260ad28800e64e3cfa0f46c8cc9059cc8db1556c.

AAB: releases/daybook-0.5.3-beta.1.aab, 27,646,574 bytes; SHA-256 4ec05520021e830a9275af75a41f9d52353c5e430e316e338a061b1bc7e60bb1.

Physical Galaxy/Pixel testing and live OpenAI validation remain pending. GitHub distribution remains source-only.

## 0.5.2 Note & Voice widget

Completed on 2026-10-19 in the isolated Android 16/API 36 x86-64 emulator:

- Added a separate dark 2 × 1 Note & Voice widget. The existing 1 × 1 Record tile and 3 × 1 capture strip remain available. Both shortcuts use the existing authenticated, user-initiated capture flow.
- The two new Android workflows passed: Write opens the editor and saves today's note without microphone capture; Record starts immediately and repeated taps keep one recording. Provider registration and the requested 2 × 1 footprint were checked.
- All five affected Android workflows passed together in 13.533 seconds, including existing direct-record/recreation and Settings navigation/key-status checks. The existing key-status test uses accessibility button activation after keyboard dismissal to avoid focus/scroll pointer races. Earlier diagnostic failures remain in ignored verification/ logs.
- Actual launcher pinning from Settings passed. Both buttons were tapped on the installed home-screen widget: Write opened the editor; Record started capture. The temporary recording was discarded. The new widget remained readable at 200% system text size, and the emulator's font setting was restored afterwards.
- All 47 JVM tests passed (38 core, 9 app). Android lint reports 0 errors and the same 40 warnings as the previous release.
- Verification/test APK, signed personal APK and signed AAB builds passed. APK/AAB signatures, bundletool validation, private-file exclusions, and 16 KiB ZIP/native ELF alignment passed.
- The signed release upgraded the existing personal 0.5.1-beta.1 installation and cold-launched successfully. Screenshot 11 shows the new widget Settings page; screenshot 30 shows the actual launcher widgets.
- No native inference, recording engine, scheduling, provider, backup, or data-format changes. Broader inference/backup checks from earlier releases were not repeated for this widget update.

APK: releases/daybook-0.5.2-beta.1.apk, 67,050,096 bytes; SHA-256 db43162f1b9b579d664cd3cfcb254c73bcd0d4948890b444ca5213252397ee4e.

AAB: releases/daybook-0.5.2-beta.1.aab, 27,638,897 bytes; SHA-256 a68cf39a9e823fa905c08c36b3c6a9dfb11ee841da98b7945350f001fa85fe78.

Physical Galaxy/Pixel launcher, fingerprint and microphone behavior remain unverified. GitHub remains source-only; APK/AAB artifacts stay local.

## 0.5.1 recording discard and daily reflections

Completed on 2026-10-19 in the isolated Android 16/API 36 emulator:

- All 47 JVM tests passed. The Android runner reported OK (42 tests): 29 regular workflows passed and 13 opt-in cases were skipped.
- New recording cases checked Keep recording versus confirmed Discard, paused capture, removing the audio/live draft/revisions, preserving an earlier note and daily reflection, and not queuing final transcription.
- Immediate cancellation during startup leaves no note or audio. A subsequent recording saves successfully. Saved voice notes cannot be removed through the unfinished-recording operation.
- Recorder completion is serialized with new service commands. Existing widget duplication and activity-recreation tests passed; an incoming widget tap while recording only reopens the active capture.
- Reflection labels passed in journal, settings, source preview and provider selection. Stored schedule fields and WorkManager identifiers are unchanged.
- Android lint: 0 errors and 40 warnings. Verification APK/test APK, signed personal APK and signed AAB builds passed.
- APK/AAB signatures and bundletool validation passed. The update retains the existing signing identity. APK ZIP and every native ELF load segment retain 16 KiB alignment.
- Final screenshot tour passed in 22.054 seconds; actual recording and discard confirmation screens were inspected. Save/Discard remained reachable with 200% text, with a shortened Save label and full accessibility description. The 3 discard workflows passed again after that UI polish (10.034 seconds).
- The signed release upgraded personal 0.5.0-beta.1 on the emulator and cold-launched successfully.
- Native inference, OpenAI wire format and archive format are unchanged. The broader real-model and automatic-backup results from 0.5.0 below were not rerun for this update.

The final regular Android suite completed in 77.867 seconds. Local test logs are under ignored verification/. Earlier failing development runs are retained there; the final result above is the passing run.

APK: releases/daybook-0.5.1-beta.1.apk, 67,028,823 bytes; SHA-256 7b74f42fc729dbc64d775af097cca68ae3962079e6d22eb96ff41993ec27d8a8.

AAB: releases/daybook-0.5.1-beta.1.aab, 27,634,816 bytes; SHA-256 3427255cde716b81219b2c0d96387a5571f4714b9f58e9d2bf93a2fcb71a0c72.

Physical Galaxy validation and a live OpenAI request remain pending. GitHub distribution remains source-only; APK and AAB files stay local.

## 0.5.0 beta publishing and recovery verification

Completed on 2026-10-19 in the isolated Android 16/API 36 x86-64 emulator. This is a locally installable beta, not a public store submission.

| Check | Actual result |
| --- | --- |
| JVM tests | 47 passed: rules, backward-compatible data defaults, recovery, privacy selection, archive integrity, HTTP, and authentication lifecycle |
| Regular Android workflows | 26 passed; runner reports 39 including 13 skipped opt-in cases |
| Final interface regression | All 5 release workflows passed again after final UI polish: pause/bookmark/resume, trash/restore, weekly reflection, private cloud preview and diagnostics, accessible privacy switches |
| Real offline speech | All 5 opt-in cases passed, including checksum-verified base.en, reusable live previews, timestamped audio after a 30-second silent window, cancellation and runtime reuse |
| Real on-device reviews | All 4 opt-in cases passed with actual Qwen inference in airplane mode; confirmation and original preservation checked; Whisper also works after local inference |
| Automatic encrypted backup | Real DocumentsUI folder grant; audio, photo, trash, privacy flags, bookmarks, segments and weekly reflection restored successfully; wrong password left existing data unchanged |
| Backup failure and retention | Seven latest tracked backups retained; latest copy decrypts; missing saved passphrase fails while preserving last-success state and previous copies |
| Large journal | 1,000 synthetic entries persisted and observed in 276 ms on this emulator; specific-note search, source opening, and weekly reflection passed |
| Visual checks | Final screenshot tour passed; weekly, backup, processing, privacy and feedback screens inspected; actual recording controls checked at 200% system text size |
| Android lint and builds | 0 errors, 40 warnings; unit tests, verification APK/test APK, signed release APK and signed AAB builds passed |
| Upgrade installation | Final signed beta installed over personal 0.4.1 and cold-launched successfully; version checked |
| Package integrity | APK signature and AAB signature verified; official bundletool validation passed; non-debuggable, automatic Android backup disabled |
| Native compatibility | Both ARM64 and x86-64; APK 16 KiB ZIP alignment and every native ELF load segment in both packages checked |
| Packaged privacy | Privacy policy and native/library notices included; no downloaded models, backup archives or signing credentials packaged |

The last five-workflow run took 13.635 seconds; the final screenshot tour took 22.099 seconds. Real speech cases took 51.533 seconds in total, local-model cases 56.678 seconds, and the actual automatic-backup test 19.344 seconds. These small synthetic fixtures are development checks, not Galaxy performance claims. No live OpenAI request was made.

At 200% font size, capture, pause/resume, bookmark and stop controls remained usable. Long bottom-navigation labels use ellipses while retaining their full accessibility labels. TalkBack, landscape, physical audio routing, fingerprint hardware, thermal behavior and Samsung scheduling still need the [device beta protocol](../publishing/device-testing.md). Folder revocation, interrupted writes and cloud document providers remain device/provider checks; the emulator automatic-backup failure case removed its saved secret.

Original audio and photos remain preserved. Soft deletion hides notes and associated reviews while keeping recovery data for 30 days; background maintenance timing depends on Android. Encrypted backups use manifest version 2 to preserve the added metadata and reject imports by older apps.

Current artifacts:

- APK: `releases/daybook-0.5.0-beta.1.apk`, 67,012,363 bytes; SHA-256 `284a302f1831096428d9bb6c9490b3e05f6517bee2a31aea0780f33e9b6e4ac4`.
- AAB: `releases/daybook-0.5.0-beta.1.aab`, 27,626,151 bytes; SHA-256 `b024b167bf098bd1f74cb2c9d9019ca79d92e6df8f7434d6036d9be40a8ef4da`.
- Signing certificate SHA-256: `b573b6ab7c5556efbbaf253b61db2d3d8e8fb3ba2db9519a883f042070d3b87f`.

The release keeps the personal signing identity. Play enrollment and package-name availability have not been checked. Public developer name, support email and hosted privacy policy are pending. The publishing checker deliberately reports NOT READY until metadata and a final policy site are supplied; phone and Play Console requirements must also be completed.

Repeat the actual automatic-backup test only after configuring a folder and synthetic passphrase through the **verification app's** Backups & recovery screen on a disposable emulator:

```sh
adb -e shell am instrument -w -e realBackup true \
  -e class dev.daybook.app.RealBackupTest \
  dev.daybook.app.verification.test/androidx.test.runner.AndroidJUnitRunner
```

The test replaces verification data and disables its automatic-backup setup afterwards. Real speech, local-model and screenshot tests remain opt-in. Local logs live under ignored `verification/`; screenshots contain synthetic content. The actual recording screenshot contains emulator audio with no detected speech; the separate recording preview fixture is kept only in ignored test output.


## 0.4.1 compact dark widget verification

Completed on 2026-10-19 in the isolated Android 16 x86-64 emulator:

- The existing `CaptureAndSettingsTest` suite passed all 3 checks, including direct recording without duplication, recreation, focused settings, and credential save/removal.
- The actual home-screen Record widget updated to the dark appearance. Launcher resize reduced the old three-column instance to one cell; an actual tap started recording and **Stop & save recording** saved it.
- The system pin dialog showed **1 × 1**, and a freshly added widget occupied one column. The widget picker showed both the dark **1 × 1 Record** tile and the dark **3 × 1 Record/Write/Photo** strip without clipped controls. Three widget/settings screenshots were inspected and saved.
- Android lint passed with 0 errors and 34 warnings. The three additional button-bar style advisories concern the deliberately filled capture-widget actions.
- Release and verification APK builds passed. The signed, non-debuggable release retained the existing signing certificate and passed 16 KiB ZIP alignment. It upgraded the personal 0.4.0 emulator package and cold-launched successfully.
- No journal, inference, storage, or recording logic changed. The wider 0.4.0 checks below were not rerun for this widget styling update. Physical One UI sizing remains a phone check; existing widgets can retain their launcher-assigned footprint until resized or re-added.

APK: `releases/daybook-0.4.1.apk` (66,736,501 bytes).

SHA-256: `b514b8046ac664b3c59e844e71d9aaccb7632ff4f0f40fdef674926edb3cb6f0`.


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
