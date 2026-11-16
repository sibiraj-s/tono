package app.tono.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.tono.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tono_prefs")

/** Filters + theme, persisted with DataStore. */
class PrefsRepository(private val context: Context) {

    private object K {
        val songsOnly = booleanPreferencesKey("songs_only")
        val language = stringPreferencesKey("language")
        val length = stringPreferencesKey("length")
        val uploaded = stringPreferencesKey("uploaded")
        val sort = stringPreferencesKey("sort")
        val minQuality = stringPreferencesKey("min_quality")
        val format = stringPreferencesKey("format")
        val officialOnly = booleanPreferencesKey("official_only")
        val hidden = stringSetPreferencesKey("hidden_versions")
        val clipSeconds = intPreferencesKey("clip_seconds")
        val leveling = booleanPreferencesKey("volume_leveling")
        val chorus = booleanPreferencesKey("start_at_chorus")
        val theme = stringPreferencesKey("theme")
        val recent = stringPreferencesKey("recent_searches")
        val rotOn = booleanPreferencesKey("rotation_enabled")
        val rotIds = stringSetPreferencesKey("rotation_tone_ids")
        val rotInterval = stringPreferencesKey("rotation_interval")
        val rotShuffle = booleanPreferencesKey("rotation_shuffle")
        val rotLast = longPreferencesKey("rotation_last_tone")
        val rotChanged = longPreferencesKey("rotation_last_changed")
        val autoUpdate = booleanPreferencesKey("auto_update_check")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
    }

    val filters: Flow<SearchFilters> = context.dataStore.data.map { p ->
        val d = SearchFilters()
        SearchFilters(
            songsOnly = p[K.songsOnly] ?: d.songsOnly,
            language = p[K.language].toEnum(d.language),
            length = p[K.length].toEnum(d.length),
            uploaded = p[K.uploaded].toEnum(d.uploaded),
            sort = p[K.sort].toEnum(d.sort),
            minQuality = p[K.minQuality].toEnum(d.minQuality),
            format = p[K.format].toEnum(d.format),
            officialOnly = p[K.officialOnly] ?: d.officialOnly,
            hidden = p[K.hidden]?.mapNotNull { name -> VersionTag.entries.find { it.name == name } }?.toSet()
                ?: d.hidden,
            clipSeconds = p[K.clipSeconds] ?: d.clipSeconds,
            volumeLeveling = p[K.leveling] ?: d.volumeLeveling,
            startAtChorus = p[K.chorus] ?: d.startAtChorus,
        )
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { it[K.theme].toEnum(ThemeMode.System) }

    suspend fun saveFilters(f: SearchFilters) {
        context.dataStore.edit { p ->
            p[K.songsOnly] = f.songsOnly
            p[K.language] = f.language.name
            p[K.length] = f.length.name
            p[K.uploaded] = f.uploaded.name
            p[K.sort] = f.sort.name
            p[K.minQuality] = f.minQuality.name
            p[K.format] = f.format.name
            p[K.officialOnly] = f.officialOnly
            p[K.hidden] = f.hidden.map { it.name }.toSet()
            p[K.clipSeconds] = f.clipSeconds
            p[K.leveling] = f.volumeLeveling
            p[K.chorus] = f.startAtChorus
        }
    }

    /** Last few searches, newest first. */
    val recentSearches: Flow<List<String>> = context.dataStore.data.map { p ->
        p[K.recent]?.split(SEP)?.filter { it.isNotBlank() }.orEmpty()
    }

    suspend fun addRecentSearch(q: String) {
        val query = q.trim().ifEmpty { return }
        context.dataStore.edit { p ->
            val old = p[K.recent]?.split(SEP).orEmpty().filter { it.isNotBlank() && !it.equals(query, ignoreCase = true) }
            p[K.recent] = (listOf(query) + old).take(MAX_RECENT).joinToString(SEP)
        }
    }

    suspend fun removeRecentSearch(q: String) {
        context.dataStore.edit { p ->
            p[K.recent] = p[K.recent]?.split(SEP).orEmpty().filter { it != q }.joinToString(SEP)
        }
    }

    suspend fun clearRecentSearches() {
        context.dataStore.edit { it.remove(K.recent) }
    }

    // ---------- Ringtone rotation ----------

    val rotation: Flow<RotationConfig> = context.dataStore.data.map { it.toRotation() }

    /** Reads, changes and saves the rotation settings in one step; returns the new settings. */
    suspend fun updateRotation(change: (RotationConfig) -> RotationConfig): RotationConfig {
        var result = RotationConfig()
        context.dataStore.edit { p ->
            val n = change(p.toRotation())
            p[K.rotOn] = n.enabled
            p[K.rotIds] = n.toneIds.map { it.toString() }.toSet()
            p[K.rotInterval] = n.interval.name
            p[K.rotShuffle] = n.shuffle
            if (n.lastToneId != null) p[K.rotLast] = n.lastToneId else p.remove(K.rotLast)
            p[K.rotChanged] = n.lastChangedMs
            result = n
        }
        return result
    }

    /** The one place stored values become a [RotationConfig]; missing values use its defaults. */
    private fun Preferences.toRotation(): RotationConfig {
        val d = RotationConfig()
        return RotationConfig(
            enabled = this[K.rotOn] ?: d.enabled,
            toneIds = this[K.rotIds]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: d.toneIds,
            interval = this[K.rotInterval].toEnum(d.interval),
            shuffle = this[K.rotShuffle] ?: d.shuffle,
            lastToneId = this[K.rotLast] ?: d.lastToneId,
            lastChangedMs = this[K.rotChanged] ?: d.lastChangedMs,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[K.theme] = mode.name }
    }

    /** Look for a new version on GitHub once a day when the app opens. */
    val autoUpdateCheck: Flow<Boolean> = context.dataStore.data.map { it[K.autoUpdate] ?: true }
    val lastUpdateCheck: Flow<Long> = context.dataStore.data.map { it[K.lastUpdateCheck] ?: 0L }

    suspend fun setAutoUpdateCheck(on: Boolean) {
        context.dataStore.edit { it[K.autoUpdate] = on }
    }

    suspend fun setLastUpdateCheck(ms: Long) {
        context.dataStore.edit { it[K.lastUpdateCheck] = ms }
    }
}

private const val SEP = "\u001F"
private const val MAX_RECENT = 8

private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
    this?.let { name -> enumValues<E>().find { it.name == name } } ?: default
