# Physical device beta protocol

Status: NOT RUN on a physical Galaxy S26 Ultra. Emulator results are recorded separately in docs/verification.md.

Use a backup and the isolated verification app for destructive tests. Never erase a real journal to test recovery.

Record device model, Android/One UI version, app version, power settings, and actual results:
1. Record English speech offline with names/numbers/noise; compare live draft, final text, and original audio.
2. Pause/resume several times, add bookmarks, seek through transcript segments, and confirm pause time adds no silence to the saved recording.
3. Record for 30 minutes, lock the screen, switch apps, receive a phone call, and connect/disconnect wired/Bluetooth microphones. Confirm any interruption preserves audio and never silently counts inaccessible microphone input as success.
4. Measure local reflections time, peak memory, battery change, and heat for short/long days. Repeat with an empty/missing model and low storage. Keep manual capture usable.
5. Test reminders, quiet hours, snooze, reboot, time-zone changes, Doze, and Samsung sleeping-app controls over several days.
6. Test fingerprint auto-prompt, cancellation/retry, screenshot blocking, and One UI Record widget add/resize/tap while locked and unlocked.
7. Round-trip a scheduled encrypted backup to local and a chosen cloud document provider. Revoke folder access, disconnect storage, interrupt a write, and check the last good backup remains usable. Verify wrong passwords fail before changing the journal.
8. Trash/restore notes with original audio/photos/reviews; permanently delete a disposable note; verify expired trash maintenance and confirm independent exported backups still contain their earlier copies.
9. Verify cloud previews and private-note/photo exclusions with disposable content, plus invalid keys, offline requests, provider switching, and user-confirmed suggestions. A real paid OpenAI request requires a user-provided key.
10. Test TalkBack, 1.5×/2× system fonts, reduced animations, dark mode, portrait/landscape, and one-handed capture.
11. Import or seed a large disposable journal (500–1,000 entries). Measure cold start, day scrolling, search, calendar, weekly reflection, and backup time.
12. Upgrade the signed release over the previous version and verify data/settings remain intact.

Closed-beta feedback log:
| Date | Device / build | Scenario | Result / issue | Resolution |
|---|---|---|---|---|

Only replace NOT RUN with measured results. A passing build or emulator suite does not verify Samsung microphone hardware, fingerprint sensors, thermal behavior, Bluetooth routing, or background delivery.


## 0.6 speech choices

On physical Samsung and Pixel phones, download each selected model from Settings, verify airplane-mode final transcription, change selection during capture (recording keeps its initial choice), retry a saved note with another model, inspect earlier text/audio, cancel/resume downloads, remove/re-download each model, and restore model preferences from an encrypted backup. Check Whisper preview on/off, all after-save choices with the screen locked, interruption/low-storage recovery, thermal/memory use and 30-minute notes. Model test cases passed on x86-64 only; physical ARM64 inference is not yet verified.
