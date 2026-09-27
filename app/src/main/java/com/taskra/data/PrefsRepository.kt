package com.taskra.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.prefsStore by preferencesDataStore(name = "taskra_prefs")

@Serializable
data class UserPrefs(
    val mottoText: String = "先做好眼前这一件。",
    val mottoVisible: Boolean = true,
    val themeMode: String = ThemeMode.SYSTEM,
    val sortMode: String = SortMode.DEADLINE,
    val currentSemesterId: String? = null,
    val lastBackupTime: Long = 0L,
)

class PrefsRepository(private val context: Context) {
    private val K_MOTTO = stringPreferencesKey("motto_text")
    private val K_MOTTO_VISIBLE = booleanPreferencesKey("motto_visible")
    private val K_THEME = stringPreferencesKey("theme_mode")
    private val K_SORT = stringPreferencesKey("sort_mode")
    private val K_SEMESTER = stringPreferencesKey("current_semester")
    private val K_BACKUP = longPreferencesKey("last_backup_time")

    val prefs: Flow<UserPrefs> = context.prefsStore.data.map { p ->
        UserPrefs(
            mottoText = p[K_MOTTO] ?: "先做好眼前这一件。",
            mottoVisible = p[K_MOTTO_VISIBLE] ?: true,
            themeMode = p[K_THEME] ?: ThemeMode.SYSTEM,
            sortMode = p[K_SORT] ?: SortMode.DEADLINE,
            currentSemesterId = p[K_SEMESTER],
            lastBackupTime = p[K_BACKUP] ?: 0L,
        )
    }

    suspend fun setMotto(text: String) {
        context.prefsStore.edit { it[K_MOTTO] = text.take(2000) }
    }
    suspend fun setMottoVisible(v: Boolean) {
        context.prefsStore.edit { it[K_MOTTO_VISIBLE] = v }
    }
    suspend fun setTheme(mode: String) {
        context.prefsStore.edit { it[K_THEME] = mode }
    }
    suspend fun setSort(mode: String) {
        context.prefsStore.edit { it[K_SORT] = mode }
    }
    suspend fun setCurrentSemester(id: String?) {
        context.prefsStore.edit { e ->
            if (id == null) e.remove(K_SEMESTER) else e[K_SEMESTER] = id
        }
    }
    /** 只有真正导出成功后调用。 */
    suspend fun setLastBackupTime(t: Long) {
        context.prefsStore.edit { it[K_BACKUP] = t }
    }

    suspend fun snapshot(): UserPrefs {
        val p = context.prefsStore.data.first()
        return UserPrefs(
            mottoText = p[K_MOTTO] ?: "先做好眼前这一件。",
            mottoVisible = p[K_MOTTO_VISIBLE] ?: true,
            themeMode = p[K_THEME] ?: ThemeMode.SYSTEM,
            sortMode = p[K_SORT] ?: SortMode.DEADLINE,
            currentSemesterId = p[K_SEMESTER],
            lastBackupTime = p[K_BACKUP] ?: 0L,
        )
    }

    suspend fun restore(p: UserPrefs) {
        context.prefsStore.edit {
            it[K_MOTTO] = p.mottoText
            it[K_MOTTO_VISIBLE] = p.mottoVisible
            it[K_THEME] = p.themeMode
            it[K_SORT] = p.sortMode
            if (p.currentSemesterId == null) it.remove(K_SEMESTER) else it[K_SEMESTER] = p.currentSemesterId
            it[K_BACKUP] = p.lastBackupTime
        }
    }
}
