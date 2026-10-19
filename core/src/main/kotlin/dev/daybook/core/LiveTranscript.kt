package dev.daybook.core

/** Later previews replace the same audio segment, rather than duplicating its text. */
class LiveTranscript {
    private val segments = sortedMapOf<Int, String>()
    fun update(index: Int, text: String): String {
        require(index >= 0)
        if (text.isNotBlank()) segments[index] = text.trim()
        return segments.values.joinToString(" ").trim()
    }
}
