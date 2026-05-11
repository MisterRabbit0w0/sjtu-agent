package edu.sjtu.agent.data.local

import edu.sjtu.agent.data.AppSettings
import edu.sjtu.agent.data.Reminder
import edu.sjtu.agent.data.SetupStatus

interface CredentialStore {
    suspend fun getSettings(): AppSettings
    suspend fun saveSettings(settings: AppSettings)

    suspend fun getSecret(key: String): String
    suspend fun putSecret(key: String, value: String)
    suspend fun removeSecret(key: String)

    suspend fun getCookies(scope: String): Map<String, String>
    suspend fun putCookies(scope: String, cookies: Map<String, String>)

    suspend fun getReminders(): List<Reminder>
    suspend fun saveReminders(reminders: List<Reminder>)

    suspend fun clearAll()
    suspend fun status(): SetupStatus
}
