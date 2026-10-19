# Play Console declarations and review access

These are preparation notes, not submitted forms. Review every answer against the exact uploaded build and current Console questions.

## App access
No login is required. Finish onboarding, then write a note, add a goal, and use History/Lists immediately. Notifications and microphone permissions are optional until their features are used. Models can be downloaded in Settings. Local AI reviews require the separate 1.1 GB model; cloud reviews require the reviewer's own OpenAI key and explicit consent. No developer key is distributed. The app lock is off initially.

## Privacy and Data safety
Host the generated HTML policy at your real public HTTPS URL and set the same link in the app and Console. Identify the publisher and a working privacy/support contact.

Core data stays on-device. Optional OpenAI cleanup transmits eligible journal text, selected photos, goal/activity context, and open list titles. These may contain personal, health/fitness, or other sensitive information supplied by users. Do not label the whole app as collecting no data merely because local mode exists. Describe optional processing and purposes accurately.

Audio is not uploaded for transcription or cleanup. No advertising/analytics SDKs, accounts, contacts, location permission, Health Connect, payment processing, or cross-app identifiers are used. HTTPS model downloads disclose ordinary connection information to their hosts. Backup document providers may sync encrypted files chosen by the user. Feedback is a user-reviewed share action with optional displayed diagnostics.

Use the current Data safety definitions for collection, service providers, user-initiated transfers, ephemeral processing, and sharing exceptions. OpenAI retention may apply, so do not automatically claim ephemeral processing or zero retention. Do not add undisclosed telemetry later without updating the policy and forms.

## Foreground services and permissions
Declare the microphone foreground-service use: user-initiated voice recording, visible ongoing notification, pause/resume, and Stop & save. Prepare a short screen recording demonstrating start, screen lock/background, and stop. No background microphone startup or exact-alarm permission is requested. Notifications are reminders/recording controls. Android picker and camera intents avoid broad photo-library permissions.

## Health and AI content
Complete the Health apps declaration. Gym/fitness goals and calorie/dietary tracking should be reviewed under Activity and Fitness / Nutrition and Weight Management; do not select 'no health features' without considering them. There is no medical diagnosis/treatment functionality and no medical-device claim. Estimates must remain clearly labeled and user-confirmed.

Review the AI policy scope against the final listing and features. Google distinguishes limited productivity enhancements from central generative chat features. Daybook is a journal with cleanup/suggestions, not a general chatbot. Feedback drafts can flag problematic output without automatically attaching private notes. Reassess requirements if adding chat, image generation, sharing/community features, or broader generation.

## Store and release
Complete content rating, target audience, ads (none in this build), app access, privacy, Data safety, health, foreground-service, countries, and pricing declarations. Check current target SDK and native 16 KiB compatibility requirements. The APK and AAB must be non-debuggable and signed appropriately.

New personal accounts created after November 13, 2023 currently require 12 testers continuously opted in for 14 days before applying for production access. Run real tests, record feedback, fix defects, and provide review access instructions. Protect the existing app signing key and decide Play App Signing enrollment before uploading.

References:
- https://support.google.com/googleplay/android-developer/answer/10144311?hl=en
- https://support.google.com/googleplay/android-developer/answer/14738291?hl=en
- https://support.google.com/googleplay/android-developer/answer/14094294?hl=en
- https://support.google.com/googleplay/android-developer/answer/14151465?hl=en
- https://developer.android.com/guide/app-bundle
