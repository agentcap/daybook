package dev.daybook.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import dev.daybook.app.security.JournalLockSession
import dev.daybook.app.ui.DaybookRoot
import dev.daybook.app.widget.CaptureIntents
import kotlinx.coroutines.flow.MutableStateFlow

class JournalLockViewModel : ViewModel() { val session = JournalLockSession() }

class MainActivity : FragmentActivity() {
    val capture = MutableStateFlow("")
    private val lockModel by viewModels<JournalLockViewModel>()
    val journalLock get() = lockModel.session
    private lateinit var biometricPrompt: BiometricPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { journalLock.finish(true) }
                override fun onAuthenticationError(code: Int, message: CharSequence) { journalLock.finish(false) }
            })
        capture.value = savedInstanceState?.getString("pendingCapture") ?: CaptureIntents.captureFrom(this, intent)
        intent.removeExtra("capture"); intent.removeExtra("widget_token")
        setContent { DaybookRoot(daybook, this) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        capture.value = CaptureIntents.captureFrom(this, intent)
        intent.removeExtra("capture"); intent.removeExtra("widget_token")
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingCapture", capture.value)
        super.onSaveInstanceState(outState)
    }
    fun configureJournalLock(enabled: Boolean) {
        val wasEnabled = journalLock.enabled
        journalLock.configure(enabled)
        if (wasEnabled && !enabled) biometricPrompt.cancelAuthentication()
        authenticateJournal()
    }
    fun authenticateJournal(manual: Boolean = false) {
        if (!journalLock.begin(manual)) return
        try {
            biometricPrompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Daybook")
                .setSubtitle("Use your fingerprint or device screen lock")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build())
        } catch (_: Exception) { journalLock.finish(false) }
    }
    override fun onResume() {
        super.onResume()
        journalLock.resume()
        if (::biometricPrompt.isInitialized) authenticateJournal()
    }
    override fun onStop() {
        journalLock.stop(isChangingConfigurations)
        super.onStop()
    }
}
