package com.p2r3.convert.data.jobs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.p2r3.convert.MainActivity
import com.p2r3.convert.R
import com.p2r3.convert.data.engine.ConversionEngine
import com.p2r3.convert.data.history.HistoryRepository
import com.p2r3.convert.model.ConversionRequest
import com.p2r3.convert.model.ConversionStatus
import com.p2r3.convert.model.OutputNamingPolicy
import com.p2r3.convert.model.PerformancePreset
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ConversionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val conversionEngine: ConversionEngine,
    private val historyRepository: HistoryRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val historyId = inputData.getLong(KEY_HISTORY_ID, -1L)
        if (historyId <= 0) return Result.failure()

        ensureNotificationChannel()
        setForeground(createForegroundInfo(historyId))
        historyRepository.markRunning(historyId)

        val request = ConversionRequest(
            inputUris = inputData.getStringArray(KEY_INPUT_URIS)?.toList().orEmpty(),
            sourceFormatId = inputData.getString(KEY_SOURCE_FORMAT_ID),
            targetFormatId = inputData.getString(KEY_TARGET_FORMAT_ID).orEmpty(),
            presetId = inputData.getString(KEY_PRESET_ID),
            outputDirectoryUri = inputData.getString(KEY_OUTPUT_DIRECTORY_URI),
            outputNamingPolicy = enumValueOf(inputData.getString(KEY_OUTPUT_NAMING_POLICY) ?: OutputNamingPolicy.APPEND_TARGET.name),
            performancePreset = enumValueOf(inputData.getString(KEY_PERFORMANCE_PRESET) ?: PerformancePreset.BALANCED.name),
            routeToken = inputData.getString(KEY_ROUTE_TOKEN),
            allowBridgeFallback = inputData.getBoolean(KEY_ALLOW_BRIDGE_FALLBACK, true),
            maxParallelJobs = inputData.getInt(KEY_MAX_PARALLEL_JOBS, 2),
            batteryFriendlyMode = inputData.getBoolean(KEY_BATTERY_FRIENDLY_MODE, true),
            autoOpenResult = inputData.getBoolean(KEY_AUTO_OPEN_RESULT, false),
            previewLimitBytes = inputData.getLong(KEY_PREVIEW_LIMIT_BYTES, 10L * 1024L * 1024L)
        )

        val result = conversionEngine.runConversion(request)
        return if (result.status == ConversionStatus.COMPLETED) {
            historyRepository.markCompleted(
                id = historyId,
                message = result.message,
                outputUris = result.outputUris,
                runtimeKind = result.runtimeKind,
                usedFallback = result.usedFallback,
                routeToken = result.routePreview?.routeToken ?: request.routeToken
            )
            showCompletionNotification(historyId, result.message, result.outputUris.firstOrNull())
            maybeAutoOpenResult(request.autoOpenResult, result.outputUris.firstOrNull())
            Result.success()
        } else {
            historyRepository.markFailed(historyId, result.message)
            showFailureNotification(historyId, result.message)
            Result.failure()
        }
    }

    private fun maybeAutoOpenResult(autoOpenResult: Boolean, rawUri: String?) {
        if (!autoOpenResult || rawUri.isNullOrBlank()) return
        if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        val uri = rawUri.toUri()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, applicationContext.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { applicationContext.startActivity(intent) }
    }

    private fun createForegroundInfo(historyId: Long): ForegroundInfo {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            historyId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Conversion in corso")
            .setContentText("Il job sta girando sul device.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(historyId: Long, message: String, rawUri: String?) {
        val targetIntent = if (!rawUri.isNullOrBlank()) {
            Intent(Intent.ACTION_VIEW).apply {
                val uri = rawUri.toUri()
                setDataAndType(uri, applicationContext.contentResolver.getType(uri) ?: "*/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            historyId.toInt() + 10_000,
            targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Conversione completata")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID + historyId.toInt(), notification)
    }

    private fun showFailureNotification(historyId: Long, message: String) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            historyId.toInt() + 20_000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Conversione fallita")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID + historyId.toInt(), notification)
    }

    private fun ensureNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private const val CHANNEL_ID = "convert-jobs"
        private const val NOTIFICATION_ID = 5124

        const val KEY_HISTORY_ID = "history_id"
        const val KEY_INPUT_URIS = "input_uris"
        const val KEY_SOURCE_FORMAT_ID = "source_format_id"
        const val KEY_TARGET_FORMAT_ID = "target_format_id"
        const val KEY_PRESET_ID = "preset_id"
        const val KEY_OUTPUT_DIRECTORY_URI = "output_directory_uri"
        const val KEY_OUTPUT_NAMING_POLICY = "output_naming_policy"
        const val KEY_PERFORMANCE_PRESET = "performance_preset"
        const val KEY_ROUTE_TOKEN = "route_token"
        const val KEY_ALLOW_BRIDGE_FALLBACK = "allow_bridge_fallback"
        const val KEY_MAX_PARALLEL_JOBS = "max_parallel_jobs"
        const val KEY_BATTERY_FRIENDLY_MODE = "battery_friendly_mode"
        const val KEY_AUTO_OPEN_RESULT = "auto_open_result"
        const val KEY_PREVIEW_LIMIT_BYTES = "preview_limit_bytes"
    }
}
