package com.p2r3.convert.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.p2r3.convert.model.ConversionHistoryEntry

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    entries: List<ConversionHistoryEntry>,
    reduceMotion: Boolean,
    onRerun: (ConversionHistoryEntry) -> Unit
) {
    val context = LocalContext.current

    ScreenContainer {
        if (entries.isEmpty()) {
            item {
                AnimatedScreenBlock(index = 0, reduceMotion = reduceMotion) {
                    EmptyStateCard(
                        title = "Cronologia vuota",
                        body = "Quando completi o fallisci un job, qui vedrai stato, output e diagnostica."
                    )
                }
            }
        } else {
            itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                AnimatedScreenBlock(index = index, reduceMotion = reduceMotion) {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ListItem(
                                headlineContent = { Text(entry.title) },
                                overlineContent = { Text(historySummary(entry)) },
                                supportingContent = {
                                    Text(
                                        buildString {
                                            append(entry.subtitle)
                                            append("\n")
                                            append(entry.message)
                                            entry.diagnosticsMessage?.takeIf { it.isNotBlank() }?.let {
                                                append("\n")
                                                append(it)
                                            }
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SummaryChip(
                                    label = "${entry.outputCount} output",
                                    icon = Icons.Outlined.TaskAlt,
                                    highlighted = entry.outputCount > 0
                                )
                                if (!entry.diagnosticsMessage.isNullOrBlank()) {
                                    SummaryChip(
                                        label = "Diagnostica disponibile",
                                        icon = Icons.Outlined.BugReport
                                    )
                                }
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
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

private fun historySummary(entry: ConversionHistoryEntry): String = buildString {
    append(entry.status.name)
    append(" - ")
    append(entry.runtimeKind?.name ?: "N/D")
    if (entry.usedFallback) {
        append(" - fallback")
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
