package dev.daybook.core

data class SpeechAsset(val name: String, val url: String, val bytes: Long, val sha256: String)
data class SpeechChoice(val id: String, val title: String, val description: String,
    val engine: String, val architecture: Int = 0, val assets: List<SpeechAsset>) {
    val bytes get() = assets.sumOf { it.bytes }
    val verification get() = if (id == SpeechCatalog.WHISPER) assets.single().sha256
        else sha256(assets.joinToString("|") { "${it.name}:${it.bytes}:${it.sha256}" }.toByteArray())
    val livePreview get() = id == SpeechCatalog.WHISPER
}
object SpeechCatalog {
    const val WHISPER = "whisper_base_en"
    val choices = listOf(
        SpeechChoice(WHISPER, "Whisper base.en", "English · compact · optional live previews", "whisper", assets = listOf(
            SpeechAsset("ggml-base.en.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.en.bin",
                147964211L, "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002")
        )),
        SpeechChoice("moonshine_small", "Moonshine v2 Small", "English · transcribes after saving · 123M parameters", "moonshine", 4, listOf(
            SpeechAsset("adapter.ort", "https://download.moonshine.ai/model/small-streaming-en/quantized_26_08_21/adapter.ort", 2870368L, "c665f742364febad597cc9ac1e0b341ffbee0e24a1466e2f3bde95e6e4771762"),
            SpeechAsset("cross_kv.ort", "https://download.moonshine.ai/model/small-streaming-en/quantized_26_08_21/cross_kv.ort", 5356536L, "e2d3417144e9514055ebfefe8dcc4c0a55a55adcb8530435844c75c53e352bf6"),
            SpeechAsset("decoder_kv.ort", "https://download.moonshine.ai/model/small-streaming-en/quantized_26_08_21/decoder_kv.ort", 81878600L, "1a05465b1dd955858dfcbee039c0020fb5dd982b0f5094c34e61735d518d771b"),
            SpeechAsset("encoder.ort", "https://download.moonshine.ai/model/small-streaming-en/quantized_26_08_21/encoder.ort", 44148576L, "2d4d973e91e8aca08c51e7e7efa28a46ab265b63d809d5294d18b86bcd85b993"),
            SpeechAsset("frontend.model.ort", "https://download.moonshine.ai/model/small-streaming-en/quantized_26_08_21/frontend.model.ort", 26944L, "09b1210ae30dc5f0f3e45f0ebab914c254741323114f53fbbe5ae62cca35058f"),
            SpeechAsset("frontend.weights.ort", "https://download.moonshine.ai/model/small-streaming-en/quantized_26_08_21/frontend.weights.ort", 7769464L, "7ef97521bd4bad3928f5bb6808586f4fcc6e92bd5990394112eed7d4052ec338"),
            SpeechAsset("streaming_config.json", "https://download.moonshine.ai/model/small-streaming-en/quantized_26_08_21/streaming_config.json", 512L, "26f02b6afb22d60871a5efd85c3d38e569cc0ddb6c5eb6e93d3260152ae8a47a"),
            SpeechAsset("tokenizer.bin", "https://download.moonshine.ai/model/small-streaming-en/quantized_26_08_21/tokenizer.bin", 249974L, "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d")
        )),
        SpeechChoice("moonshine_medium", "Moonshine v2 Medium", "English · transcribes after saving · 245M parameters", "moonshine", 5, listOf(
            SpeechAsset("adapter.ort", "https://download.moonshine.ai/model/medium-streaming-en/quantized_26_08_21/adapter.ort", 3651296L, "3f2a287def57cc094367a0eec3c4f5fc36a32ec420e86764b696920991b20281"),
            SpeechAsset("cross_kv.ort", "https://download.moonshine.ai/model/medium-streaming-en/quantized_26_08_21/cross_kv.ort", 11643776L, "642f6e21cd305be79342207c6f9e6b681d469d55bc48c72b27b84846fb71fd1e"),
            SpeechAsset("decoder_kv.ort", "https://download.moonshine.ai/model/medium-streaming-en/quantized_26_08_21/decoder_kv.ort", 146972408L, "193bb366492b74fc4ad338c6778e8d8eb916aaa11b5aa264f9057f4db7759486"),
            SpeechAsset("encoder.ort", "https://download.moonshine.ai/model/medium-streaming-en/quantized_26_08_21/encoder.ort", 94705376L, "12915e76ebac7dd287c5ea63965d06103a53ba1ce242a4a34f318f3958c60c37"),
            SpeechAsset("frontend.model.ort", "https://download.moonshine.ai/model/medium-streaming-en/quantized_26_08_21/frontend.model.ort", 28720L, "95768855c70c8251eeecc05fedf69999da1b8ab16f605c9f457fd3354b0ad6b5"),
            SpeechAsset("frontend.weights.ort", "https://download.moonshine.ai/model/medium-streaming-en/quantized_26_08_21/frontend.weights.ort", 11889560L, "5ac941f490cbe035b335b99a414cc393d62d4c6f9f2423495b286870d271d709"),
            SpeechAsset("streaming_config.json", "https://download.moonshine.ai/model/medium-streaming-en/quantized_26_08_21/streaming_config.json", 513L, "28e83b7a28e91472692a035e0dae3116422ae43aeb2bef5ed822c44ce89b88af"),
            SpeechAsset("tokenizer.bin", "https://download.moonshine.ai/model/medium-streaming-en/quantized_26_08_21/tokenizer.bin", 249974L, "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d")
        )),
        SpeechChoice("parakeet_v2", "Parakeet TDT v2", "English · transcribes after saving · 600M parameters", "parakeet", assets = listOf(
            SpeechAsset("decoder.int8.onnx", "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/1ab9323565ddb038682214b292f588070a538ce2/decoder.int8.onnx", 7257753L, "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
            SpeechAsset("encoder.int8.onnx", "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/1ab9323565ddb038682214b292f588070a538ce2/encoder.int8.onnx", 652184296L, "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
            SpeechAsset("joiner.int8.onnx", "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/1ab9323565ddb038682214b292f588070a538ce2/joiner.int8.onnx", 1739080L, "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
            SpeechAsset("tokens.txt", "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/1ab9323565ddb038682214b292f588070a538ce2/tokens.txt", 9384L, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d")
        ))
    )
    fun get(id: String) = choices.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Choose a supported speech model.")
    fun workName(id: String) = if (get(id).id == WHISPER) "speech-model" else "speech-model-$id"
}
