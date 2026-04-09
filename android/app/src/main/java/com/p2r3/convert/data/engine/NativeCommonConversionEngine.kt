package com.p2r3.convert.data.engine

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.OpenableColumns
import android.text.TextUtils
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import androidx.documentfile.provider.DocumentFile
import com.p2r3.convert.model.ConversionPreview
import com.p2r3.convert.model.ConversionRequest
import com.p2r3.convert.model.ConversionResult
import com.p2r3.convert.model.ConversionStatus
import com.p2r3.convert.model.BridgeValidationState
import com.p2r3.convert.model.EngineDiagnostics
import com.p2r3.convert.model.EngineRuntimeKind
import com.p2r3.convert.model.FormatDescriptor
import com.p2r3.convert.model.PreviewKind
import com.p2r3.convert.model.RoutePreview
import com.p2r3.convert.model.RouteStep
import com.p2r3.convert.model.legacyFormatId
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class NativeCommonConversionEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : ConversionEngine {

    private val pngId = legacyFormatId("png", "image/png")
    private val jpegId = legacyFormatId("jpeg", "image/jpeg")
    private val webpId = legacyFormatId("webp", "image/webp")
    private val pdfId = legacyFormatId("pdf", "application/pdf")
    private val txtId = legacyFormatId("txt", "text/plain")
    private val mdId = legacyFormatId("md", "text/markdown")
    private val htmlId = legacyFormatId("html", "text/html")

    private val catalog = listOf(
        FormatDescriptor(
            id = pngId,
            displayName = "Portable Network Graphics",
            shortLabel = "PNG",
            extension = "png",
            mimeType = "image/png",
            categories = listOf("Images"),
            supportsInput = true,
            supportsOutput = true,
            handlerName = "NativeBitmap",
            lossless = true
        ),
        FormatDescriptor(
            id = jpegId,
            displayName = "Joint Photographic Experts Group",
            shortLabel = "JPG",
            extension = "jpg",
            mimeType = "image/jpeg",
            categories = listOf("Images"),
            supportsInput = true,
            supportsOutput = true,
            handlerName = "NativeBitmap"
        ),
        FormatDescriptor(
            id = webpId,
            displayName = "WebP image",
            shortLabel = "WebP",
            extension = "webp",
            mimeType = "image/webp",
            categories = listOf("Images"),
            supportsInput = true,
            supportsOutput = true,
            handlerName = "NativeBitmap"
        ),
        FormatDescriptor(
            id = pdfId,
            displayName = "Portable Document Format",
            shortLabel = "PDF",
            extension = "pdf",
            mimeType = "application/pdf",
            categories = listOf("Documents"),
            supportsInput = false,
            supportsOutput = true,
            handlerName = "NativeDocument",
            lossless = true
        ),
        FormatDescriptor(
            id = txtId,
            displayName = "Plain text",
            shortLabel = "TXT",
            extension = "txt",
            mimeType = "text/plain",
            categories = listOf("Text"),
            supportsInput = true,
            supportsOutput = true,
            handlerName = "NativeText",
            lossless = true
        ),
        FormatDescriptor(
            id = mdId,
            displayName = "Markdown document",
            shortLabel = "MD",
            extension = "md",
            mimeType = "text/markdown",
            categories = listOf("Text", "Documents"),
            supportsInput = true,
            supportsOutput = true,
            handlerName = "NativeText",
            lossless = true
        ),
        FormatDescriptor(
            id = htmlId,
            displayName = "HyperText Markup Language",
            shortLabel = "HTML",
            extension = "html",
            mimeType = "text/html",
            categories = listOf("Text", "Documents"),
            supportsInput = true,
            supportsOutput = true,
            handlerName = "NativeText"
        )
    )

    private val catalogById = catalog.associateBy { it.id }

    override suspend fun loadCatalog(): List<FormatDescriptor> = catalog

    override suspend fun detectFormat(uri: String, fileName: String?, mimeType: String?): FormatDescriptor? {
        val resolvedName = fileName ?: resolveDisplayName(uri.toUri())
        val resolvedMime = mimeType ?: context.contentResolver.getType(uri.toUri())
        return FormatDetectionHeuristics.detect(
            catalog = catalog,
            uri = uri,
            fileName = resolvedName,
            mimeType = resolvedMime
        )
    }

    override suspend fun listTargets(sourceFormatId: String): List<FormatDescriptor> {
        val ids = when (sourceFormatId) {
            pngId, jpegId, webpId -> setOf(pngId, jpegId, webpId, pdfId)
            txtId -> setOf(txtId, mdId, htmlId, pdfId)
            mdId -> setOf(mdId, txtId, htmlId, pdfId)
            htmlId -> setOf(htmlId, txtId, pdfId)
            else -> emptySet()
        }
        return catalog.filter { it.id in ids && it.supportsOutput }
    }

    override suspend fun planRoute(
        sourceFormatId: String,
        targetFormatId: String,
        fileCount: Int,
        totalBytes: Long
    ): RoutePreview? {
        val source = catalogById[sourceFormatId] ?: return null
        val target = catalogById[targetFormatId] ?: return null
        if (target !in listTargets(source.id)) return null

        val usesDocumentPath = target.id == pdfId
        val estimatedSeconds = when {
            usesDocumentPath -> if (totalBytes > 4_000_000) "4-8 s" else "2-5 s"
            source.categories.firstOrNull() == "Images" -> if (totalBytes > 4_000_000) "2-4 s" else "1-2 s"
            else -> "under 1 s"
        }

        val routeToken = """{"runtimeKind":"NATIVE","sourceFormatId":"${source.id}","targetFormatId":"${target.id}"}"""

        return RoutePreview(
            routeKey = "${source.id}->${target.id}",
            routeToken = routeToken,
            steps = listOf(
                RouteStep(
                    fromFormatId = source.id,
                    toFormatId = target.id,
                    handlerName = if (usesDocumentPath) "NativeDocument" else "NativeCommonEngine",
                    performanceClass = if (usesDocumentPath) "medium" else "light",
                    batchStrategy = if (fileCount > 1) "per-file" else "single",
                    note = when {
                        usesDocumentPath -> "Builds a local document without leaving the device."
                        source.categories.firstOrNull() == "Images" -> "Decodes the source bitmap and writes a new compressed output."
                        else -> "Transforms local text content and writes the result directly."
                    },
                    runtimeKind = EngineRuntimeKind.NATIVE
                )
            ),
            etaLabel = estimatedSeconds,
            cpuImpactLabel = if (usesDocumentPath || totalBytes > 4_000_000) "Moderate" else "Low",
            confidenceLabel = "High confidence",
            reasons = listOf(
                "Route stays on-device in the native Android engine.",
                "This source/target pair uses native codecs and document builders.",
                if (fileCount > 1) "Each file is processed independently for predictable batch behavior." else "Single-file path stays lightweight."
            ),
            previewSupported = target.id in setOf(pngId, jpegId, webpId, txtId, mdId, htmlId, pdfId),
            runtimeKind = EngineRuntimeKind.NATIVE
        )
    }

    override suspend fun generatePreview(
        inputUri: String,
        sourceFormatId: String,
        targetFormatId: String,
        routeToken: String?,
        previewLimitBytes: Long
    ): ConversionPreview {
        if (previewLimitBytes > 0 && resolveFileSize(inputUri.toUri()) > previewLimitBytes) {
            return ConversionPreview(
                supported = false,
                kind = PreviewKind.NONE,
                headline = "Preview skipped",
                body = "The selected file is above the configured preview size limit."
            )
        }
        return when (targetFormatId) {
            pngId, jpegId, webpId -> ConversionPreview(
                supported = true,
                kind = PreviewKind.IMAGE_PROXY,
                headline = "Image preview ready",
                body = "The native flow can reuse the source image as a safe proxy preview before export.",
                proxyUri = inputUri
            )
            txtId, mdId, htmlId -> ConversionPreview(
                supported = true,
                kind = PreviewKind.TEXT,
                headline = "Text preview available",
                body = "This route stays lightweight, so the final content can be previewed inline."
            )
            pdfId -> ConversionPreview(
                supported = true,
                kind = PreviewKind.DOCUMENT,
                headline = "Document preview planned",
                body = "PDF output is generated locally when the job runs. The route is ready."
            )
            else -> ConversionPreview(
                supported = false,
                kind = PreviewKind.NONE,
                headline = "Preview unavailable",
                body = "This output type does not expose an inline preview yet."
            )
        }
    }

    override suspend fun runConversion(request: ConversionRequest): ConversionResult = withContext(Dispatchers.IO) {
        val target = catalogById[request.targetFormatId]
            ?: return@withContext ConversionResult(
                status = ConversionStatus.FAILED,
                message = "Unknown native target format.",
                runtimeKind = EngineRuntimeKind.NATIVE
            )

        val outputUris = mutableListOf<String>()
        for (input in request.inputUris) {
            val inputUri = input.toUri()
            val detected = request.sourceFormatId?.let(catalogById::get)
                ?: detectFormat(input, fileName = resolveDisplayName(inputUri), mimeType = context.contentResolver.getType(inputUri))
                ?: return@withContext ConversionResult(
                    status = ConversionStatus.FAILED,
                    message = "Could not detect the source format for ${resolveDisplayName(inputUri)}.",
                    runtimeKind = EngineRuntimeKind.NATIVE
                )

            val outputBytes = convertSingle(inputUri, detected.id, target.id)
                ?: return@withContext ConversionResult(
                    status = ConversionStatus.FAILED,
                    message = "Native conversion is not ready for ${detected.shortLabel} to ${target.shortLabel} yet.",
                    runtimeKind = EngineRuntimeKind.NATIVE
                )

            val outputName = buildOutputName(resolveDisplayName(inputUri), target.extension, target.shortLabel, request)
            val outputUri = saveOutput(
                fileName = outputName,
                mimeType = target.mimeType,
                bytes = outputBytes,
                outputDirectoryUri = request.outputDirectoryUri
            )
            outputUris += outputUri.toString()
        }

        ConversionResult(
            status = ConversionStatus.COMPLETED,
            message = "Created ${outputUris.size} converted file${if (outputUris.size == 1) "" else "s"} on device.",
            outputUris = outputUris,
            routePreview = request.sourceFormatId?.let {
                planRoute(it, request.targetFormatId, request.inputUris.size, 0)
            },
            runtimeKind = EngineRuntimeKind.NATIVE
        )
    }

    override suspend fun diagnostics(): EngineDiagnostics = EngineDiagnostics(
        runtimeKind = EngineRuntimeKind.NATIVE,
        catalogFormatCount = catalog.size,
        inputFormatCount = catalog.count { it.supportsInput },
        outputFormatCount = catalog.count { it.supportsOutput },
        handlerCount = 3,
        bridgeValidationState = BridgeValidationState.VALIDATED
    )

    private fun convertSingle(inputUri: Uri, sourceId: String, targetId: String): ByteArray? {
        return when {
            sourceId in setOf(pngId, jpegId, webpId) && targetId in setOf(pngId, jpegId, webpId) ->
                transcodeBitmap(inputUri, targetId)

            sourceId in setOf(pngId, jpegId, webpId) && targetId == pdfId ->
                imageToPdf(inputUri)

            sourceId in setOf(txtId, mdId, htmlId) && targetId in setOf(txtId, mdId, htmlId) ->
                transcodeText(inputUri, sourceId, targetId)?.toByteArray()

            sourceId in setOf(txtId, mdId, htmlId) && targetId == pdfId ->
                transcodeText(inputUri, sourceId, txtId)?.let(::textToPdf)

            else -> null
        }
    }

    private fun transcodeBitmap(inputUri: Uri, targetId: String): ByteArray? {
        val bitmap = context.contentResolver.openInputStream(inputUri)?.use(BitmapFactory::decodeStream) ?: return null
        val format = when (targetId) {
            pngId -> Bitmap.CompressFormat.PNG
            jpegId -> Bitmap.CompressFormat.JPEG
            webpId -> Bitmap.CompressFormat.WEBP
            else -> return null
        }

        return ByteArrayOutputStream().use { output ->
            bitmap.compress(format, if (targetId == pngId) 100 else 90, output)
            output.toByteArray()
        }
    }

    private fun imageToPdf(inputUri: Uri): ByteArray? {
        val bitmap = context.contentResolver.openInputStream(inputUri)?.use(BitmapFactory::decodeStream) ?: return null
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 40f
        val scale = minOf(
            (pageWidth - margin * 2) / bitmap.width,
            (pageHeight - margin * 2) / bitmap.height
        )
        val scaledWidth = bitmap.width * scale
        val scaledHeight = bitmap.height * scale
        val left = (pageWidth - scaledWidth) / 2f
        val top = (pageHeight - scaledHeight) / 2f

        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = document.startPage(pageInfo)
        page.canvas.drawBitmap(bitmap, null, RectF(left, top, left + scaledWidth, top + scaledHeight), null)
        document.finishPage(page)

        return ByteArrayOutputStream().use { output ->
            document.writeTo(output)
            document.close()
            output.toByteArray()
        }
    }

    private fun transcodeText(inputUri: Uri, sourceId: String, targetId: String): String? {
        val rawText = context.contentResolver.openInputStream(inputUri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: return null

        val plainText = if (sourceId == htmlId) {
            HtmlCompat.fromHtml(rawText, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        } else {
            rawText
        }

        return when (targetId) {
            txtId, mdId -> plainText
            htmlId -> "<html><body><pre>${TextUtils.htmlEncode(plainText)}</pre></body></html>"
            else -> null
        }
    }

    private fun textToPdf(text: String): ByteArray {
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 42f
        val lineHeight = 22f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f
        }

        val wrappedLines = text.lines().ifEmpty { listOf("") }.flatMap { line ->
            if (line.length <= 78) listOf(line) else line.chunked(78)
        }

        var pageNumber = 1
        var index = 0
        while (index < wrappedLines.size) {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber++).create()
            val page = document.startPage(pageInfo)
            var y = margin
            while (index < wrappedLines.size && y < pageHeight - margin) {
                page.canvas.drawText(wrappedLines[index], margin, y, paint)
                y += lineHeight
                index += 1
            }
            document.finishPage(page)
        }

        return ByteArrayOutputStream().use { output ->
            document.writeTo(output)
            document.close()
            output.toByteArray()
        }
    }

    private fun buildOutputName(
        originalName: String,
        targetExtension: String,
        targetLabel: String,
        request: ConversionRequest
    ): String {
        val stem = originalName.substringBeforeLast('.', originalName)
        val suffix = when (request.outputNamingPolicy) {
            com.p2r3.convert.model.OutputNamingPolicy.KEEP_SOURCE_STEM -> stem
            com.p2r3.convert.model.OutputNamingPolicy.APPEND_TARGET -> "$stem-${targetLabel.lowercase()}"
            com.p2r3.convert.model.OutputNamingPolicy.TIMESTAMP_SUFFIX -> "$stem-${System.currentTimeMillis()}"
        }
        return "$suffix.$targetExtension"
    }

    fun saveOutput(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        outputDirectoryUri: String?
    ): Uri {
        val resolvedMimeType = resolveOutputMimeType(fileName, mimeType)
        outputDirectoryUri?.let { rawTree ->
            val tree = DocumentFile.fromTreeUri(context, rawTree.toUri())
            if (tree != null && tree.canWrite()) {
                val targetName = findAvailableFileName(fileName) { candidate -> tree.findFile(candidate) != null }
                val document = tree.createFile(resolvedMimeType, targetName)
                if (document != null) {
                    context.contentResolver.openOutputStream(document.uri)?.use { it.write(bytes) }
                    return document.uri
                }
            }
        }

        val baseDir = File(
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir,
            "convert-to-it"
        ).apply { mkdirs() }
        val outputFile = File(baseDir, findAvailableFileName(fileName) { candidate -> File(baseDir, candidate).exists() })
        FileOutputStream(outputFile).use { it.write(bytes) }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outputFile
        )
    }

    private fun resolveOutputMimeType(fileName: String, fallbackMimeType: String): String {
        val trimmedFallback = fallbackMimeType.trim()
        if (trimmedFallback.isNotEmpty() && trimmedFallback != "application/octet-stream") {
            return trimmedFallback
        }

        val extension = MimeTypeMap.getFileExtensionFromUrl(fileName)
            ?.lowercase()
            ?.takeIf(String::isNotBlank)
            ?: fileName.substringAfterLast('.', "").lowercase().takeIf(String::isNotBlank)

        return extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: trimmedFallback.ifBlank { "application/octet-stream" }
    }

    private fun findAvailableFileName(baseName: String, exists: (String) -> Boolean): String {
        if (!exists(baseName)) return baseName

        val extension = baseName.substringAfterLast('.', "")
        val stem = if (extension.isBlank() || !baseName.contains('.')) {
            baseName
        } else {
            baseName.removeSuffix(".$extension")
        }

        var suffix = 2
        while (true) {
            val candidate = if (extension.isBlank() || !baseName.contains('.')) {
                "$stem-$suffix"
            } else {
                "$stem-$suffix.$extension"
            }
            if (!exists(candidate)) return candidate
            suffix += 1
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return File(uri.path.orEmpty()).name.ifBlank { "input-file" }
        }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index != -1 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "input-file"
    }

    private fun resolveFileSize(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index != -1 && cursor.moveToFirst()) {
                return cursor.getLong(index)
            }
        }
        return 0L
    }
}
