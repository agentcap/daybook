package dev.daybook.app
import android.graphics.*
import androidx.activity.compose.setContent
import dev.daybook.app.ui.*
import dev.daybook.app.capture.RecordingSession
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.daybook.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*

@RunWith(AndroidJUnit4::class)
class ScreenshotTour {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun shot(name: String) {
        compose.waitForIdle()
        // Native dialog/window transitions run outside Compose's test clock.
        Thread.sleep(450)
        val context = compose.activity
        val target = File(context.getExternalFilesDir("screenshots"), "$name.png").apply { parentFile!!.mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap -> target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    @Test fun captureSyntheticScreens() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        check(compose.activity.packageName.endsWith(".verification"))
        val graph = compose.activity.daybook
        runBlocking { graph.initialized.await(); graph.preferences.save(Settings()) }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Make room for your day.").fetchSemanticsNodes().isNotEmpty() }; shot("01-onboarding")
        val day = LocalDate.now().toString()
        val baseTime = LocalDate.now().atTime(8, 15).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val entries = listOf(
            Entry(id = "sample-gym", day = day, created = baseTime, kind = "voice", originalText = "Made it to the gym this morning. Felt more focused afterwards."),
            Entry(id = "sample-lunch", day = day, created = baseTime + 4 * 3600000, kind = "photo", originalText = "Salmon, rice and greens for lunch. Around 780 calories."),
            Entry(id = "sample-learning", day = day, created = baseTime + 7 * 3600000, originalText = "Spent 25 minutes learning how attention works. Started an outline for my next blog."))
        val goals = listOf(Goal(id = "sample-gym-goal", title = "Make time for the gym", period = "weekly", target = 3.0),
            Goal(id = "sample-calorie-goal", title = "Eat 3000 calories", target = 3000.0, unit = "kcal", checklist = false),
            Goal(id = "sample-blog-goal", title = "Write a new blog", period = "once"),
            Goal(id = "sample-learning-goal", title = "Learn something new"))
        val activities = listOf(Activity(goalId = goals[0].id, day = day, amount = 1.0, note = "Morning gym session"),
            Activity(goalId = goals[1].id, day = day, amount = 780.0, note = "Lunch, confirmed estimate"),
            Activity(goalId = goals[3].id, day = day, amount = 1.0, note = "25 minutes on attention"))
        val path = graph.media.newPath("jpg")
        val bitmap = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(230, 218, 195)); paint.color = Color.rgb(252, 248, 239); canvas.drawOval(110f, 30f, 690f, 470f, paint)
        paint.color = Color.rgb(224, 147, 106); canvas.drawRoundRect(240f, 130f, 490f, 290f, 25f, 25f, paint)
        paint.color = Color.rgb(80, 120, 69)
        listOf(180f to 200f, 250f to 340f, 560f to 320f, 550f to 170f).forEach { (x,y) -> canvas.drawCircle(x, y, 52f, paint) }
        paint.color = Color.rgb(235, 226, 195); canvas.drawOval(370f, 290f, 570f, 400f, paint)
        graph.media.file(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        val attachment = Attachment(id = "sample-photo", entryId = entries[1].id, path = path, kind = "photo", bytes = graph.media.file(path).length())
        val state = Snapshot(entries = entries, goals = goals, activities = activities, attachments = listOf(attachment))
        val review = Review(day = day, fingerprint = JournalRules.fingerprint(state, day),
            journal = "I started the day with a gym session and felt more focused afterwards. Lunch was salmon, rice and greens—about 780 calories. Later, I spent 25 minutes learning how attention works and began outlining my next blog post.",
            summary = "Movement, a nourishing lunch, and a little space to learn. Three goals moved forward today.",
            captions = listOf(PhotoCaption(attachment.id, "Illustrated sample lunch: salmon, rice and greens.")), model = "synthetic preview")
        runBlocking { graph.repository.change { state.copy(reviews = listOf(review)) }; graph.preferences.save(Settings(onboarded = true, reminders = false, autoCleanup = false)) }
        compose.waitUntil(10000) { graph.state.value.entries.size == 3 }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Your day, captured.").fetchSemanticsNodes().isNotEmpty() }; shot("02-today")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Your daily reflection"))
        shot("03-review")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Salmon, rice and greens for lunch. Around 780 calories."))
        shot("08-moments")
        compose.onNodeWithText("Goals").performClick(); shot("04-goals")
        compose.onNodeWithText("History").performClick(); shot("05-history")
        compose.onNodeWithText("Weekly reflection").performClick(); shot("22-weekly-reflection")
        compose.onNodeWithText("Back to journal").performClick()
        compose.onNodeWithText("Calendar", substring = false).performClick(); shot("13-calendar")
        compose.onNodeWithText("Stats", substring = false).performClick(); shot("14-stats")
        val planning = Entry(id = "sample-plans", day = day, originalText = "I want to learn Italian. I need to book a dentist visit. Maybe I could try pottery.")
        runBlocking { graph.repository.change { current ->
            val next = current.copy(entries = current.entries + planning,
                items = listOf(ListItem(kind = "todo", title = "Order groceries", details = "Vegetables, rice, and fruit"),
                    ListItem(kind = "idea", title = "Try a new trail", details = "A slow Sunday walk somewhere new")))
            PlannerRules.proposals(next, next.reviews.single(), listOf(
                ProposedItem("goal", "Learn Italian", "Make a little room for a new language.", listOf(planning.id)),
                ProposedItem("todo", "Book a dentist visit", "Call to arrange an appointment.", listOf(planning.id)),
                ProposedItem("idea", "Try pottery", "A possible weekend class to explore.", listOf(planning.id))))
        } }
        compose.onNodeWithText("Lists", substring = false).performClick(); shot("15-todos")
        compose.onNodeWithText("Ideas", substring = false).performClick(); shot("16-ideas")
        compose.onNodeWithText("Suggestions", substring = true).performClick(); shot("17-suggestions")
        compose.onNodeWithText("Settings").performClick(); shot("06-settings")
        compose.onNodeWithText("Processing & downloads").performScrollTo().performClick(); shot("24-processing")
        compose.onNodeWithText("Back to journal").performClick()
        compose.onNodeWithText("Backups & storage").performScrollTo().performClick()
        compose.onNodeWithText("Automatic backups & recovery").performScrollTo().performClick(); shot("23-backup-recovery")
        compose.onNodeWithText("Back to journal").performClick()
        compose.onNodeWithText("All settings").performScrollTo().performClick()
        compose.onNodeWithText("Privacy policy & feedback").performScrollTo().performClick(); shot("25-privacy-feedback")
        compose.onNodeWithText("Feedback draft", substring = false).performScrollTo(); shot("27-feedback")
        compose.onNodeWithText("Back to journal").performScrollTo().performClick()
        compose.onNodeWithText("Reminders & rhythm").performScrollTo().performClick(); shot("09-rhythm")
        compose.onNodeWithText("All settings").performScrollTo().performClick()
        compose.onNodeWithText("Privacy & lock").performScrollTo().performClick(); shot("10-privacy")
        compose.onNodeWithText("All settings").performScrollTo().performClick()
        compose.onNodeWithText("Home-screen widgets").performScrollTo().performClick(); shot("11-widget")
        compose.onNodeWithText("All settings").performScrollTo().performClick()
        compose.onNodeWithText("AI reflections").performScrollTo().performClick()
        compose.onNodeWithText("On-device", substring = false).performClick()
        compose.waitUntil(10000) { graph.settings.value.reviewProvider == "local" }
        shot("19-review-provider")
        compose.onNodeWithText("Download is checked before installation. The model is excluded from backups. Daybook never switches to OpenAI automatically.").performScrollTo()
        shot("20-local-model")
        runBlocking { graph.saveSettings(graph.preferences.read().copy(reviewProvider = "openai", theme = "dark")) }
        compose.onNodeWithText("Today", substring = false).performClick()
        compose.waitUntil(10000) { graph.settings.value.theme == "dark" }; compose.waitForIdle(); shot("07-dark")
        compose.runOnUiThread { compose.activity.setContent {
            DaybookTheme(false) {
                RecordingScreen(RecordingSession(elapsedMs = 42000, level = 0.45f, phase = "recording",
                    transcript = "Made it to the gym this morning. I felt a little more focused afterwards.\n\nI’d like to spend some time learning Italian this week, and maybe try a pottery class on the weekend.",
                    previewStatus = "Synthetic transcript for the interface preview."), true, true, {}, {}, {})
            }
        } }
        shot("28-recording-preview")
    }
}
