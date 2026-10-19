package dev.daybook.app.ai

import dev.daybook.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LocalCleanupClientTest {
    private val entry = Entry(id = "source", day = "2026-10-18", originalText = "I went to the gym.")
    private val valid = """{"journal":"I went to the gym.","summary":"A gym visit.","captions":[],"goalActivity":[],"newGoals":{"found":false,"items":[]},"todos":{"found":false,"items":[]},"ideas":{"found":false,"items":[]}}"""
    private fun engine(output: String) = object : LocalTextEngine {
        override suspend fun generate(model: File, system: String, input: String, grammar: String): String {
            assertTrue(input.contains(entry.text)); assertTrue(grammar.contains("source"))
            return output
        }
    }
    @Test fun localResultsUseTheSharedValidatorWithoutAnApiKey() = runBlocking<Unit> {
        val client = LocalCleanupClient(File("model.gguf"), { true }, engine(valid))
        val result = client.generate(ReviewInput(entry.day, listOf(entry), emptyList(), listOf(ReviewPhoto("photo", entry.id, "image-bytes"))))
        assertEquals(entry.text, result.journal); assertTrue(result.captions.isEmpty())
    }
    @Test fun malformedOutputAndMissingModelCannotCreateAReview() = runBlocking<Unit> {
        for (client in listOf(LocalCleanupClient(File("missing"), { false }, engine(valid)),
            LocalCleanupClient(File("model.gguf"), { true }, engine("unstructured text")))) {
            try { client.generate(ReviewInput(entry.day, listOf(entry), emptyList(), emptyList())); fail("Should reject") }
            catch (_: IllegalStateException) { }
        }
    }
}
