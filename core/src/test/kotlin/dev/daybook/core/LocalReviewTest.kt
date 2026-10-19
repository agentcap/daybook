package dev.daybook.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class LocalReviewTest {
    private val entry = Entry(id = "source", day = "2026-10-18", originalText = "I went to the gym. Tomorrow I need to call the dentist.")
    private val input = ReviewInput(entry.day, listOf(entry), listOf(Goal(id = "gym", title = "Go to gym")),
        listOf(ReviewPhoto("photo", entry.id, "not-an-upload")))
    @Test fun oldPreferencesAndReviewsKeepTheirCloudDefaults() {
        val settings = Gson().fromJson("""{"onboarded":true,"model":"gpt-5.4-mini"}""", Settings::class.java)
        assertEquals("openai", settings.reviewProvider)
        JournalRules.validateSettings(settings)
        val review = Gson().fromJson("""{"id":"review","day":"2026-10-18","fingerprint":"","journal":"A day","summary":"Summary","model":"fixture"}""", Review::class.java)
        assertEquals("openai", review.provider)
        assertThrows(IllegalArgumentException::class.java) { JournalRules.validateSettings(settings.copy(reviewProvider = "remote-server")) }
    }
    @Test fun localPolicyNeverNeedsCloudConsentOrNetwork() {
        val local = Settings(reviewProvider = "local", cloudConsent = false)
        assertTrue(ReviewPolicy.allowed(local)); assertFalse(ReviewPolicy.needsNetwork(local.reviewProvider))
        assertFalse(ReviewPolicy.allowed(Settings(cloudConsent = false)))
        assertTrue(ReviewPolicy.needsNetwork(Settings().reviewProvider))
    }
    @Test fun textOnlyReviewRejectsPhotoOnlyInputAndDoesNotEncodeImages() {
        val text = LocalReviewProtocol.textInput(input)
        assertTrue(text.photos.isEmpty()); assertEquals(input.entries, text.entries)
        val payload = LocalReviewProtocol.payload(text)
        assertTrue(payload.contains("source")); assertFalse(payload.contains("not-an-upload"))
        assertThrows(IllegalArgumentException::class.java) { LocalReviewProtocol.textInput(input.copy(entries = listOf(entry.copy(text = "")))) }
    }
    @Test fun explicitGoalEvaluationsRemainSuggestionsUntilApproved() {
        val yes = """{"journal":"A gym visit.","summary":"Gym.","captions":[],"goalActivity":[{"goalId":"gym","workedOn":true,"amount":1,"note":"Went to gym.","sources":["source"],"estimated":false}],"newGoals":{"found":false,"items":[]},"todos":{"found":false,"items":[]},"ideas":{"found":false,"items":[]}}"""
        val text = input.copy(photos = emptyList())
        assertEquals(1.0, LocalReviewProtocol.parse(yes, text).suggestions.single().amount, 0.0)
        val no = yes.replace("true", "false").replace("\"amount\":1", "\"amount\":0").replace("[\"source\"]", "[]")
        assertTrue(LocalReviewProtocol.parse(no, text).suggestions.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { LocalReviewProtocol.parse(yes.replace("gym", "invented"), text) }
    }
    @Test fun existingGoalTitlesAreNotProposedAgainAsTasksOrIdeas() {
        val duplicate = ProposedItem("todo", input.goals.single().title, "Already tracked.", listOf(entry.id))
        val draft = ReviewDraft("Journal", "Summary", emptyList(), emptyList(), listOf(duplicate, duplicate.copy(kind = "idea")))
        assertTrue(LocalReviewProtocol.conservativeDefaults(draft, input).discoveries.isEmpty())
    }
    @Test fun unstatedFrequencyDefaultsToOneTime() {
        val proposal = ProposedItem("goal", "Learn Italian", "Want to learn.", listOf(entry.id), period = "daily")
        val draft = ReviewDraft("Journal", "Summary", emptyList(), emptyList(), listOf(proposal))
        assertEquals("once", LocalReviewProtocol.conservativeDefaults(draft, input).discoveries.single().period)
        assertEquals("daily", LocalReviewProtocol.conservativeDefaults(draft, input.copy(entries = listOf(entry.copy(text = "I want to learn Italian every day.")))).discoveries.single().period)
    }
    @Test fun grammarRestrictsReferencesAndEmptyGoalInputs() {
        val grammar = LocalReviewProtocol.grammar(LocalReviewProtocol.textInput(input))
        assertTrue(grammar.contains("entry-reference")); assertTrue(grammar.contains("source"))
        assertTrue(grammar.contains("gym")); assertTrue(grammar.contains("progress ::= \"[\" ws evaluation-0"))
        val noGoals = LocalReviewProtocol.grammar(input.copy(goals = emptyList()))
        assertTrue(noGoals.contains("progress ::= \"[\" ws \"]\" ws"))
    }
}
