package com.p2r3.convert.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.p2r3.convert.model.ConversionHistoryEntry

@Composable
fun HistoryScreen(
    entries: List<ConversionHistoryEntry>,
    reduceMotion: Boolean,
    onRerun: (ConversionHistoryEntry) -> Unit
) {
    val context = LocalContext.current
    ScreenContainer(
        title = "Cronologia",
        subtitle = "Job recenti, stato, output generati e scorciatoie per aprire o condividere.",
        reduceMotion = reduceMotion
    ) {
        if (entries.isEmpty()) {
            item {
                AnimatedScreenBlock(index = 1, reduceMotion = reduceMotion) {
                    EmptyStateCard(
                        title = "Cronologia vuota",
                        body = "Appena lanci un job, qui compariranno risultato, file creati e azioni utili."
                    )
                }
            }
        } else {
            itemsIndexed(entries) { index, entry ->
                AnimatedScreenBlock(index = 1 + index, reduceMotion = reduceMotion) {
                    Card {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium)
                            Text(entry.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val runtimeLabel = entry.runtimeKind?.name ?: "N/D"
                            Text(
                                "${entry.status.name.lowercase().replaceFirstChar(Char::titlecase)} - ${entry.outputCount} output - $runtimeLabel${if (entry.usedFallback) " fallback" else ""}",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(entry.message, style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (!entry.requestSnapshot.isNullOrBlank()) {
                                    Button(onClick = { onRerun(entry) }) {
                                        Text("Riesegui")
                                    }
                                }
                                if (entry.outputUris.isNotEmpty()) {
                                    OutlinedButton(onClick = { openUri(context, entry.outputUris.first()) }) {
                                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Apri")
                                    }
                                    OutlinedButton(onClick = { shareUris(context, entry.outputUris) }) {
                                        Icon(Icons.Outlined.Share, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (entry.outputUris.size > 1) "Condividi tutto" else "Condividi")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun openUri(context: Context, rawUri: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(rawUri.toUri(), context.contentResolver.getType(rawUri.toUri()))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}

private fun shareUris(context: Context, rawUris: List<String>) {
    val uris = rawUris.map(String::toUri)
    if (uris.isEmpty()) return

    val mimeType = uris
        .mapNotNull(context.contentResolver::getType)
        .distinct()
        .singleOrNull()
        ?: "*/*"

    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uris.first())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = android.content.ClipData.newUri(context.contentResolver, "Converted files", uris.first()).apply {
                uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    runCatching { context.startActivity(Intent.createChooser(intent, "Condividi conversione")) }
}
