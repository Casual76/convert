package com.p2r3.convert.model

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class StartDestination {
    HOME,
    CONVERT,
    COMMON,
    HISTORY,
    SETTINGS
}

enum class OutputNamingPolicy {
    KEEP_SOURCE_STEM,
    APPEND_TARGET,
    TIMESTAMP_SUFFIX
}

enum class PerformancePreset {
    BATTERY,
    BALANCED,
    PERFORMANCE
}

enum class ConversionStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
}

enum class EngineRuntimeKind {
    NATIVE,
    BRIDGE
}

enum class PreviewKind {
    NONE,
    IMAGE_PROXY,
    TEXT,
    DOCUMENT
}

data class FormatDescriptor(
    val id: String,
    val displayName: String,
    val shortLabel: String,
    val extension: String,
    val mimeType: String,
    val categories: List<String>,
    val supportsInput: Boolean,
    val supportsOutput: Boolean,
    val handlerName: String,
    val lossless: Boolean = false,
    val availableRuntimeKinds: List<EngineRuntimeKind> = listOf(EngineRuntimeKind.NATIVE),
    val nativePreferred: Boolean = true
)

data class CommonConversionPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val sourceFormatId: String,
    val targetFormatId: String,
    val category: String,
    val pinnedByDefault: Boolean = false
)

data class RouteStep(
    val fromFormatId: String,
    val toFormatId: String,
    val handlerName: String,
    val performanceClass: String,
    val batchStrategy: String,
    val note: String,
    val runtimeKind: EngineRuntimeKind = EngineRuntimeKind.NATIVE
)

data class RoutePreview(
    val routeKey: String,
    val routeToken: String? = null,
    val steps: List<RouteStep>,
    val etaLabel: String,
    val cpuImpactLabel: String,
    val confidenceLabel: String,
    val reasons: List<String>,
    val previewSupported: Boolean,
    val runtimeKind: EngineRuntimeKind = EngineRuntimeKind.NATIVE
)

data class ConversionPreview(
    val supported: Boolean,
    val kind: PreviewKind,
    val headline: String,
    val body: String,
    val proxyUri: String? = null,
    val textPreview: String? = null
)

data class ConversionRequest(
    val inputUris: List<String>,
    val sourceFormatId: String?,
    val targetFormatId: String,
    val presetId: String? = null,
    val outputDirectoryUri: String? = null,
    val outputNamingPolicy: OutputNamingPolicy = OutputNamingPolicy.KEEP_SOURCE_STEM,
    val performancePreset: PerformancePreset = PerformancePreset.BALANCED,
    val previewMode: Boolean = false,
    val routeToken: String? = null,
    val allowBridgeFallback: Boolean = true,
    val maxParallelJobs: Int = 2,
    val batteryFriendlyMode: Boolean = true,
    val autoOpenResult: Boolean = false,
    val previewLimitBytes: Long = 10L * 1024L * 1024L
)

data class ConversionResult(
    val status: ConversionStatus,
    val message: String,
    val outputUris: List<String> = emptyList(),
    val routePreview: RoutePreview? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val runtimeKind: EngineRuntimeKind = EngineRuntimeKind.NATIVE,
    val usedFallback: Boolean = false,
    val diagnosticsMessage: String? = null
)

data class ConversionHistoryEntry(
    val id: Long,
    val title: String,
    val subtitle: String,
    val createdAt: Long,
    val status: ConversionStatus,
    val inputCount: Int,
    val outputCount: Int,
    val outputUris: List<String>,
    val message: String,
    val presetTitle: String? = null,
    val requestSnapshot: String? = null,
    val routeToken: String? = null,
    val runtimeKind: EngineRuntimeKind? = null,
    val usedFallback: Boolean = false,
    val keepEntry: Boolean = true
)

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val startDestination: StartDestination = StartDestination.HOME,
    val autoPreview: Boolean = true,
    val previewSizeLimitMb: Int = 10,
    val outputDirectoryUri: String? = null,
    val outputNamingPolicy: OutputNamingPolicy = OutputNamingPolicy.APPEND_TARGET,
    val autoOpenResult: Boolean = false,
    val keepHistory: Boolean = true,
    val keepScreenOn: Boolean = true,
    val performancePreset: PerformancePreset = PerformancePreset.BALANCED,
    val maxParallelJobs: Int = 2,
    val batteryFriendlyMode: Boolean = true,
    val confirmBeforeBatch: Boolean = true,
    val reduceMotion: Boolean = false,
    val hapticsEnabled: Boolean = true
)

data class EngineDiagnostics(
    val runtimeKind: EngineRuntimeKind,
    val catalogFormatCount: Int,
    val inputFormatCount: Int,
    val outputFormatCount: Int,
    val handlerCount: Int,
    val disabledHandlers: List<String> = emptyList(),
    val cacheSource: String? = null
)

fun legacyFormatId(format: String, mimeType: String): String =
    "${mimeType.lowercase()}(${format.lowercase()})"
