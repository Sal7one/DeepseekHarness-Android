package io.github.jackwener.dsh.core.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.model.ToolGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.store by preferencesDataStore("settings")

data class Settings(
    val onboarded: Boolean = false,
    val model: String = "deepseek-flash",
    val enabledGroups: Set<ToolGroup> = emptySet(),
    val notificationApps: Set<String> = emptySet(),
    val floatingBall: Boolean = false,
    val dailyTokenLimit: Long = 0,
    val sessionTokenLimit: Long = 0,
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val model = stringPreferencesKey("model")
        val groups = stringSetPreferencesKey("groups")
        val notifApps = stringSetPreferencesKey("notif_apps")
        val ball = booleanPreferencesKey("floating_ball")
        val dailyTokens = longPreferencesKey("daily_tokens")
        val sessionTokens = longPreferencesKey("session_tokens")
    }

    val settings: Flow<Settings> = context.store.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    private fun Preferences.toSettings() = Settings(
        onboarded = this[K.onboarded] ?: false,
        model = this[K.model] ?: "deepseek-flash",
        enabledGroups = (this[K.groups] ?: emptySet()).mapNotNull(ToolGroup::of).toSet(),
        notificationApps = this[K.notifApps] ?: emptySet(),
        floatingBall = this[K.ball] ?: false,
        dailyTokenLimit = this[K.dailyTokens] ?: 0,
        sessionTokenLimit = this[K.sessionTokens] ?: 0,
    )

    suspend fun setOnboarded() = context.store.edit { it[K.onboarded] = true }
    suspend fun setModel(model: String) = context.store.edit { it[K.model] = model }
    suspend fun setGroups(groups: Set<ToolGroup>) = context.store.edit { it[K.groups] = groups.map { g -> g.id }.toSet() }
    suspend fun setGroupEnabled(group: ToolGroup, enabled: Boolean) = context.store.edit {
        val cur = it[K.groups] ?: emptySet()
        it[K.groups] = if (enabled) cur + group.id else cur - group.id
    }
    suspend fun setNotificationApps(apps: Set<String>) = context.store.edit { it[K.notifApps] = apps }
    suspend fun setFloatingBall(on: Boolean) = context.store.edit { it[K.ball] = on }
    suspend fun setLimits(daily: Long, session: Long) = context.store.edit {
        it[K.dailyTokens] = daily; it[K.sessionTokens] = session
    }
}
