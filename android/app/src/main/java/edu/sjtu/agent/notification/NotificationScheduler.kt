package edu.sjtu.agent.notification

import edu.sjtu.agent.data.DeadlineItem
import edu.sjtu.agent.data.Reminder

interface NotificationScheduler {
    suspend fun scheduleDeadlineGuards(items: List<DeadlineItem>)
    suspend fun scheduleReminder(reminder: Reminder)
    suspend fun cancelReminder(id: Long)
}

class LocalNotificationScheduler : NotificationScheduler {
    override suspend fun scheduleDeadlineGuards(items: List<DeadlineItem>) {
        // FCM and background delivery are intentionally reserved for a later slice.
    }

    override suspend fun scheduleReminder(reminder: Reminder) {
        // First release keeps local reminder persistence; notification delivery is the next slice.
    }

    override suspend fun cancelReminder(id: Long) {
        // No-op until background notification delivery is enabled.
    }
}
