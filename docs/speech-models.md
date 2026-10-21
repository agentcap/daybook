# Offline speech engines

Version 0.6.0-beta.1 supports English Whisper base.en, Moonshine v2 Small/Medium and Parakeet TDT 0.6B v2. Settings → Voice & offline speech saves the chosen model immediately; download each choice separately. Recording remains available when its model is missing. Retry the note after downloading.

## Runtime pins

The build helper downloads AARs into ignored .tools/speech-runtime and verifies them before Gradle uses them.

| Runtime | Version | SHA-256 |
| --- | --- | --- |
| Moonshine Voice Android | 0.1.5 | ee2d95c21150683c743db8f3aef66281fd5408bcefc94be3ca1d2545ada1f571 |
| Sherpa-onnx, statically linked ONNX Runtime | 1.13.8 | b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471 |

See scripts/speech-dependencies.py for exact download URLs. Moonshine source commit: 234f60faa0eb388b01cdf7e60aca232af37aefda. Sherpa source commit: 11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf. Moonshine bundles dynamic ONNX Runtime 1.23.2; Sherpa's static variant avoids a conflicting dynamic library. ARM64 and x86-64 libraries were checked for 16 KiB ELF alignment. Actual runtime coexistence was tested in both loading orders in the x86-64 verification app.

Moonshine and its English weights use MIT; Sherpa uses Apache-2.0; ONNX Runtime uses MIT with dependency notices. Parakeet TDT 0.6B v2 is NVIDIA's CC-BY-4.0 model, quantized/exported to ONNX by sherpa-onnx/csukuangfj. See the bundled speech-model-licenses.txt and onnx-third-party-notices.txt. [Original model](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2). [Converted weights](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8).

## Model pins

The authoritative catalog is core/src/main/kotlin/dev/daybook/core/SpeechCatalog.kt. Whisper retains its existing base.en file, SHA-256 and marker path. Moonshine uses CDN quantized_26_08_21 directories (optional attention-decoder weights are not needed). Their published CRC32C values were checked against downloaded files before pinning SHA-256. Parakeet uses Hugging Face revision 1ab9323565ddb038682214b292f588070a538ce2; binary SHA-256 values were checked against LFS metadata.

### moonshine_medium

Total: 269141623 bytes.

| Component | Bytes | SHA-256 |
| --- | ---: | --- |
| adapter.ort | 3651296 | 3f2a287def57cc094367a0eec3c4f5fc36a32ec420e86764b696920991b20281 |
| cross_kv.ort | 11643776 | 642f6e21cd305be79342207c6f9e6b681d469d55bc48c72b27b84846fb71fd1e |
| decoder_kv.ort | 146972408 | 193bb366492b74fc4ad338c6778e8d8eb916aaa11b5aa264f9057f4db7759486 |
| encoder.ort | 94705376 | 12915e76ebac7dd287c5ea63965d06103a53ba1ce242a4a34f318f3958c60c37 |
| frontend.model.ort | 28720 | 95768855c70c8251eeecc05fedf69999da1b8ab16f605c9f457fd3354b0ad6b5 |
| frontend.weights.ort | 11889560 | 5ac941f490cbe035b335b99a414cc393d62d4c6f9f2423495b286870d271d709 |
| streaming_config.json | 513 | 28e83b7a28e91472692a035e0dae3116422ae43aeb2bef5ed822c44ce89b88af |
| tokenizer.bin | 249974 | 6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d |

### moonshine_small

Total: 142300974 bytes.

| Component | Bytes | SHA-256 |
| --- | ---: | --- |
| adapter.ort | 2870368 | c665f742364febad597cc9ac1e0b341ffbee0e24a1466e2f3bde95e6e4771762 |
| cross_kv.ort | 5356536 | e2d3417144e9514055ebfefe8dcc4c0a55a55adcb8530435844c75c53e352bf6 |
| decoder_kv.ort | 81878600 | 1a05465b1dd955858dfcbee039c0020fb5dd982b0f5094c34e61735d518d771b |
| encoder.ort | 44148576 | 2d4d973e91e8aca08c51e7e7efa28a46ab265b63d809d5294d18b86bcd85b993 |
| frontend.model.ort | 26944 | 09b1210ae30dc5f0f3e45f0ebab914c254741323114f53fbbe5ae62cca35058f |
| frontend.weights.ort | 7769464 | 7ef97521bd4bad3928f5bb6808586f4fcc6e92bd5990394112eed7d4052ec338 |
| streaming_config.json | 512 | 26f02b6afb22d60871a5efd85c3d38e569cc0ddb6c5eb6e93d3260152ae8a47a |
| tokenizer.bin | 249974 | 6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d |

### parakeet_v2

Total: 661190513 bytes.

| Component | Bytes | SHA-256 |
| --- | ---: | --- |
| decoder.int8.onnx | 7257753 | b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e |
| encoder.int8.onnx | 652184296 | a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab |
| joiner.int8.onnx | 1739080 | 7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2 |
| tokens.txt | 9384 | ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d |

## Data and scheduling

Every new voice entry and queued work request stores its stable speech-model ID. Legacy entries/jobs use Whisper. Changing the setting does not redirect pending jobs; explicit retries use the new selection. Model removal waits for its active inference/download file lock and preserves recordings. No automatic fallback or automatic multi-model download occurs.

All components must be verified before the model is marked ready. A missing component invalidates readiness. Transfers retain resumable partial files, validate Content-Range, bound file size, and verify SHA-256 before installation. WorkManager scheduling, connection constraints, low storage, force-stop and device battery policies can delay work. Transcription has no network requirement.

Only Whisper offers optional live draft previews in this release. All choices create a final transcript after saving. The new engines use 30-second PCM windows, skipping completely silent windows and keeping offsets; timestamps are approximate. There is no diarization, translation, GPU/NPU acceleration or word-level alignment. Chunk boundaries can reduce accuracy. Larger models can use substantial memory; low-memory/load errors keep the audio and show a retry/alternate-model message. Native inference cancellation takes effect between bounded windows.

Models stay out of APKs, Git and backups. Backups retain preferences, model IDs, original notes/audio/photos and revisions. Re-download models after restoring to a new phone. A note can be transcribed again using the selected model after confirmation; prior current text is appended to revisions and original text/audio are preserved. Manual edits mark the entry ready and cannot be replaced by pending work.

## Reproduce actual-model checks

Install the verification APK and test APK on a dedicated emulator, open the verification app, and download all four models in its Settings. Do not use the regular app's package. Then run:

```sh
bash scripts/build.sh :app:connectedVerificationAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.daybook.app.MultipleSpeechTest \
  -Pandroid.testInstrumentationRunnerArguments.multipleSpeech=true
```

One case deliberately removes one Moonshine component, seeds a partial download, and resumes from its real HTTPS host. The other verifies all model hashes, enables airplane mode on the emulator, checks no active network, and runs all four engines forward and backward through the production transcription worker. It asserts recognized fixture words, preserved original text/revisions/audio, model selection despite a different default, and protection of saved corrections. Connectivity is restored afterwards. Results are written under the verification app's external files/verification/multiple-speech-result.txt. Network testing requires internet; model checking uses about 1.2 GB of disk. Normal tests skip these opt-in cases.

The short JFK fixture is a development correctness sample, not an accuracy benchmark. Emulator timings are not physical Galaxy/Pixel performance claims. Physical microphone quality, accent/noise accuracy, long-note boundaries, memory pressure, sustained speed, battery/thermal behavior, background scheduling, ARM64 runtime loading and a full on-phone download remain in the device checklist.
