package dev.daybook.app.jobs
import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import dev.daybook.app.MainActivity
import dev.daybook.app.R
import dev.daybook.app.daybook
import dev.daybook.core.*
import kotlinx.coroutines.*
import java.time.*
import java.util.concurrent.TimeUnit

class Scheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private fun pending(action: String, code: Int) = PendingIntent.getBroadcast(context, code,
        Intent(context, ReminderReceiver::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    suspend fun reschedule() = withContext(Dispatchers.IO) {
        val settings = context.daybook.preferences.read()
        alarms.cancel(pending(REMIND, 10)); alarms.cancel(pending(SNOOZED, 11))
        val work = WorkManager.getInstance(context)
        if (settings.onboarded && settings.autoCleanup && ReviewPolicy.allowed(settings)) {
            val now = ZonedDateTime.now()
            val dueThrough = if (now.toLocalTime() >= LocalTime.parse(settings.cleanupTime)) now.toLocalDate() else now.toLocalDate().minusDays(1)
            val missed = work.getWorkInfosByTag("nightly-dispatch").get(10, TimeUnit.SECONDS)
                .filter { !it.state.isFinished }.flatMap { it.tags }
                .filter { it.startsWith("nightly-day:") }
                .mapNotNull { runCatching { LocalDate.parse(it.substringAfter(':')) }.getOrNull() }
                .filter { !it.isAfter(dueThrough) }.distinct()
            missed.forEach { context.daybook.cleanup.enqueue(it.toString(), provider = settings.reviewProvider) }
        }
        work.cancelAllWorkByTag("nightly-dispatch").result.get(10, TimeUnit.SECONDS)
        if (!settings.onboarded) return@withContext
        val now = ZonedDateTime.now()
        if (settings.reminders) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Schedule.nextReminder(now, settings).toInstant().toEpochMilli(), pending(REMIND, 10))
        scheduleNextCleanup()
    }
    suspend fun scheduleNextCleanup() {
        val settings = context.daybook.preferences.read()
        if (!settings.onboarded || !settings.autoCleanup || !ReviewPolicy.allowed(settings)) return
        val now = ZonedDateTime.now()
        val next = Schedule.nextCleanup(now, settings)
        val request = OneTimeWorkRequestBuilder<NightlyDispatchWorker>()
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("day" to next.toLocalDate().toString()))
            .addTag("nightly-dispatch").addTag("nightly-day:${next.toLocalDate()}").build()
        WorkManager.getInstance(context).enqueueUniqueWork("nightly-dispatch-${next.toLocalDate()}", ExistingWorkPolicy.KEEP, request)
    }
    suspend fun nextReminder() {
        val s = context.daybook.preferences.read()
        if (s.onboarded && s.reminders) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            Schedule.nextReminder(ZonedDateTime.now(), s).toInstant().toEpochMilli(), pending(REMIND, 10))
    }
    suspend fun snooze() {
        val s = context.daybook.preferences.read()
        if (!s.reminders || !s.onboarded) return
        val candidate = ZonedDateTime.now().plusMinutes(30)
        if (Schedule.awake(candidate.toLocalTime(), s)) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            candidate.toInstant().toEpochMilli(), pending(SNOOZED, 11))
        NotificationManagerCompat.from(context).cancel(20)
    }
    companion object {
        const val REMIND = "dev.daybook.REMIND"
        const val SNOOZE = "dev.daybook.SNOOZE"
        const val SNOOZED = "dev.daybook.SNOOZED"
    }
}
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        context.daybook.scope.launch {
            try {
                val graph = context.daybook
                if (intent?.action == Scheduler.SNOOZE) { graph.scheduler.snooze(); return@launch }
                val s = graph.preferences.read()
                graph.scheduler.nextReminder()
                if (!s.onboarded || !s.reminders || !Schedule.awake(LocalTime.now(), s)) return@launch
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel("journal_reminders", "Journal check-ins", NotificationManager.IMPORTANCE_DEFAULT))
                fun capture(kind: String, code: Int) = PendingIntent.getActivity(context, code,
                    Intent(context, MainActivity::class.java).putExtra("capture", kind).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val snooze = PendingIntent.getBroadcast(context, 23, Intent(context, ReminderReceiver::class.java).setAction(Scheduler.SNOOZE), PendingIntent.FLAG_IMMUTABLE)
                val notification = NotificationCompat.Builder(context, "journal_reminders").setSmallIcon(R.drawable.ic_daybook)
                    .setContentTitle("A moment for your day").setContentText("What have you been doing? Save a thought or a photo.")
                    .setContentIntent(capture("write", 24)).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .addAction(0, "Record", capture("voice", 21)).addAction(0, "Write", capture("write", 22))
                    .addAction(0, "Snooze 30 min", snooze).build()
                try { NotificationManagerCompat.from(context).notify(20, notification) } catch (_: SecurityException) { }
            } finally { pending.finish() }
        }
    }
}
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        context.daybook.scope.launch { try { context.daybook.scheduler.reschedule() } finally { pending.finish() } }
    }
}
class NightlyDispatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.daybook
        val s = graph.preferences.read()
        if (s.onboarded && s.autoCleanup && ReviewPolicy.allowed(s)) {
            val day = inputData.getString("day") ?: LocalDate.now().toString()
            graph.cleanup.enqueue(day, provider = s.reviewProvider, charging = s.cleanupChargingOnly)
        }
        // Enqueue the next date without cancelling this dispatch or the independent cleanup request.
        graph.scheduler.scheduleNextCleanup()
        return Result.success()
    }
}
