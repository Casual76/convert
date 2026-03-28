package com.p2r3.convert.data.engine

import com.p2r3.convert.model.FormatDescriptor
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object FormatDetectionHeuristics {

    private val extensionAliasGroups = listOf(
        setOf("jpg", "jpeg", "jpe"),
        setOf("tif", "tiff"),
        setOf("htm", "html"),
        setOf("md", "markdown", "mdown"),
        setOf("yaml", "yml"),
        setOf("oga", "ogg"),
        setOf("mpg", "mpeg"),
        setOf("3gp", "3gpp"),
        setOf("7z", "7zip")
    )

    private val mimeAliases = mapOf(
        "image/jpg" to "image/jpeg",
        "image/pjpeg" to "image/jpeg",
        "image/x-png" to "image/png",
        "application/x-pdf" to "application/pdf",
        "application/pdf" to "application/pdf",
        "audio/x-wav" to "audio/wav",
        "audio/wave" to "audio/wav",
        "text/x-markdown" to "text/markdown",
        "text/markdown" to "text/markdown",
        "text/x-web-markdown" to "text/markdown",
        "application/x-markdown" to "text/markdown",
        "application/x-zip-compressed" to "application/zip",
        "application/x-7z-compressed" to "application/x-7z-compressed"
    )

    fun detect(
        catalog: List<FormatDescriptor>,
        uri: String?,
        fileName: String?,
        mimeType: String?
    ): FormatDescriptor? {
        val candidates = catalog.filter { it.supportsInput }
        if (candidates.isEmpty()) return null

        val normalizedMime = normalizeMime(mimeType)
        val names = buildNameCandidates(uri, fileName)

        val ranked = candidates
            .map { descriptor -> descriptor to score(descriptor, normalizedMime, names) }
            .sortedWith(
                compareByDescending<Pair<FormatDescriptor, Int>> { it.second }
                    .thenByDescending { it.first.nativePreferred }
                    .thenBy { it.first.displayName.lowercase(Locale.ROOT) }
            )
            .firstOrNull()

        return ranked?.first?.takeIf { ranked.second > 0 }
    }

    private fun score(
        descriptor: FormatDescriptor,
        normalizedMime: String?,
        names: List<String>
    ): Int {
        var score = 0
        val descriptorMime = normalizeMime(descriptor.mimeType)
        val descriptorExtensions = descriptorAliases(descriptor)
        val descriptorTokens = buildSet {
            addAll(descriptorExtensions)
            add(descriptor.shortLabel.lowercase(Locale.ROOT))
            add(formatToken(descriptor.id))
        }.filter { it.isNotBlank() }

        if (!normalizedMime.isNullOrBlank()) {
            if (descriptorMime == normalizedMime) {
                score += 90
            } else if (mimeAliases[normalizedMime] == descriptorMime) {
                score += 82
            } else if (descriptorMime != null && normalizedMime.substringBefore('/') == descriptorMime.substringBefore('/')) {
                score += 8
            }

            val mimeSubtype = normalizedMime.substringAfter('/', "").substringBefore('+')
            if (mimeSubtype.isNotBlank() && descriptorExtensions.any { it == mimeSubtype || aliasGroupFor(it).contains(mimeSubtype) }) {
                score += 34
            }
        }

        names.forEach { candidate ->
            val lowerName = candidate.lowercase(Locale.ROOT)
            descriptorExtensions.forEach { extension ->
                if (lowerName.endsWith(".$extension")) {
                    score += if (extension.contains('.')) 78 else 64
                } else if (aliasGroupFor(extension).any { alias -> lowerName.endsWith(".$alias") }) {
                    score += 56
                }
            }

            if (descriptorTokens.any { token -> token.length > 1 && lowerName.contains(".$token") }) {
                score += 10
            }

            if (descriptor.displayName.lowercase(Locale.ROOT) in lowerName) {
                score += 4
            }
        }

        return score
    }

    private fun descriptorAliases(descriptor: FormatDescriptor): Set<String> {
        val direct = sequenceOf(
            descriptor.extension,
            descriptor.shortLabel,
            formatToken(descriptor.id)
        )
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .toMutableSet()

        return buildSet {
            direct.forEach { token ->
                add(token)
                addAll(aliasGroupFor(token))
            }
        }
    }

    private fun aliasGroupFor(token: String): Set<String> =
        extensionAliasGroups.firstOrNull { token in it } ?: setOf(token)

    private fun formatToken(id: String): String =
        id.substringAfterLast('(', "").substringBefore(')').trim().lowercase(Locale.ROOT)

    private fun normalizeMime(value: String?): String? {
        val raw = value
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return mimeAliases[raw] ?: raw
    }

    private fun buildNameCandidates(uri: String?, fileName: String?): List<String> {
        val values = linkedSetOf<String>()
        fileName?.takeIf { it.isNotBlank() }?.let(values::add)

        uri
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?.let { encoded ->
                val decoded = runCatching {
                    URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
                }.getOrDefault(encoded)
                values += decoded
                values += encoded
            }

        return values.toList()
    }
}
