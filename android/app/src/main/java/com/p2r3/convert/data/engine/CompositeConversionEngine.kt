package com.p2r3.convert.data.engine

import com.p2r3.convert.data.bridge.CompatibilityBridge
import com.p2r3.convert.data.settings.SettingsRepository
import com.p2r3.convert.model.ConversionPreview
import com.p2r3.convert.model.ConversionRequest
import com.p2r3.convert.model.ConversionResult
import com.p2r3.convert.model.ConversionStatus
import com.p2r3.convert.model.EngineDiagnostics
import com.p2r3.convert.model.EngineRuntimeKind
import com.p2r3.convert.model.FormatDescriptor
import com.p2r3.convert.model.RoutePreview
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class CompositeConversionEngine @Inject constructor(
    private val nativeEngine: NativeCommonConversionEngine,
    private val compatibilityBridge: CompatibilityBridge,
    private val settingsRepository: SettingsRepository
) : ConversionEngine {

    private val catalogMutex = Mutex()
    @Volatile
    private var cachedCatalog: List<FormatDescriptor>? = null

    override suspend fun loadCatalog(): List<FormatDescriptor> {
        cachedCatalog?.let { return it }
        return catalogMutex.withLock {
            cachedCatalog ?: run {
                val nativeCatalog = nativeEngine.loadCatalog()
                val bridgeCatalog = runCatching { compatibilityBridge.loadCatalog() }
                val merged = mergeCatalog(nativeCatalog, bridgeCatalog.getOrDefault(emptyList()))
                if (bridgeCatalog.isSuccess) {
                    cachedCatalog = merged
                }
                merged
            }
        }
    }

    override suspend fun detectFormat(uri: String, fileName: String?, mimeType: String?): FormatDescriptor? {
        val mergedCatalog = loadCatalog()
        return FormatDetectionHeuristics.detect(
            catalog = mergedCatalog,
            uri = uri,
            fileName = fileName,
            mimeType = mimeType
        )
            ?: nativeEngine.detectFormat(uri, fileName, mimeType)
            ?: runCatching {
                compatibilityBridge.detectFormat(fileName = fileName, mimeType = mimeType)
            }.getOrNull()
    }

    override suspend fun listTargets(sourceFormatId: String): List<FormatDescriptor> {
        val nativeTargets = nativeEngine.listTargets(sourceFormatId)
        val bridgeTargets = runCatching {
            compatibilityBridge.listTargets(sourceFormatId)
        }.getOrDefault(emptyList())
        return mergeCatalog(nativeTargets, bridgeTargets)
    }

    override suspend fun planRoute(
        sourceFormatId: String,
        targetFormatId: String,
        fileCount: Int,
        totalBytes: Long
    ): RoutePreview? {
        val settings = settingsRepository.settings.first()
        val native = nativeEngine.planRoute(sourceFormatId, targetFormatId, fileCount, totalBytes)
        if (native != null) return native
        return runCatching {
            compatibilityBridge.planRoute(
            sourceFormatId = sourceFormatId,
            targetFormatId = targetFormatId,
            fileCount = fileCount,
            totalBytes = totalBytes,
            performancePreset = settings.performancePreset,
            maxParallelJobs = settings.maxParallelJobs,
            batteryFriendlyMode = settings.batteryFriendlyMode
            )
        }.getOrNull()
    }

    override suspend fun generatePreview(
        inputUri: String,
        sourceFormatId: String,
        targetFormatId: String,
        routeToken: String?,
        previewLimitBytes: Long
    ): ConversionPreview {
        val settings = settingsRepository.settings.first()
        return if (routeToken.isBridgeToken()) {
            runCatching {
                compatibilityBridge.generatePreview(
                inputUri = inputUri,
                fileName = null,
                mimeType = null,
                sourceFormatId = sourceFormatId,
                targetFormatId = targetFormatId,
                routeToken = routeToken,
                performancePreset = settings.performancePreset,
                maxParallelJobs = settings.maxParallelJobs,
                batteryFriendlyMode = settings.batteryFriendlyMode,
                previewLimitBytes = previewLimitBytes
                )
            }.getOrElse { error ->
                ConversionPreview(
                    supported = false,
                    kind = com.p2r3.convert.model.PreviewKind.NONE,
                    headline = "Anteprima bridge non disponibile",
                    body = error.message ?: "Il runtime compatibile non ha risposto."
                )
            }
        } else {
            nativeEngine.generatePreview(
                inputUri = inputUri,
                sourceFormatId = sourceFormatId,
                targetFormatId = targetFormatId,
                routeToken = routeToken,
                previewLimitBytes = previewLimitBytes
            )
        }
    }

    override suspend fun runConversion(request: ConversionRequest): ConversionResult {
        if (request.routeToken.isBridgeToken()) {
            return runCatching {
                runBridge(request, usedFallback = false)
            }.getOrElse { error ->
                ConversionResult(
                    status = ConversionStatus.FAILED,
                    message = "Il motore compatibile non ha completato la conversione.",
                    runtimeKind = EngineRuntimeKind.BRIDGE,
                    diagnosticsMessage = error.message
                )
            }
        }

        val nativeResult = nativeEngine.runConversion(request)
        if (nativeResult.status == ConversionStatus.COMPLETED || !request.allowBridgeFallback) {
            return nativeResult
        }

        return runCatching { runBridge(request, usedFallback = true) }
            .getOrElse { nativeResult.copy(diagnosticsMessage = it.message) }
    }

    override suspend fun diagnostics(): EngineDiagnostics {
        val native = nativeEngine.diagnostics()
        val bridge = runCatching { compatibilityBridge.diagnostics() }.getOrDefault(
            EngineDiagnostics(
                runtimeKind = EngineRuntimeKind.BRIDGE,
                catalogFormatCount = 0,
                inputFormatCount = 0,
                outputFormatCount = 0,
                handlerCount = 0
            )
        )
        val mergedCatalog = loadCatalog()
        return EngineDiagnostics(
            runtimeKind = EngineRuntimeKind.NATIVE,
            catalogFormatCount = mergedCatalog.size,
            inputFormatCount = mergedCatalog.count { it.supportsInput },
            outputFormatCount = mergedCatalog.count { it.supportsOutput },
            handlerCount = native.handlerCount + bridge.handlerCount,
            disabledHandlers = bridge.disabledHandlers,
            cacheSource = bridge.cacheSource
        )
    }

    private suspend fun runBridge(request: ConversionRequest, usedFallback: Boolean): ConversionResult {
        val result = compatibilityBridge.runConversion(request)
        return ConversionResult(
            status = ConversionStatus.COMPLETED,
            message = result.message,
            outputUris = result.outputUris,
            routePreview = result.routePreview,
            runtimeKind = EngineRuntimeKind.BRIDGE,
            usedFallback = usedFallback
        )
    }

    private fun mergeCatalog(
        primary: List<FormatDescriptor>,
        secondary: List<FormatDescriptor>
    ): List<FormatDescriptor> {
        val merged = linkedMapOf<String, FormatDescriptor>()
        (primary + secondary).forEach { descriptor ->
            val current = merged[descriptor.id]
            if (current == null) {
                merged[descriptor.id] = descriptor
            } else {
                merged[descriptor.id] = current.copy(
                    categories = (current.categories + descriptor.categories).distinct(),
                    supportsInput = current.supportsInput || descriptor.supportsInput,
                    supportsOutput = current.supportsOutput || descriptor.supportsOutput,
                    lossless = current.lossless || descriptor.lossless,
                    availableRuntimeKinds = (current.availableRuntimeKinds + descriptor.availableRuntimeKinds).distinct(),
                    nativePreferred = current.nativePreferred || descriptor.nativePreferred,
                    handlerName = if (current.nativePreferred) current.handlerName else descriptor.handlerName
                )
            }
        }
        return merged.values.toList().sortedBy { it.displayName.lowercase() }
    }

    private fun String?.isBridgeToken(): Boolean =
        this?.contains("\"runtimeKind\":\"BRIDGE\"", ignoreCase = true) == true
}
