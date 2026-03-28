package com.p2r3.convert.data.jobs

import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.p2r3.convert.data.history.HistoryRepository
import com.p2r3.convert.data.settings.SettingsRepository
import com.p2r3.convert.model.ConversionRequest
import com.p2r3.convert.model.EngineRuntimeKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class WorkManagerConversionJobScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository
) : ConversionJobScheduler {

    override suspend fun schedule(
        request: ConversionRequest,
        title: String,
        subtitle: String,
        presetTitle: String?
    ): Long {
        val settings = settingsRepository.settings.first()
        val historyId = historyRepository.queueEntry(
            title = title,
            subtitle = subtitle,
            inputCount = request.inputUris.size,
            presetTitle = presetTitle,
            requestSnapshot = serializeConversionRequest(request),
            routeToken = request.routeToken,
            runtimeKind = if (request.routeToken?.contains("\"runtimeKind\":\"BRIDGE\"") == true) EngineRuntimeKind.BRIDGE else EngineRuntimeKind.NATIVE,
            keepEntry = settings.keepHistory
        )

        val data = Data.Builder()
            .putLong(ConversionWorker.KEY_HISTORY_ID, historyId)
            .putStringArray(ConversionWorker.KEY_INPUT_URIS, request.inputUris.toTypedArray())
            .putString(ConversionWorker.KEY_SOURCE_FORMAT_ID, request.sourceFormatId)
            .putString(ConversionWorker.KEY_TARGET_FORMAT_ID, request.targetFormatId)
            .putString(ConversionWorker.KEY_PRESET_ID, request.presetId)
            .putString(ConversionWorker.KEY_OUTPUT_DIRECTORY_URI, request.outputDirectoryUri)
            .putString(ConversionWorker.KEY_OUTPUT_NAMING_POLICY, request.outputNamingPolicy.name)
            .putString(ConversionWorker.KEY_PERFORMANCE_PRESET, request.performancePreset.name)
            .putString(ConversionWorker.KEY_ROUTE_TOKEN, request.routeToken)
            .putBoolean(ConversionWorker.KEY_ALLOW_BRIDGE_FALLBACK, request.allowBridgeFallback)
            .putInt(ConversionWorker.KEY_MAX_PARALLEL_JOBS, request.maxParallelJobs)
            .putBoolean(ConversionWorker.KEY_BATTERY_FRIENDLY_MODE, request.batteryFriendlyMode)
            .putBoolean(ConversionWorker.KEY_AUTO_OPEN_RESULT, request.autoOpenResult)
            .putLong(ConversionWorker.KEY_PREVIEW_LIMIT_BYTES, request.previewLimitBytes)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ConversionWorker>()
            .setInputData(data)
            .addTag("conversion")
            .addTag("conversion-$historyId")
            .build()

        workManager.enqueue(workRequest)
        return historyId
    }
}
