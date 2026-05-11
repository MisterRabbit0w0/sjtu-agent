package edu.sjtu.agent.data.local

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import edu.sjtu.agent.data.AppSettings
import edu.sjtu.agent.data.Reminder
import edu.sjtu.agent.data.SetupStatus
import edu.sjtu.agent.util.LenientJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class EncryptedCredentialStore(context: Context) : CredentialStore {
    private val appContext = context.applicationContext
    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val prefs = EncryptedSharedPreferences.create(
        appContext,
        "sjtu_agent_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override suspend fun getSettings(): AppSettings = withContext(Dispatchers.IO) {
        prefs.getString(KEY_SETTINGS, null)?.let {
            runCatching { LenientJson.decodeFromString<AppSettings>(it) }.getOrNull()
        } ?: AppSettings()
    }

    override suspend fun saveSettings(settings: AppSettings) = withContext(Dispatchers.IO) {
        prefs.edit { putString(KEY_SETTINGS, LenientJson.encodeToString(settings)) }
    }

    override suspend fun getSecret(key: String): String = withContext(Dispatchers.IO) {
        prefs.getString("secret.$key", "").orEmpty()
    }

    override suspend fun putSecret(key: String, value: String) = withContext(Dispatchers.IO) {
        prefs.edit { putString("secret.$key", value) }
    }

    override suspend fun removeSecret(key: String) = withContext(Dispatchers.IO) {
        prefs.edit { remove("secret.$key") }
    }

    override suspend fun getCookies(scope: String): Map<String, String> = withContext(Dispatchers.IO) {
        val raw = prefs.getString("cookies.$scope", "{}").orEmpty()
        runCatching {
            LenientJson.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                raw,
            )
        }.getOrElse { emptyMap() }
    }

    override suspend fun putCookies(scope: String, cookies: Map<String, String>) = withContext(Dispatchers.IO) {
        val raw = LenientJson.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            cookies,
        )
        prefs.edit { putString("cookies.$scope", raw) }
    }

    override suspend fun getReminders(): List<Reminder> = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_REMINDERS, "[]").orEmpty()
        runCatching {
            LenientJson.decodeFromString(ListSerializer(Reminder.serializer()), raw)
        }.getOrElse { emptyList() }
    }

    override suspend fun saveReminders(reminders: List<Reminder>) = withContext(Dispatchers.IO) {
        prefs.edit {
            putString(KEY_REMINDERS, LenientJson.encodeToString(ListSerializer(Reminder.serializer()), reminders))
        }
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        prefs.edit { clear() }
    }

    override suspend fun status(): SetupStatus = withContext(Dispatchers.IO) {
        SetupStatus(
            hasCanvasToken = !prefs.getString("secret.canvas_token", "").isNullOrBlank(),
            hasAihaokeCookie = getCookiesBlocking("aihaoke")["haoke-token"].isNullOrBlank().not(),
            hasJwxtCookie = getCookiesBlocking("jwxt").isNotEmpty(),
            hasPhycaiCookie = getCookiesBlocking("phycai").isNotEmpty(),
            hasIcourseCookie = getCookiesBlocking("icourse").isNotEmpty(),
            hasLlmKey = !prefs.getString("secret.llm_api_key", "").isNullOrBlank(),
            hasShuiyuanCredential = !prefs.getString("secret.shuiyuan_api_key", "").isNullOrBlank() ||
                getCookiesBlocking("shuiyuan").isNotEmpty(),
            hasDywebToken = !prefs.getString("secret.dyweb_token", "").isNullOrBlank() ||
                getCookiesBlocking("dyweb")["sjtu_token"].isNullOrBlank().not(),
        )
    }

    private fun getCookiesBlocking(scope: String): Map<String, String> {
        val raw = prefs.getString("cookies.$scope", "{}").orEmpty()
        return runCatching {
            LenientJson.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                raw,
            )
        }.getOrElse { emptyMap() }
    }

    private companion object {
        const val KEY_SETTINGS = "settings"
        const val KEY_REMINDERS = "reminders"
    }
}
