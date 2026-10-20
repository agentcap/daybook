# Beta feature guide

## Protecting your journal
Move a note to Recently Deleted from its note menu. Its original audio, photos, edits, and linked reviews are retained privately and hidden from normal browsing. Restore it in Settings → Backups & storage → Recently Deleted. Confirm permanent deletion there to free its media storage, or let background maintenance remove it after 30 days. Confirmed goal activity is independent.

Full backups include Recently Deleted. A past export is an independent copy; permanent deletion in the live journal does not erase it.

## Automatic encrypted backups
Open Settings → Backups & storage → Automatic backups & recovery. Enter and confirm a passphrase of at least ten characters, explicitly consent to remembering it encrypted on this phone, and choose a folder with Android's picker. The first backup queues immediately; subsequent backups run approximately daily when Android permits.

Keep the passphrase somewhere safe outside this phone. The schedule and saved passphrase do not migrate in a backup. Disable automatic backups to remove the remembered secret. Existing encrypted files remain in your folder.

The screen shows last successful backup and failures. Back up now retries; changing the folder/passphrase reconfigures the schedule. Old successful files are not overwritten on failure. Only files tracked as this schedule's own successful backups are considered for seven-copy retention. A document provider can sync encrypted files to its own cloud.

## Voice and processing
Pause/resume controls are available in the recording screen and notification. Pause time does not enter the audio timeline. Bookmark marks a captured-audio position; original notes show those bookmarks and approximate transcript segments with tap-to-play.

Microphone silencing can pause recording; interruptions preserve audio. Physical call/Bluetooth behavior remains part of phone testing. Saved audio can exist before a final transcript. Processing & downloads shows queued/running/failing jobs with cancellation and retry, plus model download states. Cancelling inference prevents its result from replacing saved content.

Wi-Fi-only downloads use Android's unmetered-network constraint and apply when starting/resuming downloads. Create reflections only while charging applies to manual and scheduled daily reviews. Approximate background timing remains subject to Android/Samsung restrictions.

## Cloud privacy
Inside each note, Keep this entry on-device excludes that note and its photos from future OpenAI reviews. Photo inclusion can be disabled separately. Local cleanup can still use the text. Manual OpenAI reflections previews current eligible text/photos plus goal, activity, and list-title context. Nightly reflections follows saved choices and explicit cloud consent. Entry times, zones, and record references support source links.

These controls do not retract past requests. Text entered independently as goals or list titles is still context. Derived records with private source references are excluded. OpenAI storage/retention caveats remain in the policy.

## Weekly reflection and feedback
History → Weekly reflection shows a Monday–Sunday window through today, journal days, photos, distinct confirmed goals, goal/day check-ins, linked moments, current open items, and your own saved reflection. Zero-amount confirmed effort counts as working on a goal; suggestions and future plans do not count.

Privacy policy & feedback contains the local policy, open-source notices, and an editable feedback draft. Diagnostic details are optional and shown before sharing. No notes, recordings, photos, credentials, paths, or identifiers are automatically attached.

The emulator automatic-backup folder round trip and seven-copy retention checks passed. Physical device and cloud-document-provider checks remain listed in the publishing protocol. Build/test evidence and artifact checksums are recorded in [verification.md](verification.md).
