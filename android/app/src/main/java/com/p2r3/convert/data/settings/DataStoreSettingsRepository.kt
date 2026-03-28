package com.p2r3.convert.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.p2r3.convert.model.AppSettings
import com.p2r3.convert.model.OutputNamingPolicy
import com.p2r3.convert.model.PerformancePreset
import com.p2r3.convert.model.StartDestination
import com.p2r3.convert.model.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.appSettingsDataStore by preferencesDataStore(name = "convert_settings")

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    override val settings: Flow<AppSettings> = context.appSettingsDataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map(::toSettings)

    override suspend fun setThemeMode(value: ThemeMode) = setString(Keys.THEME_MODE, value.name)
    override suspend fun setDynamicColorEnabled(enabled: Boolean) = setBoolean(Keys.DYNAMIC_COLOR, enabled)
    override suspend fun setStartDestination(destination: StartDestination) = setString(Keys.START_DESTINATION, destination.name)
    override suspend fun setAutoPreview(enabled: Boolean) = setBoolean(Keys.AUTO_PREVIEW, enabled)
    override suspend fun setPreviewSizeLimitMb(limitMb: Int) = setInt(Keys.PREVIEW_SIZE_LIMIT_MB, limitMb.coerceIn(1, 250))
    override suspend fun setOutputDirectoryUri(uri: String?) = setNullableString(Keys.OUTPUT_DIRECTORY_URI, uri)
    override suspend fun setOutputNamingPolicy(policy: OutputNamingPolicy) = setString(Keys.OUTPUT_NAMING_POLICY, policy.name)
    override suspend fun setAutoOpenResult(enabled: Boolean) = setBoolean(Keys.AUTO_OPEN_RESULT, enabled)
    override suspend fun setKeepHistory(enabled: Boolean) = setBoolean(Keys.KEEP_HISTORY, enabled)
    override suspend fun setKeepScreenOn(enabled: Boolean) = setBoolean(Keys.KEEP_SCREEN_ON, enabled)
    override suspend fun setPerformancePreset(preset: PerformancePreset) = setString(Keys.PERFORMANCE_PRESET, preset.name)
    override suspend fun setMaxParallelJobs(value: Int) = setInt(Keys.MAX_PARALLEL_JOBS, value.coerceIn(1, 8))
    override suspend fun setBatteryFriendlyMode(enabled: Boolean) = setBoolean(Keys.BATTERY_FRIENDLY_MODE, enabled)
    override suspend fun setConfirmBeforeBatch(enabled: Boolean) = setBoolean(Keys.CONFIRM_BEFORE_BATCH, enabled)
    override suspend fun setReduceMotion(enabled: Boolean) = setBoolean(Keys.REDUCE_MOTION, enabled)
    override suspend fun setHapticsEnabled(enabled: Boolean) = setBoolean(Keys.HAPTICS_ENABLED, enabled)

    private suspend fun setString(key: Preferences.Key<String>, value: String) {
        context.appSettingsDataStore.edit { it[key] = value }
    }

    private suspend fun setNullableString(key: Preferences.Key<String>, value: String?) {
        context.appSettingsDataStore.edit { prefs ->
            if (value.isNullOrBlank()) prefs.remove(key) else prefs[key] = value
        }
    }

    private suspend fun setBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        context.appSettingsDataStore.edit { it[key] = value }
    }

    private suspend fun setInt(key: Preferences.Key<Int>, value: Int) {
        context.appSettingsDataStore.edit { it[key] = value }
    }

    private fun toSettings(preferences: Preferences): AppSettings = AppSettings(
        themeMode = preferences[Keys.THEME_MODE]?.let { enumValueOfOrNull<ThemeMode>(it) } ?: AppSettings().themeMode,
        useDynamicColor = preferences[Keys.DYNAMIC_COLOR] ?: AppSettings().useDynamicColor,
        startDestination = preferences[Keys.START_DESTINATION]?.let { enumValueOfOrNull<StartDestination>(it) } ?: AppSettings().startDestination,
        autoPreview = preferences[Keys.AUTO_PREVIEW] ?: AppSettings().autoPreview,
        previewSizeLimitMb = preferences[Keys.PREVIEW_SIZE_LIMIT_MB] ?: AppSettings().previewSizeLimitMb,
        outputDirectoryUri = preferences[Keys.OUTPUT_DIRECTORY_URI],
        outputNamingPolicy = preferences[Keys.OUTPUT_NAMING_POLICY]?.let { enumValueOfOrNull<OutputNamingPolicy>(it) } ?: AppSettings().outputNamingPolicy,
        autoOpenResult = preferences[Keys.AUTO_OPEN_RESULT] ?: AppSettings().autoOpenResult,
        keepHistory = preferences[Keys.KEEP_HISTORY] ?: AppSettings().keepHistory,
        keepScreenOn = preferences[Keys.KEEP_SCREEN_ON] ?: AppSettings().keepScreenOn,
        performancePreset = preferences[Keys.PERFORMANCE_PRESET]?.let { enumValueOfOrNull<PerformancePreset>(it) } ?: AppSettings().performancePreset,
        maxParallelJobs = preferences[Keys.MAX_PARALLEL_JOBS] ?: AppSettings().maxParallelJobs,
        batteryFriendlyMode = preferences[Keys.BATTERY_FRIENDLY_MODE] ?: AppSettings().batteryFriendlyMode,
        confirmBeforeBatch = preferences[Keys.CONFIRM_BEFORE_BATCH] ?: AppSettings().confirmBeforeBatch,
        reduceMotion = preferences[Keys.REDUCE_MOTION] ?: AppSettings().reduceMotion,
        hapticsEnabled = preferences[Keys.HAPTICS_ENABLED] ?: AppSettings().hapticsEnabled
    )

    private inline fun <reified T : Enum<T>> enumValueOfOrNull(value: String): T? =
        runCatching { enumValueOf<T>(value) }.getOrNull()

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val START_DESTINATION = stringPreferencesKey("start_destination")
        val AUTO_PREVIEW = booleanPreferencesKey("auto_preview")
        val PREVIEW_SIZE_LIMIT_MB = intPreferencesKey("preview_size_limit_mb")
        val OUTPUT_DIRECTORY_URI = stringPreferencesKey("output_directory_uri")
        val OUTPUT_NAMING_POLICY = stringPreferencesKey("output_naming_policy")
        val AUTO_OPEN_RESULT = booleanPreferencesKey("auto_open_result")
        val KEEP_HISTORY = booleanPreferencesKey("keep_history")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val PERFORMANCE_PRESET = stringPreferencesKey("performance_preset")
        val MAX_PARALLEL_JOBS = intPreferencesKey("max_parallel_jobs")
        val BATTERY_FRIENDLY_MODE = booleanPreferencesKey("battery_friendly_mode")
        val CONFIRM_BEFORE_BATCH = booleanPreferencesKey("confirm_before_batch")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
    }
}
