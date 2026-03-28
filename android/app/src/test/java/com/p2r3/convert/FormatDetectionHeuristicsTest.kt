package com.p2r3.convert

import com.p2r3.convert.data.engine.FormatDetectionHeuristics
import com.p2r3.convert.model.EngineRuntimeKind
import com.p2r3.convert.model.FormatDescriptor
import com.p2r3.convert.model.legacyFormatId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FormatDetectionHeuristicsTest {

    private val jpeg = FormatDescriptor(
        id = legacyFormatId("jpeg", "image/jpeg"),
        displayName = "JPEG image",
        shortLabel = "JPG",
        extension = "jpg",
        mimeType = "image/jpeg",
        categories = listOf("Images"),
        supportsInput = true,
        supportsOutput = true,
        handlerName = "LegacyImage",
        availableRuntimeKinds = listOf(EngineRuntimeKind.BRIDGE),
        nativePreferred = false
    )

    private val markdown = FormatDescriptor(
        id = legacyFormatId("md", "text/markdown"),
        displayName = "Markdown document",
        shortLabel = "MD",
        extension = "md",
        mimeType = "text/markdown",
        categories = listOf("Text"),
        supportsInput = true,
        supportsOutput = true,
        handlerName = "LegacyText",
        availableRuntimeKinds = listOf(EngineRuntimeKind.BRIDGE),
        nativePreferred = false
    )

    private val pdf = FormatDescriptor(
        id = legacyFormatId("pdf", "application/pdf"),
        displayName = "Portable Document Format",
        shortLabel = "PDF",
        extension = "pdf",
        mimeType = "application/pdf",
        categories = listOf("Documents"),
        supportsInput = true,
        supportsOutput = true,
        handlerName = "LegacyDocument",
        availableRuntimeKinds = listOf(EngineRuntimeKind.BRIDGE),
        nativePreferred = false
    )

    private val catalog = listOf(jpeg, markdown, pdf)

    @Test
    fun `detects jpeg from alias mime and file extension`() {
        val detected = FormatDetectionHeuristics.detect(
            catalog = catalog,
            uri = "content://docs/holiday-photo.jpg",
            fileName = "holiday-photo.jpg",
            mimeType = "image/jpg"
        )

        assertNotNull(detected)
        assertEquals(jpeg.id, detected?.id)
    }

    @Test
    fun `detects markdown from long extension alias`() {
        val detected = FormatDetectionHeuristics.detect(
            catalog = catalog,
            uri = "content://docs/README.markdown",
            fileName = "README.markdown",
            mimeType = null
        )

        assertNotNull(detected)
        assertEquals(markdown.id, detected?.id)
    }

    @Test
    fun `detects pdf from file suffix even with generic mime`() {
        val detected = FormatDetectionHeuristics.detect(
            catalog = catalog,
            uri = "content://docs/report.final.pdf",
            fileName = "report.final.pdf",
            mimeType = "application/octet-stream"
        )

        assertNotNull(detected)
        assertEquals(pdf.id, detected?.id)
    }
}
