# Third-party notices

- whisper.cpp v1.8.7 is included as a pinned Git submodule under its upstream MIT license. See `vendor/whisper.cpp/LICENSE`. Its ggml sources carry their upstream notices.
- The English base.en model is downloaded from `ggerganov/whisper.cpp` on Hugging Face at the pinned revision documented in source. OpenAI Whisper's model/code license is MIT; see [upstream Whisper](https://github.com/openai/whisper).
- AndroidX and Material components are provided under their upstream Apache 2.0 notices. OkHttp, Gson, Kotlin, coroutines, and Coil retain their respective upstream licenses.
- `app/src/androidTest/assets/jfk.wav` comes from the pinned whisper.cpp sample directory and is used only as an instrumentation speech fixture. It is not included in the personal app APK.
- UI screenshot fixtures are synthetic, including their sample meal illustration and generated-looking review text. They are interface demonstrations, not live OpenAI output.
