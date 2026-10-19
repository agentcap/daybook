# On-device daily reviews

1. Open **Settings → AI daily reviews → On-device**.
2. Tap **Download review model**. Allow about 1.2 GB of free storage. The model download is 1.1 GB; it is separate from the English speech model.
3. Once the model says ready, open a day and tap **Clean up now**, or enable automatic nightly cleanup.
4. Review the cleaned journal and approve any goal progress, new goals, to-dos, or ideas you want to keep.

After download, text review works without internet or an API key. Attached photos are preserved but are not analyzed by this model. Add a text caption for a photo-only day. Choose OpenAI explicitly when you want image understanding; Daybook never falls back to it automatically.

Local results can be less detailed than cloud results, especially with complex notes. Large days can take minutes or exceed the local limits. Errors retain original entries and previous reviews. The app bounds input, generated output, and inference time. Physical S26 Ultra speed, battery use, and thermal behavior still need phone testing.

You can pause/resume the download or remove the model from AI settings or **Backups & storage**. Removing it preserves all notes and saved reviews. Full backups exclude models; re-download after restoring on another phone.

## Pinned model and runtime

- [Official Qwen model card](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF): Qwen2.5-1.5B-Instruct, Q4_K_M, Apache 2.0.
- Model revision: `91cad51170dc346986eccefdc2dd33a9da36ead9`.
- File: `qwen2.5-1.5b-instruct-q4_k_m.gguf`; 1,117,320,736 bytes.
- SHA-256: `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e`.
- [llama.cpp Android reference](https://github.com/ggml-org/llama.cpp/blob/b11724/docs/android.md), MIT, pinned to `99db719bdf07f7d537431ca556a0512bf3d995b6`.
- Runtime license: `local-llm/src/main/assets/llama.cpp-LICENSE.txt`, also included in the APK.

The app downloads only this pinned model and verifies its digest before use. No model Python code, downloaded executable, or server process is used.

The ARM64 phone build uses a portable CPU baseline. Local reviews in the x86-64 emulator build require an AVX2/FMA/F16C-capable host; JNI checks those capabilities before inference.

## Repeat the real-model check

Use the isolated verification APK on a disposable emulator. The first opt-in test downloads and verifies the actual model; the second disables emulator connectivity, runs local cleanup on synthetic notes, checks suggestions and original preservation, then exercises whisper.cpp in the same process.

```sh
bash scripts/build.sh :app:assembleVerification :app:assembleVerificationAndroidTest
adb -e install -r app/build/outputs/apk/verification/app-verification.apk
adb -e install -r app/build/outputs/apk/androidTest/verification/app-verification-androidTest.apk
adb -e shell am instrument -w -e localLlm true \
  -e class dev.daybook.app.RealLocalLlmTest \
  dev.daybook.app.verification.test/androidx.test.runner.AndroidJUnitRunner
```

This test is opt-in because it downloads about 1.1 GB and changes airplane mode on the emulator. It restores connectivity afterwards. No paid API request is used.
