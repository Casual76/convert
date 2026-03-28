package com.p2r3.convert.data.settings

import com.p2r3.convert.model.AppSettings
import com.p2r3.convert.model.OutputNamingPolicy
import com.p2r3.convert.model.PerformancePreset
import com.p2r3.convert.model.StartDestination
import com.p2r3.convert.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setThemeMode(value: ThemeMode)
    suspend fun setDynamicColorEnabled(enabled: Boolean)
    suspend fun setStartDestination(destination: StartDestination)
    suspend fun setAutoPreview(enabled: Boolean)
    suspend fun setPreviewSizeLimitMb(limitMb: Int)
    suspend fun setOutputDirectoryUri(uri: String?)
    suspend fun setOutputNamingPolicy(policy: OutputNamingPolicy)
    suspend fun setAutoOpenResult(enabled: Boolean)
    suspend fun setKeepHistory(enabled: Boolean)
    suspend fun setKeepScreenOn(enabled: Boolean)
    suspend fun setPerformancePreset(preset: PerformancePreset)
    suspend fun setMaxParallelJobs(value: Int)
    suspend fun setBatteryFriendlyMode(enabled: Boolean)
    suspend fun setConfirmBeforeBatch(enabled: Boolean)
    suspend fun setReduceMotion(enabled: Boolean)
    suspend fun setHapticsEnabled(enabled: Boolean)
}
