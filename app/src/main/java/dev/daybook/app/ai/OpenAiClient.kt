package dev.daybook.app.ai

import com.google.gson.*
import dev.daybook.core.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class CleanupFailure(message: String, val retryable: Boolean = false) : IOException(message)
interface DailyCleanupClient { fun generate(input: ReviewInput, key: String, model: String): ReviewDraft }
class OpenAiClient(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(180, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).build(),
    private val endpoint: String = "https://api.openai.com/v1/responses"
) : DailyCleanupClient {
    fun requestBody(input: ReviewInput, model: String): String {
        val gson = Gson()
        val content = JsonArray()
        content.add(JsonObject().apply {
            addProperty("type", "input_text")
            addProperty("text", gson.toJson(mapOf("date" to input.day, "entries" to input.entries.map {
                mapOf("id" to it.id, "time" to java.time.Instant.ofEpochMilli(it.created).atZone(java.time.ZoneId.of(it.zone)).toString(), "text" to it.text, "kind" to it.kind)
            }, "goals" to input.goals, "confirmedActivities" to input.confirmedActivities)))
        })
        input.photos.forEach { photo ->
            content.add(JsonObject().apply { addProperty("type", "input_text"); addProperty("text", "Photo attachmentId=${photo.attachmentId}; source entryId=${photo.entryId}") })
            content.add(JsonObject().apply { addProperty("type", "input_image"); addProperty("image_url", "data:image/jpeg;base64,${photo.jpegBase64}"); addProperty("detail", "auto") })
        }
        val request = JsonObject().apply {
            addProperty("model", model); addProperty("store", false); addProperty("instructions", ReviewProtocol.instructions)
            addProperty("max_output_tokens", 10000)
            add("input", JsonArray().apply { add(JsonObject().apply { addProperty("role", "user"); add("content", content) }) })
            add("text", JsonObject().apply { add("format", JsonObject().apply {
                addProperty("type", "json_schema"); addProperty("name", "daily_review"); addProperty("strict", true); add("schema", ReviewProtocol.schema)
            }) })
        }
        val body = gson.toJson(request)
        require(body.toByteArray().size <= 40 * 1024 * 1024) { "This day exceeds the 40 MB review upload limit. Your originals are preserved; reduce attached images or export this day." }
        return body
    }
    override fun generate(input: ReviewInput, key: String, model: String): ReviewDraft {
        require(key.isNotBlank()) { "Enter your OpenAI API key in Settings." }
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $key")
            .post(requestBody(input, model).toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val message = when (response.code) {
                    401, 403 -> "OpenAI could not authorize your key. Check it and project permissions in Settings."
                    404 -> "The selected OpenAI model is unavailable to this key. Choose another vision model in Settings."
                    429 -> "OpenAI's usage or rate limit was reached. Check your API billing and limits, then retry."
                    in 500..599 -> "OpenAI is temporarily unavailable. The review will retry."
                    else -> "OpenAI rejected the review (HTTP ${response.code}). Check your model and try again."
                }
                throw CleanupFailure(message, response.code == 429 || response.code >= 500)
            }
            val body = response.body?.string() ?: throw CleanupFailure("OpenAI returned no response.")
            val root = JsonParser.parseString(body).asJsonObject
            if (root.get("status")?.asString != "completed") throw CleanupFailure("OpenAI did not finish the review. Retry or choose another model.")
            val outputs = root.getAsJsonArray("output") ?: throw CleanupFailure("OpenAI returned no review text.")
            val text = buildString {
                outputs.forEach { output ->
                    output.asJsonObject.getAsJsonArray("content")?.forEach { part ->
                        val item = part.asJsonObject
                        when (item.get("type")?.asString) {
                            "output_text" -> append(item.get("text").asString)
                            "refusal" -> throw CleanupFailure("OpenAI could not process this review. Your originals are unchanged.")
                        }
                    }
                }
            }
            return ReviewProtocol.parse(text, input)
        }
    }
}
