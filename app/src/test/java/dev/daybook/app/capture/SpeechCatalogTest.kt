package dev.daybook.app.capture
import com.google.gson.Gson
import dev.daybook.core.*
import org.junit.Assert.*
import org.junit.Test

class SpeechCatalogTest {
    @Test fun oldPreferencesAndEntriesKeepWhisperDefaults() {
        val gson = Gson()
        val settings = gson.fromJson("""{"onboarded":true}""", Settings::class.java)
        assertEquals(SpeechCatalog.WHISPER, settings.speechModel)
        assertTrue(settings.liveTranscripts)
        assertEquals("", gson.fromJson("""{"id":"old"}""", Entry::class.java).speechModel)
        assertEquals("speech-model", SpeechCatalog.workName(SpeechCatalog.WHISPER))
    }
    @Test fun selectedModelRoundTripsAndInvalidIdsAreRejected() {
        val gson = Gson()
        for (choice in SpeechCatalog.choices) {
            val settings = Settings(speechModel = choice.id, liveTranscripts = false)
            JournalRules.validateSettings(settings)
            assertEquals(settings, gson.fromJson(gson.toJson(settings), Settings::class.java))
            assertTrue(choice.assets.isNotEmpty())
            assertEquals(choice.assets.size, choice.assets.map { it.name }.toSet().size)
            assertTrue(choice.assets.all { it.bytes > 0 && it.sha256.matches(Regex("[0-9a-f]{64}")) && it.url.startsWith("https://") })
        }
        try { JournalRules.validateSettings(Settings(speechModel = "unknown")); fail("Unknown model must be rejected") } catch (_: IllegalArgumentException) {}
    }
    @Test fun modelMetadataPreservesLegacyReviewAndSuggestionFingerprints() {
        val entry = Entry(id = "entry", day = "2026-10-21", created = 0, zone = "UTC", originalText = "A memory.")
        val snapshot = Snapshot(entries = listOf(entry))
        // Known hashes of the 0.5.3 serialized source shapes, before model provenance existed.
        assertEquals("87059fd1f58a0d22f98f4cf4d8764dd28cd657e08e059ec7a9dfac444455a662", JournalRules.fingerprint(snapshot, entry.day))
        assertEquals("69e33204211fec7c1fab1f1654a1a2ae6bdd3e05f753ead92164e4f4293167ac", PlannerRules.sourceFingerprint(snapshot, listOf(entry.id)))
        val metadata = snapshot.copy(entries = listOf(entry.copy(speechModel = "parakeet_v2")))
        assertEquals(JournalRules.fingerprint(snapshot, entry.day), JournalRules.fingerprint(metadata, entry.day))
        assertEquals(PlannerRules.sourceFingerprint(snapshot, listOf(entry.id)), PlannerRules.sourceFingerprint(metadata, listOf(entry.id)))
        assertNotEquals(JournalRules.fingerprint(snapshot, entry.day), JournalRules.fingerprint(snapshot.copy(entries = listOf(entry.copy(text = "Edited content"))), entry.day))
    }

}
