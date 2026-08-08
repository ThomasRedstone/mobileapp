package coredevices.ring.agent.builtin_servlets.reminders

import PlatformUiContext
import co.touchlab.kermit.Logger
import coredevices.ring.agent.integrations.ItemSource
import coredevices.ring.agent.integrations.ReminderListEntry
import coredevices.ring.data.entity.room.reminders.LocalReminderData
import coredevices.ring.database.room.RingDatabase
import coredevices.ring.database.room.repository.ListRepository
import coredevices.ring.service.GenericNotification
import coredevices.ring.service.PlatformIndexNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Desktop counterpart to [AndroidBuiltInReminderIntegration]: records each reminder in
 * [LocalReminderData] and fires it through [PlatformIndexNotificationManager].
 *
 * There is no desktop equivalent of AlarmManager / UNUserNotificationCenter, so pending reminders
 * are held as in-process timers: anything still pending when the app exits must be re-scheduled
 * from the [LocalReminderData] rows on next start.
 */
class JvmBuiltInReminderIntegration : BuiltInReminderIntegration, KoinComponent {
    private val db: RingDatabase by inject()
    private val feedItems: BuiltInReminderFeedItems by inject()
    private val listRepository: ListRepository by inject()
    private val notifications: PlatformIndexNotificationManager by inject()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val timersMutex = Mutex()
    private val timers = mutableMapOf<Int, Job>()

    override suspend fun createReminder(
        title: String,
        deadline: Instant?,
        listId: String?,
        notifyBefore: Duration?,
        source: ItemSource?,
    ): String {
        require(deadline == null || deadline > Clock.System.now()) { "Time must be in the future" }

        val id = db.localReminderDao().insertReminder(
            LocalReminderData(
                0,
                deadline,
                title,
                recordingId = source?.recordingFirestoreId,
                notifyBeforeMillis = notifyBefore?.inWholeMilliseconds)
        ).toInt()

        deadline?.let { time ->
            schedule(id, title, time, isPreNotification = false)
            notifyBefore?.let { lead ->
                val preTime = time - lead
                if (preTime > Clock.System.now()) {
                    schedule(id, title, preTime, isPreNotification = true)
                }
            }
        }
        try {
            feedItems.createFeedItem(id, title, deadline, listId, notifyBefore, source)
        } catch (e: Exception) {
            cancel(id, isPreNotification = false)
            cancel(id, isPreNotification = true)
            db.localReminderDao().deleteReminder(id)
            throw e
        }
        return id.toString()
    }

    override suspend fun searchForList(listName: String): List<ReminderListEntry> =
        feedItems.searchForList(listName)

    override suspend fun cancelReminder(reminderId: Int) {
        db.localReminderDao().getReminder(reminderId) ?: return
        cancel(reminderId, isPreNotification = false)
        cancel(reminderId, isPreNotification = true)
        db.localReminderDao().deleteReminder(reminderId)
    }

    override suspend fun rescheduleReminder(reminderId: Int, expectedRecordingId: String?, newTime: Instant?) {
        val reminder = db.localReminderDao().getReminder(reminderId) ?: return
        if (reminder.recordingId != expectedRecordingId) return

        cancel(reminderId, isPreNotification = false)
        cancel(reminderId, isPreNotification = true)
        db.localReminderDao().setTime(reminderId, newTime)

        if (newTime == null || newTime <= Clock.System.now()) return
        schedule(reminderId, reminder.message, newTime, isPreNotification = false)
        reminder.notifyBeforeMillis?.let { lead ->
            val preTime = newTime - lead.milliseconds
            if (preTime > Clock.System.now()) {
                schedule(reminderId, reminder.message, preTime, isPreNotification = true)
            }
        }
    }

    override suspend fun cancelExtraNotification(reminderId: Int) {
        db.localReminderDao().getReminder(reminderId) ?: return
        cancel(reminderId, isPreNotification = true)
        db.localReminderDao().clearNotifyBefore(reminderId)
    }

    override suspend fun getAllLists(): List<ReminderListEntry> {
        val lists = listRepository.getAllFlow().first()
        return lists.map { ReminderListEntry(it.firestoreId, it.title) }
    }

    // Built-in reminders need no account.
    override suspend fun signIn(uiContext: PlatformUiContext): Boolean = true
    override suspend fun unlink() {}
    override suspend fun isAuthorized(): Boolean = true

    private suspend fun schedule(
        reminderId: Int,
        message: String,
        triggerTime: Instant,
        isPreNotification: Boolean,
    ) {
        val notificationId = notificationId(reminderId, isPreNotification)
        val job = scope.launch {
            delay(triggerTime - Clock.System.now())
            notifications.notify(
                GenericNotification(
                    id = notificationId,
                    title = if (isPreNotification) "Upcoming reminder" else "Reminder",
                    contentText = message,
                )
            )
            timersMutex.withLock { timers.remove(notificationId) }
        }
        timersMutex.withLock { timers.put(notificationId, job) }?.cancel()
        logger.d { "Scheduled reminder $reminderId for $triggerTime (pre=$isPreNotification)" }
    }

    private suspend fun cancel(reminderId: Int, isPreNotification: Boolean) {
        val notificationId = notificationId(reminderId, isPreNotification)
        timersMutex.withLock { timers.remove(notificationId) }?.cancel()
        notifications.cancel(notificationId)
    }

    companion object {
        private val logger = Logger.withTag("JvmBuiltInReminderIntegration")

        private const val NOTIFICATION_ID_BASE_REMINDER = 10
        private const val NOTIFICATION_ID_BASE_REMINDER_PRE = 1_000_000

        private fun notificationId(reminderId: Int, isPreNotification: Boolean) =
            if (isPreNotification) NOTIFICATION_ID_BASE_REMINDER_PRE + reminderId
            else NOTIFICATION_ID_BASE_REMINDER + reminderId
    }
}
