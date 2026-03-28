package com.p2r3.convert.data.engine

import com.p2r3.convert.model.ConversionPreview
import com.p2r3.convert.model.ConversionRequest
import com.p2r3.convert.model.ConversionResult
import com.p2r3.convert.model.EngineDiagnostics
import com.p2r3.convert.model.FormatDescriptor
import com.p2r3.convert.model.RoutePreview

interface ConversionEngine {
    suspend fun loadCatalog(): List<FormatDescriptor>
    suspend fun detectFormat(uri: String, fileName: String? = null, mimeType: String? = null): FormatDescriptor?
    suspend fun listTargets(sourceFormatId: String): List<FormatDescriptor>
    suspend fun planRoute(
        sourceFormatId: String,
        targetFormatId: String,
        fileCount: Int,
        totalBytes: Long
    ): RoutePreview?

    suspend fun generatePreview(
        inputUri: String,
        sourceFormatId: String,
        targetFormatId: String,
        routeToken: String? = null,
        previewLimitBytes: Long = Long.MAX_VALUE
    ): ConversionPreview

    suspend fun runConversion(request: ConversionRequest): ConversionResult

    suspend fun diagnostics(): EngineDiagnostics
}
