package com.p2r3.convert.data.bridge

import com.p2r3.convert.model.ConversionPreview
import com.p2r3.convert.model.ConversionRequest
import com.p2r3.convert.model.EngineDiagnostics
import com.p2r3.convert.model.FormatDescriptor
import com.p2r3.convert.model.RoutePreview

data class BridgeRunResult(
    val message: String,
    val outputUris: List<String>,
    val routePreview: RoutePreview? = null
)

interface CompatibilityBridge {
    suspend fun loadCatalog(): List<FormatDescriptor>
    suspend fun detectFormat(fileName: String?, mimeType: String?): FormatDescriptor?
    suspend fun listTargets(sourceFormatId: String): List<FormatDescriptor>
    suspend fun planRoute(
        sourceFormatId: String,
        targetFormatId: String,
        fileCount: Int,
        totalBytes: Long,
        performancePreset: com.p2r3.convert.model.PerformancePreset,
        maxParallelJobs: Int,
        batteryFriendlyMode: Boolean
    ): RoutePreview?

    suspend fun generatePreview(
        inputUri: String,
        fileName: String?,
        mimeType: String?,
        sourceFormatId: String,
        targetFormatId: String,
        routeToken: String?,
        performancePreset: com.p2r3.convert.model.PerformancePreset,
        maxParallelJobs: Int,
        batteryFriendlyMode: Boolean,
        previewLimitBytes: Long
    ): ConversionPreview

    suspend fun runConversion(request: ConversionRequest): BridgeRunResult
    suspend fun diagnostics(): EngineDiagnostics
}
