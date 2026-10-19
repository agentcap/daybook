package dev.daybook.app.security

import kotlinx.coroutines.flow.MutableStateFlow

/** One automatic attempt per foreground visit; cancellation leaves a manual retry. */
class JournalLockSession {
    val unlocked = MutableStateFlow(false)
    var enabled = false; private set
    var prompting = false; private set
    private var attempted = false
    private var foreground = false

    fun configure(value: Boolean) {
        if (enabled != value) { enabled = value; unlocked.value = false; attempted = false }
    }
    fun resume() { foreground = true }
    fun begin(manual: Boolean = false): Boolean {
        if (!foreground || !enabled || unlocked.value || prompting || (attempted && !manual)) return false
        attempted = true; prompting = true
        return true
    }
    fun finish(success: Boolean) {
        prompting = false; unlocked.value = success
        if (!foreground && !success) attempted = false
    }
    fun stop(changingConfiguration: Boolean) {
        foreground = false
        // Device-credential authentication can itself temporarily stop the activity.
        if (!changingConfiguration && !prompting) { unlocked.value = false; attempted = false }
    }
}
