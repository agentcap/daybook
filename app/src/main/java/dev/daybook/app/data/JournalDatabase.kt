package dev.daybook.app.data

import android.content.Context
import androidx.room.*
import dev.daybook.core.*
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Entity(tableName = "documents", indices = [Index("kind"), Index("day")])
data class DocumentRow(@PrimaryKey val id: String, val kind: String, val day: String, val json: String)
@Dao interface DocumentDao {
    @Query("SELECT * FROM documents") fun observe(): Flow<List<DocumentRow>>
    @Query("SELECT * FROM documents") suspend fun all(): List<DocumentRow>
    @Upsert suspend fun put(rows: List<DocumentRow>)
    @Query("DELETE FROM documents WHERE id IN (:ids)") suspend fun remove(ids: List<String>)
}
@Database(entities = [DocumentRow::class], version = 1, exportSchema = true)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
    companion object {
        fun open(context: Context, name: String = "daybook.sqlite") = Room.databaseBuilder(context, JournalDatabase::class.java, name).build()
    }
}

interface JournalStorage {
    val changes: Flow<Snapshot>
    suspend fun read(): Snapshot
    suspend fun change(transform: (Snapshot) -> Snapshot)
}
class JournalRepository(private val db: JournalDatabase) : JournalStorage {
    private val gson = Gson()
    private val lock = Mutex()
    private val dao = db.documents()
    override val changes: Flow<Snapshot> = dao.observe().map(::decode)
    override suspend fun read(): Snapshot = decode(dao.all())
    override suspend fun change(transform: (Snapshot) -> Snapshot) = lock.withLock {
        db.withTransaction {
            val old = dao.all()
            val next = transform(decode(old))
            JournalRules.validateSnapshot(next)
            val rows = encode(next)
            val previous = old.associateBy { it.id }
            dao.put(rows.filter { previous[it.id] != it })
            val retained = rows.map { it.id }.toSet()
            old.map { it.id }.filter { it !in retained }.chunked(500).forEach { dao.remove(it) }
        }
    }
    suspend fun addText(day: String, text: String): Entry {
        require(text.isNotBlank()) { "Write something before saving." }
        val entry = Entry(day = day, originalText = text.trim())
        change { it.copy(entries = it.entries + entry) }
        return entry
    }
    suspend fun editEntry(id: String, text: String) = change { s ->
        val old = s.entries.first { it.id == id }
        require(old.deletedAt == 0L) { "Restore this note before editing." }
        require(old.status != "recording") { "Stop recording before editing." }
        s.copy(entries = s.entries.map { if (it.id == id) it.copy(text = text, status = if (it.kind == "voice" && text.isNotBlank()) "ready" else it.status, error = if (text.isNotBlank()) "" else it.error) else it },
            revisions = s.revisions + Revision(entryId = id, text = old.text))
    }
    suspend fun addGoal(goal: Goal) = change { s -> JournalRules.validateGoal(goal); s.copy(goals = s.goals + goal) }
    suspend fun logActivity(activity: Activity) = change { s ->
        require(s.goals.any { it.id == activity.goalId && !it.archived }) { "Choose an active goal." }
        require(activity.amount.isFinite() && activity.amount >= 0 && activity.amount <= 1e9) { "Enter a valid amount." }
        require(activity.note.isNotBlank() || activity.amount > 0) { "Describe the effort or enter an amount." }
        s.copy(activities = s.activities + activity)
    }
    suspend fun undo(id: String) = change { s -> s.copy(activities = s.activities.map { if (it.id == id) it.copy(undone = true) else it }) }
    suspend fun reject(id: String) = change { s -> s.copy(suggestions = s.suggestions.map { if (it.id == id && it.status == "pending") it.copy(status = "rejected") else it }) }
    suspend fun confirm(id: String, amount: Double, note: String) = change { JournalRules.confirm(it, id, amount, note) }
    suspend fun saveItem(item: ListItem) = change { s ->
        require(item.kind in setOf("todo", "idea") && item.title.isNotBlank())
        s.copy(items = s.items.filterNot { it.id == item.id } + item)
    }
    suspend fun rejectProposal(id: String) = change { s -> s.copy(proposals = s.proposals.map { if (it.id == id && it.status == "pending") it.copy(status = "rejected") else it }) }
    suspend fun approveProposal(id: String, title: String, details: String, goal: Goal? = null) = change { PlannerRules.approve(it, id, title, details, goal) }
    suspend fun undoProposal(id: String) = change { PlannerRules.undo(it, id) }
    suspend fun archive(id: String) = change { s -> s.copy(goals = s.goals.map { if (it.id == id) it.copy(archived = true) else it }) }
    suspend fun trash(id: String) = change { RecoveryRules.trash(it, id) }
    suspend fun restoreEntry(id: String) = change { RecoveryRules.restore(it, id) }
    /** Remove only an unfinished voice draft; earlier reviews for the day remain intact. */
    suspend fun discardRecording(id: String): List<Attachment> {
        var removed = emptyList<Attachment>()
        change { state ->
            val entry = state.entries.find { it.id == id } ?: return@change state
            require(entry.kind == "voice" && entry.status in setOf("recording", "interrupted")) { "This recording has already been saved." }
            removed = state.attachments.filter { it.entryId == id }
            state.copy(entries = state.entries.filterNot { it.id == id },
                attachments = state.attachments.filterNot { it.entryId == id },
                revisions = state.revisions.filterNot { it.entryId == id })
        }
        return removed
    }
    suspend fun deleteEntry(id: String): List<Attachment> {
        var removed = emptyList<Attachment>()
        change { s ->
            val entry = s.entries.first { it.id == id }
            require(entry.status != "recording") { "Stop recording before deleting." }
            removed = s.attachments.filter { it.entryId == id }
            val reviewIds = s.reviews.filter { it.day == entry.day }.map { it.id }.toSet()
            s.copy(entries = s.entries.filterNot { it.id == id }, revisions = s.revisions.filterNot { it.entryId == id },
                attachments = s.attachments.filterNot { it.entryId == id }, reviews = s.reviews.filterNot { it.id in reviewIds },
                suggestions = s.suggestions.filterNot { it.reviewId in reviewIds },
                proposals = s.proposals.filterNot { it.reviewId in reviewIds },
                items = s.items.map { if (id in it.sources) it.copy(sources = it.sources - id) else it },
                activities = s.activities.map { if (id in it.sources) it.copy(sources = it.sources - id) else it })
        }
        return removed
    }
    private inline fun <reified T> List<DocumentRow>.of(kind: String): List<T> = filter { it.kind == kind }.map { gson.fromJson(it.json, T::class.java) }
    private fun decode(rows: List<DocumentRow>) = Snapshot(rows.of("entry"), rows.of("revision"), rows.of("attachment"),
        rows.of("goal"), rows.of("activity"), rows.of("review"), rows.of("suggestion"), rows.of("list_item"), rows.of("proposal"), rows.of("week_reflection"))
    private fun encode(s: Snapshot): List<DocumentRow> = buildList {
        s.reflections.forEach { add(DocumentRow(it.id, "week_reflection", it.week, gson.toJson(it))) }
        s.entries.forEach { add(DocumentRow(it.id, "entry", it.day, gson.toJson(it))) }
        s.revisions.forEach { add(DocumentRow(it.id, "revision", "", gson.toJson(it))) }
        s.attachments.forEach { add(DocumentRow(it.id, "attachment", "", gson.toJson(it))) }
        s.goals.forEach { add(DocumentRow(it.id, "goal", "", gson.toJson(it))) }
        s.activities.forEach { add(DocumentRow(it.id, "activity", it.day, gson.toJson(it))) }
        s.reviews.forEach { add(DocumentRow(it.id, "review", it.day, gson.toJson(it))) }
        s.suggestions.forEach { add(DocumentRow(it.id, "suggestion", it.day, gson.toJson(it))) }
        s.items.forEach { add(DocumentRow(it.id, "list_item", "", gson.toJson(it))) }
        s.proposals.forEach { add(DocumentRow(it.id, "proposal", it.day, gson.toJson(it))) }
    }
}
