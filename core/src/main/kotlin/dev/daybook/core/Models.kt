package dev.daybook.core

import java.time.*
import java.time.temporal.TemporalAdjusters
import java.security.MessageDigest
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

data class Entry(val id: String = newId(), val day: String = "", val created: Long = System.currentTimeMillis(),
    val zone: String = ZoneId.systemDefault().id, val kind: String = "text", val originalText: String = "",
    val text: String = originalText, val status: String = "ready", val error: String = "",
    val deletedAt: Long = 0, val keepLocal: Boolean = false, val includePhotosInCloud: Boolean = true,
    val bookmarks: List<AudioBookmark> = emptyList(), val segments: List<TranscriptSegment> = emptyList(),
    val speechModel: String = "")
// Speech-engine provenance is metadata, not journal content. Keep existing source hashes stable.
internal fun sourceEntries(entries: List<Entry>): List<com.google.gson.JsonObject> {
    val gson = com.google.gson.Gson()
    return entries.map { gson.toJsonTree(it).asJsonObject.apply { remove("speechModel") } }
}
data class AudioBookmark(val id: String = newId(), val ms: Long = 0, val label: String = "Bookmark")
data class TranscriptSegment(val ms: Long = 0, val endMs: Long = 0, val text: String = "")
data class WeekReflection(val id: String = newId(), val week: String = "", val text: String = "", val updated: Long = System.currentTimeMillis())
data class Revision(val id: String = newId(), val entryId: String, val text: String, val created: Long = System.currentTimeMillis())
data class Attachment(val id: String = newId(), val entryId: String, val path: String, val kind: String,
    val bytes: Long, val digest: String = "")
data class Goal(val id: String = newId(), val title: String, val period: String = "daily", val target: Double = 1.0,
    val unit: String = "times", val checklist: Boolean = true, val archived: Boolean = false)
data class Activity(val id: String = newId(), val goalId: String, val day: String, val amount: Double = 0.0,
    val note: String = "", val sources: List<String> = emptyList(), val undone: Boolean = false,
    val created: Long = System.currentTimeMillis(), val sourceKey: String = "")
data class Review(val id: String = newId(), val day: String = "", val fingerprint: String = "", val journal: String = "", val summary: String = "",
    val captions: List<PhotoCaption> = emptyList(), val created: Long = System.currentTimeMillis(), val model: String = "", val sourceIds: List<String> = emptyList(), val provider: String = "openai")
data class PhotoCaption(val attachmentId: String, val description: String)
data class Suggestion(val id: String = newId(), val reviewId: String, val goalId: String, val day: String,
    val amount: Double, val note: String, val sources: List<String>, val estimated: Boolean = false, val status: String = "pending")
data class Snapshot(val entries: List<Entry> = emptyList(), val revisions: List<Revision> = emptyList(),
    val attachments: List<Attachment> = emptyList(), val goals: List<Goal> = emptyList(),
    val activities: List<Activity> = emptyList(), val reviews: List<Review> = emptyList(),
    val suggestions: List<Suggestion> = emptyList(), val items: List<ListItem> = emptyList(), val proposals: List<Proposal> = emptyList(), val reflections: List<WeekReflection> = emptyList())
data class Settings(val onboarded: Boolean = false, val wake: String = "09:00", val sleep: String = "22:00",
    val intervalHours: Int = 3, val cleanupTime: String = "22:00", val reminders: Boolean = true,
    val autoCleanup: Boolean = true, val model: String = "gpt-5.4-mini", val biometric: Boolean = false,
    val theme: String = "system", val cloudConsent: Boolean = false, val reviewProvider: String = "openai",
    val wifiOnlyDownloads: Boolean = true, val cleanupChargingOnly: Boolean = false, val reduceMotion: Boolean = false,
    val speechModel: String = SpeechCatalog.WHISPER, val liveTranscripts: Boolean = true)
data class BackupBundle(val version: Int = 2, val state: Snapshot, val settings: Settings)

object JournalRules {
    fun validateGoal(goal: Goal) {
        require(goal.title.isNotBlank() && goal.title.length <= 200) { "Enter a goal title (up to 200 characters)." }
        require(goal.period in setOf("daily", "weekly", "once")) { "Choose a supported goal period." }
        require(goal.target.isFinite() && goal.target > 0 && goal.target <= 1e9) { "Target must be a positive number." }
        require(goal.unit.isNotBlank() && goal.unit.length <= 40) { "Enter a unit." }
    }
    fun validateSettings(s: Settings) {
        val wake = LocalTime.parse(s.wake); val sleep = LocalTime.parse(s.sleep)
        require(wake != sleep) { "Waking and quiet-hours times must differ." }
        LocalTime.parse(s.cleanupTime)
        require(s.intervalHours in 1..12) { "Reminder interval must be between 1 and 12 hours." }
        require(s.model.matches(Regex("[a-zA-Z0-9._-]{1,100}"))) { "Enter a valid model name." }
        require(s.theme in setOf("system", "light", "dark"))
        SpeechCatalog.get(s.speechModel)
        require(s.reviewProvider in setOf("openai", "local")) { "Choose OpenAI or on-device reviews." }
    }
    fun progress(state: Snapshot, goal: Goal, date: LocalDate): Double = state.activities
        .filter { !it.undone && it.goalId == goal.id && inPeriod(goal, LocalDate.parse(it.day), date) }.sumOf { it.amount }
    fun inPeriod(goal: Goal, event: LocalDate, date: LocalDate): Boolean = when (goal.period) {
        "once" -> !event.isAfter(date)
        "weekly" -> !event.isAfter(date) && event.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) == date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        else -> event == date
    }
    fun workedOn(state: Snapshot, day: String): Int = state.activities.filter { !it.undone && it.day == day }.map { it.goalId }.distinct().size
    fun suggestionKey(goalId: String, day: String, sources: List<String>): String = sha256("$goalId|$day|${sources.sorted().joinToString(",")}".toByteArray())
    fun fingerprint(state: Snapshot, day: String): String {
        val ids = state.entries.filter { it.day == day && it.deletedAt == 0L }.map { it.id }.toSet()
        val data = listOf(sourceEntries(state.entries.filter { it.id in ids }.sortedBy { it.id }),
            state.revisions.filter { it.entryId in ids }.sortedBy { it.id },
            state.attachments.filter { it.entryId in ids }.sortedBy { it.id },
            state.goals.filter { !it.archived }.sortedBy { it.id })
        return sha256(com.google.gson.Gson().toJson(data).toByteArray())
    }
    fun confirm(state: Snapshot, suggestionId: String, amount: Double, note: String): Snapshot {
        val s = state.suggestions.first { it.id == suggestionId }
        require(s.status == "pending") { "This suggestion was already handled." }
        val r = state.reviews.first { it.id == s.reviewId }
        require(r.fingerprint == fingerprint(state, s.day)) { "This review is outdated. Refresh it before confirming suggestions." }
        val goal = state.goals.first { it.id == s.goalId }
        require(!goal.archived) { "This goal is archived." }
        require(amount.isFinite() && amount >= 0 && amount <= 1e9) { "Enter a valid amount." }
        require(s.sources.isNotEmpty() && s.sources.all { id -> state.entries.any { it.id == id && it.day == s.day && it.deletedAt == 0L } })
        require(state.activities.none { !it.undone && it.goalId == s.goalId && it.sources.intersect(s.sources.toSet()).isNotEmpty() }) {
            "Some of this evidence is already logged for this goal. Undo that activity or log a separate manual correction."
        }
        val a = Activity(goalId = s.goalId, day = s.day, amount = amount, note = note, sources = s.sources,
            sourceKey = suggestionKey(s.goalId, s.day, s.sources))
        return state.copy(activities = state.activities + a, suggestions = state.suggestions.map { if (it.id == s.id) it.copy(status = "accepted") else it })
    }
    fun validateSnapshot(state: Snapshot) {
        require(state.entries.map { it.id }.distinct().size == state.entries.size)
        val entries = state.entries.associateBy { it.id }; val goals = state.goals.associateBy { it.id }
        require(goals.size == state.goals.size)
        state.entries.forEach { require(it.id.isNotBlank()); LocalDate.parse(it.day); ZoneId.of(it.zone); require(it.kind in setOf("text", "voice", "photo")) }
        state.entries.forEach { e ->
            require(e.deletedAt >= 0 && e.bookmarks.size <= 1000 && e.segments.size <= 20000)
            require(e.bookmarks.map { it.id }.distinct().size == e.bookmarks.size)
            e.bookmarks.forEach { require(it.id.isNotBlank() && it.ms in 0..1_800_000 && it.label.length <= 200) }
            e.segments.forEach { require(it.ms >= 0 && it.endMs >= it.ms && it.endMs <= 1_800_000 && it.text.length <= 10000) }
        }
        state.reflections.forEach { require(LocalDate.parse(it.week).dayOfWeek == DayOfWeek.MONDAY && it.text.length <= 10000) }
        require(state.reflections.map { it.week }.distinct().size == state.reflections.size)
        state.goals.forEach(::validateGoal)
        state.revisions.forEach { require(it.entryId in entries) }
        state.attachments.forEach {
            require(it.entryId in entries)
            require(it.path.matches(Regex("media/[a-zA-Z0-9_-]+\\.(wav|jpg|jpeg|png|webp|heic)"))) { "Invalid attachment path." }
            require(it.kind in setOf("audio", "photo") && it.bytes >= 0)
        }
        state.activities.forEach { require(it.goalId in goals && it.amount.isFinite() && it.amount >= 0); LocalDate.parse(it.day); require(it.sources.all(entries::containsKey)) }
        val reviewIds = state.reviews.map { it.id }.toSet()
        state.reviews.forEach { require(it.provider in setOf("local", "openai")); LocalDate.parse(it.day); require(it.sourceIds.orEmpty().all(entries::containsKey)); require(it.captions.all { c -> state.attachments.any { it.id == c.attachmentId } }) }
        state.suggestions.forEach { require(it.reviewId in reviewIds && it.goalId in goals && it.sources.all(entries::containsKey)); require(it.amount.isFinite() && it.amount >= 0) }
        state.items.forEach { item ->
            require(item.kind in setOf("todo", "idea") && item.title.isNotBlank() && item.title.length <= 200 && item.details.length <= 4000)
            require(item.sources.all(entries::containsKey))
        }
        state.proposals.forEach { p ->
            PlannerRules.validate(p.item); LocalDate.parse(p.day)
            require(p.reviewId in reviewIds && p.item.sources.all { entries[it]?.day == p.day })
            require(p.sourceFingerprint.matches(Regex("[a-f0-9]{64}")) && p.dedupeKey.matches(Regex("[a-f0-9]{64}")))
            require(p.status in setOf("pending", "accepted", "rejected", "superseded", "undone"))
            if (p.status == "accepted") require(if (p.item.kind == "goal") p.createdId in goals else state.items.any { it.id == p.createdId })
        }
        val ids = state.entries.map { it.id } + state.revisions.map { it.id } + state.attachments.map { it.id } +
            state.goals.map { it.id } + state.activities.map { it.id } + state.reviews.map { it.id } + state.suggestions.map { it.id } + state.items.map { it.id } + state.proposals.map { it.id } + state.reflections.map { it.id }
        require(ids.none { it.isBlank() } && ids.distinct().size == ids.size) { "Duplicate record identifiers." }
    }
}
