package com.p2r3.convert.ui

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

enum class IncomingShareSource {
    SHARE_SHEET,
    OPEN_WITH
}

data class IncomingSharePayload(
    val uris: List<Uri>,
    val source: IncomingShareSource,
    val action: String
)

data class IncomingImportPrompt(
    val title: String,
    val body: String,
    val confirmLabel: String = "Sostituisci",
    val dismissLabel: String = "Mantieni attuale"
)

object IncomingShareIntentParser {
    fun parse(intent: Intent?): IncomingSharePayload? {
        val action = intent?.action ?: return null
        val uris = linkedSetOf<Uri>()

        when (action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::add)
                collectClipData(intent, uris)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.forEach(uris::add)
                collectClipData(intent, uris)
            }

            Intent.ACTION_VIEW -> {
                intent.data?.let(uris::add)
                collectClipData(intent, uris)
            }

            else -> return null
        }

        val sanitized = uris
            .filter(::isSupportedUri)
            .distinctBy(Uri::toString)

        if (sanitized.isEmpty()) return null

        return IncomingSharePayload(
            uris = sanitized,
            source = if (action == Intent.ACTION_VIEW) IncomingShareSource.OPEN_WITH else IncomingShareSource.SHARE_SHEET,
            action = action
        )
    }

    private fun collectClipData(intent: Intent, uris: MutableSet<Uri>) {
        val clipData = intent.clipData ?: return
        for (index in 0 until clipData.itemCount) {
            clipData.getItemAt(index).uri?.let(uris::add)
        }
    }

    private fun isSupportedUri(uri: Uri): Boolean = when (uri.scheme?.lowercase()) {
        "content", "file" -> true
        else -> false
    }
}
