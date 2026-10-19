package dev.daybook.app.ai
import dev.daybook.core.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class OpenAiClientTest {
    private val server = MockWebServer()
    @After fun close() { server.shutdown() }
    private val input = ReviewInput("2026-10-18", listOf(Entry(id = "e", day = "2026-10-18", originalText = "Cooked dinner.")), emptyList(), emptyList())
    @Test fun sendsStatelessStructuredRequestAndPreservesInput() {
        val draft = """{"journal":"I cooked dinner.","summary":"Cooking.","captions":[],"suggestions":[]}"""
        val body = com.google.gson.Gson().toJson(mapOf("status" to "completed", "output" to listOf(mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to draft))))))
        server.enqueue(MockResponse().setBody(body))
        val result = OpenAiClient(OkHttpClient(), server.url("/v1/responses").toString()).generate(input, "synthetic-test-key", "gpt-5.4-mini")
        assertEquals("I cooked dinner.", result.journal)
        val request = server.takeRequest()
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertFalse(json["store"].asBoolean)
        assertTrue(json["text"].asJsonObject["format"].asJsonObject["strict"].asBoolean)
        assertEquals("Cooked dinner.", input.entries.single().originalText)
        assertEquals("Bearer synthetic-test-key", request.getHeader("Authorization"))
    }
    @Test fun refusesIncompleteOrUnsafeResponses() {
        server.enqueue(MockResponse().setBody("""{"status":"incomplete","output":[]}"""))
        assertThrows(CleanupFailure::class.java) { OpenAiClient(OkHttpClient(), server.url("/").toString()).generate(input, "test-key", "test") }
    }
    @Test fun authenticationFailureIsActionableAndDoesNotEchoProviderBody() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive provider response"))
        val error = assertThrows(CleanupFailure::class.java) { OpenAiClient(OkHttpClient(), server.url("/").toString()).generate(input, "test-key", "test") }
        assertTrue(error.message!!.contains("key"))
        assertFalse(error.message!!.contains("sensitive"))
        assertFalse(error.retryable)
    }
    @Test fun dailyPhotosAreSentWithSourceLabels() {
        val photoInput = input.copy(photos = listOf(ReviewPhoto("p", "e", "base64-fixture")))
        val body = OpenAiClient().requestBody(photoInput, "test")
        assertTrue(body.contains("data:image/jpeg;base64,base64-fixture"))
        val json = JsonParser.parseString(body).asJsonObject
        assertTrue(json["input"].asJsonArray[0].asJsonObject["content"].asJsonArray.any { it.asJsonObject.get("text")?.asString?.contains("attachmentId=p") == true })
        assertFalse(body.contains("audio"))
    }
}
