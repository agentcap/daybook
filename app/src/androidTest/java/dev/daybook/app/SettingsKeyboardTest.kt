package dev.daybook.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.daybook.core.Settings
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun setup() = runBlocking<Unit> {
        val graph = compose.activity.daybook
        check(compose.activity.packageName.endsWith(".verification"))
        graph.initialized.await()
        graph.keys.save("")
        graph.saveSettings(Settings(onboarded = true, reminders = false, autoCleanup = false))
        compose.waitUntil(10000) { compose.onAllNodesWithText("Settings", substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Settings", substring = false).performClick()
        compose.onNodeWithText("AI reflections").performClick()
    }
    @After fun cleanup() = runBlocking {
        compose.activity.daybook.keys.save("")
        compose.activity.daybook.saveSettings(Settings(onboarded = true, reminders = false, autoCleanup = false))
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    private fun imeVisible() = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    private fun waitIme(visible: Boolean) = compose.waitUntil(10000) { imeVisible() == visible }
    private fun aboveKeyboard(text: String) {
        compose.waitUntil(10000) {
            runCatching {
                compose.onNodeWithText(text, substring = false).assertIsDisplayed()
                val bounds = compose.onNodeWithText(text, substring = false).fetchSemanticsNode().boundsInWindow
                val window = compose.activity.window.decorView
                val inset = ViewCompat.getRootWindowInsets(window)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
                bounds.bottom <= window.height - inset + 1 && bounds.top >= 0
            }.getOrDefault(false)
        }
    }
    private fun shot() {
        if (InstrumentationRegistry.getArguments().getString("settingsScreenshot") != "true") return
        Thread.sleep(650); compose.waitForIdle()
        val file = File(compose.activity.getExternalFilesDir(null), "screenshots/37-openai-model-keyboard.png").apply { parentFile!!.mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun modelAndSaveStayAboveKeyboardWithoutManualDismissal() = runBlocking<Unit> {
        compose.onNodeWithText("OpenAI model").performScrollTo().performClick()
        compose.onNodeWithText("OpenAI model").performTextReplacement("ui-test-model")
        waitIme(true)
        aboveKeyboard("OpenAI model"); aboveKeyboard("Save model")
        compose.onAllNodesWithText("Settings", substring = false).assertCountEquals(0)
        shot()
        compose.onNodeWithText("Save model").performClick()
        compose.waitUntil(10000) { compose.activity.daybook.settings.value.model == "ui-test-model" }
        waitIme(false)
        compose.onNodeWithText("Settings", substring = false).assertIsDisplayed()
        assertEquals("ui-test-model", compose.activity.daybook.preferences.read().model)
    }
    @Test fun modelDraftSurvivesRecreationAndDoneSavesInLandscape() = runBlocking<Unit> {
        compose.onNodeWithText("OpenAI model").performScrollTo().performClick()
        compose.onNodeWithText("OpenAI model").performTextReplacement("ui-landscape-model")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("OpenAI model").performScrollTo().assertTextContains("ui-landscape-model")
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitUntil(15000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        compose.onNodeWithText("OpenAI model").performScrollTo().performClick()
        waitIme(true)
        compose.onNodeWithText("OpenAI model").performImeAction()
        compose.waitUntil(10000) { compose.activity.daybook.settings.value.model == "ui-landscape-model" }
        waitIme(false)
        assertEquals("ui-landscape-model", compose.activity.daybook.preferences.read().model)
    }
    @Test fun invalidModelShowsErrorBesideInputAndCanBeCorrected() {
        compose.onNodeWithText("OpenAI model").performScrollTo().performClick()
        compose.onNodeWithText("OpenAI model").performTextReplacement("invalid model name")
        waitIme(true)
        compose.onNodeWithText("OpenAI model").performImeAction()
        compose.onNodeWithText("Enter a valid model name.").assertIsDisplayed()
        assertEquals("gpt-5.4-mini", compose.activity.daybook.settings.value.model)
        compose.onNodeWithText("OpenAI model").performTextReplacement("ui-corrected-model")
        compose.onNodeWithText("Enter a valid model name.").assertDoesNotExist()
        compose.onNodeWithText("OpenAI model").performImeAction()
        compose.waitUntil(10000) { compose.activity.daybook.settings.value.model == "ui-corrected-model" }
        waitIme(false)
    }
    @Test fun keySaveAlsoStaysReachableWithKeyboardOpen() = runBlocking<Unit> {
        compose.onNodeWithText("OpenAI API key").performScrollTo().performClick()
        compose.onNodeWithText("OpenAI API key").performTextInput("synthetic-ui-key-not-real")
        waitIme(true)
        aboveKeyboard("Save key")
        compose.onNodeWithText("Save key").performClick()
        compose.waitUntil(10000) { compose.activity.daybook.keys.hasKey() }
        waitIme(false)
        compose.onNodeWithText("Remove key").performScrollTo().performClick()
        compose.waitUntil(10000) { !compose.activity.daybook.keys.hasKey() }
    }
}
