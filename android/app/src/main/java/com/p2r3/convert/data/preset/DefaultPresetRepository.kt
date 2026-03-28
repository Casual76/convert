package com.p2r3.convert.data.preset

import com.p2r3.convert.model.CommonConversionPreset
import com.p2r3.convert.model.legacyFormatId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Singleton
class DefaultPresetRepository @Inject constructor() : PresetRepository {
    private val pngId = legacyFormatId("png", "image/png")
    private val jpegId = legacyFormatId("jpeg", "image/jpeg")
    private val webpId = legacyFormatId("webp", "image/webp")
    private val pdfId = legacyFormatId("pdf", "application/pdf")
    private val txtId = legacyFormatId("txt", "text/plain")
    private val mdId = legacyFormatId("md", "text/markdown")
    private val htmlId = legacyFormatId("html", "text/html")

    private val seededPresets = listOf(
        CommonConversionPreset("png-to-jpeg", "PNG to JPG", "Shrink screenshots and share faster.", pngId, jpegId, "Images", true),
        CommonConversionPreset("jpeg-to-png", "JPG to PNG", "Switch photos into a lossless asset.", jpegId, pngId, "Images", true),
        CommonConversionPreset("png-to-webp", "PNG to WebP", "Keep transparency with smaller exports.", pngId, webpId, "Images", true),
        CommonConversionPreset("webp-to-png", "WebP to PNG", "Open web assets in broader editors.", webpId, pngId, "Images", true),
        CommonConversionPreset("image-to-pdf", "Image to PDF", "Wrap a single visual into a portable document.", pngId, pdfId, "Documents", true),
        CommonConversionPreset("txt-to-pdf", "Text to PDF", "Turn notes into a simple printable page.", txtId, pdfId, "Documents", false),
        CommonConversionPreset("md-to-html", "Markdown to HTML", "Share a lightweight rich-text version.", mdId, htmlId, "Text", true),
        CommonConversionPreset("md-to-pdf", "Markdown to PDF", "Make a basic document from Markdown.", mdId, pdfId, "Documents", false),
        CommonConversionPreset("html-to-txt", "HTML to TXT", "Strip markup and keep plain content.", htmlId, txtId, "Text", false),
        CommonConversionPreset("txt-to-html", "Text to HTML", "Wrap plain text in a readable page.", txtId, htmlId, "Text", false)
    )

    override val presets: Flow<List<CommonConversionPreset>> = flowOf(seededPresets)
    override val featuredPresets: Flow<List<CommonConversionPreset>> = flowOf(seededPresets.filter { it.pinnedByDefault }.take(6))

    override suspend fun findById(id: String): CommonConversionPreset? = seededPresets.firstOrNull { it.id == id }
}
