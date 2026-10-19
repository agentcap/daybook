package dev.daybook.core
import org.junit.Assert.*
import org.junit.Test

class ReviewProtocolTest {
    private val entry = Entry(id = "e1", day = "2026-10-18", originalText = "Ate lunch.")
    private val goal = Goal(id = "g1", title = "Calories", target = 3000.0, unit = "kcal", checklist = false)
    private val input = ReviewInput(entry.day, listOf(entry), listOf(goal), listOf(ReviewPhoto("p1", entry.id, "unused")))
    private val valid = """{"journal":"I ate lunch.","summary":"Lunch.","captions":[{"attachmentId":"p1","description":"Rice and vegetables."}],"suggestions":[{"goalId":"g1","amount":600,"note":"Estimated portion","sources":["e1"],"estimated":true}],"discoveries":[]}"""
    @Test fun photoEstimatesRemainSuggestions() {
        val draft = ReviewProtocol.parse(valid, input)
        assertTrue(draft.suggestions.single().estimated)
        assertEquals(600.0, draft.suggestions.single().amount, 0.0)
    }
    @Test fun inventedSourcesAndGoalsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ReviewProtocol.parse(valid.replace("\"e1\"", "\"missing\""), input) }
        assertThrows(IllegalStateException::class.java) { ReviewProtocol.parse(valid.replace("\"g1\"", "\"missing\""), input) }
    }
    @Test fun missingPhotoDescriptionRejected() {
        assertThrows(IllegalArgumentException::class.java) { ReviewProtocol.parse(valid.replace("[{\"attachmentId\":\"p1\",\"description\":\"Rice and vegetables.\"}]", "[]"), input) }
    }
}
