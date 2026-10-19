package dev.daybook.app

import dev.daybook.app.security.JournalLockSession
import org.junit.Assert.*
import org.junit.Test

class JournalLockSessionTest {
    @Test fun opensAutomaticallyOnceAndCancellationAllowsManualRetry() {
        val s = JournalLockSession()
        s.configure(true); assertFalse(s.begin())
        s.resume(); assertTrue(s.begin()); assertFalse(s.begin())
        s.finish(false); assertFalse(s.begin()); assertTrue(s.begin(manual = true))
        s.finish(true); assertTrue(s.unlocked.value); assertFalse(s.begin(manual = true))
        s.stop(false); assertFalse(s.unlocked.value)
        s.resume(); assertTrue(s.begin())
    }
    @Test fun credentialActivityAndRotationDoNotCreateDuplicatePrompts() {
        val s = JournalLockSession()
        s.configure(true); s.resume(); assertTrue(s.begin())
        s.stop(false); s.resume(); assertFalse(s.begin())
        s.finish(true); s.stop(true); s.resume()
        assertTrue(s.unlocked.value); assertFalse(s.begin())
        s.stop(false); s.resume(); assertTrue(s.begin())
    }
    @Test fun backgroundCancellationPromptsOnNextVisitAndDisabledLockDoesNotPrompt() {
        val s = JournalLockSession()
        s.configure(true); s.resume(); s.begin(); s.stop(false); s.finish(false)
        s.resume(); assertTrue(s.begin())
        s.finish(false); s.configure(false); assertFalse(s.begin())
    }
}
