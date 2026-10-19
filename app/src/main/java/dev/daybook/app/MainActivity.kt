package dev.daybook.app
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import dev.daybook.app.ui.DaybookRoot
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : FragmentActivity() {
    val capture = MutableStateFlow("")
    val lockEpoch = MutableStateFlow(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        capture.value = intent.getStringExtra("capture") ?: ""
        intent.removeExtra("capture")
        setContent { DaybookRoot(daybook, this) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        capture.value = intent.getStringExtra("capture") ?: ""
        intent.removeExtra("capture")
    }
    override fun onStop() { lockEpoch.value += 1; super.onStop() }
}
