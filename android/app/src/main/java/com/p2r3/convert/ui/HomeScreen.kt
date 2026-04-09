package com.p2r3.convert.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.p2r3.convert.model.BridgeValidationState
import com.p2r3.convert.model.CommonConversionPreset
import com.p2r3.convert.model.ConversionHistoryEntry

@Composable
fun HomeScreen(
    uiState: MainUiState,
    reduceMotion: Boolean,
    onOpenConvert: () -> Unit,
    onPresetClick: (CommonConversionPreset) -> Unit
) {
    val diagnostics = uiState.engineDiagnostics

    ScreenContainer {
        item {
            AnimatedScreenBlock(index = 0, reduceMotion = reduceMotion) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = uiState.engineHeadline,
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Text(
                                text = "Scegli i file, controlla il formato suggerito e avvia il job quando sei pronto.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = onOpenConvert,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Inizia nuova conversione")
                        }
                    }
                }
            }
        }

        item {
            AnimatedScreenBlock(index = 1, reduceMotion = reduceMotion) {
                SectionTitle(
                    title = "Stato rapido",
                    body = "Informazioni essenziali sul motore e sulla disponibilita del bridge."
                )
            }
        }

        item {
            AnimatedScreenBlock(index = 2, reduceMotion = reduceMotion) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        ListItem(
                            headlineContent = { Text("Catalogo disponibile") },
                            supportingContent = {
                                Text(
                                    "${diagnostics?.catalogFormatCount ?: 0} formati, ${diagnostics?.inputFormatCount ?: 0} ingressi, ${diagnostics?.outputFormatCount ?: 0} uscite.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.DataObject,
                                    contentDescription = null
                                )
                            }
                        )
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("Bridge") },
                            supportingContent = {
                                Text(
                                    bridgeSummary(
                                        state = diagnostics?.bridgeValidationState ?: BridgeValidationState.WARMING_UP,
                                        handlerCount = diagnostics?.validatedBridgeHandlerCount ?: 0,
                                        diagnosticsMessage = diagnostics?.diagnosticsMessage
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.AutoAwesome,
                                    contentDescription = null
                                )
                            }
                        )
                    }
                }
            }
        }

        item {
            AnimatedScreenBlock(index = 3, reduceMotion = reduceMotion) {
                SectionTitle(
                    title = "Scorciatoie utili",
                    body = "Preset gia pronti per i flussi piu frequenti."
                )
            }
        }

        if (uiState.featuredPresets.isEmpty()) {
            item {
                AnimatedScreenBlock(index = 4, reduceMotion = reduceMotion) {
                    EmptyStateCard(
                        title = "Nessun preset disponibile",
                        body = "Appena il catalogo e pronto vedrai qui le scorciatoie piu utili."
                    )
                }
            }
        } else {
            itemsIndexed(uiState.featuredPresets.take(4), key = { _, preset -> preset.id }) { index, preset ->
                AnimatedScreenBlock(index = 4 + index, reduceMotion = reduceMotion) {
                    ElevatedCard(
                        onClick = { onPresetClick(preset) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ListItem(
                            headlineContent = { Text(preset.title) },
                            supportingContent = {
                                Text(
                                    preset.subtitle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            overlineContent = { Text(preset.category) }
                        )
                    }
                }
            }
        }

        item {
            AnimatedScreenBlock(index = 20, reduceMotion = reduceMotion) {
                SectionTitle(
                    title = "Ultimi job",
                    body = "Stato, output e dettagli utili degli ultimi tentativi."
                )
            }
        }

        if (uiState.history.isEmpty()) {
            item {
                AnimatedScreenBlock(index = 21, reduceMotion = reduceMotion) {
                    EmptyStateCard(
                        title = "Cronologia vuota",
                        body = "La cronologia comparira qui dopo il primo job."
                    )
                }
            }
        } else {
            itemsIndexed(uiState.history.take(4), key = { _, entry -> entry.id }) { index, entry ->
                AnimatedScreenBlock(index = 21 + index, reduceMotion = reduceMotion) {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text(entry.title) },
                            overlineContent = { Text(historyMeta(entry)) },
                            supportingContent = {
                                Text(
                                    buildString {
                                        append(entry.message)
                                        entry.diagnosticsMessage?.takeIf { it.isNotBlank() }?.let {
                                            append("\n")
                                            append(it)
                                        }
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.TaskAlt,
                                    contentDescription = null
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun bridgeSummary(
    state: BridgeValidationState,
    handlerCount: Int,
    diagnosticsMessage: String?
): String {
    val status = when (state) {
        BridgeValidationState.WARMING_UP -> "Sto ancora verificando le route sul device."
        BridgeValidationState.VALIDATED -> "Bridge validato e pronto all'uso."
        BridgeValidationState.UNAVAILABLE -> "Bridge limitato: uso prima il motore nativo."
    }
    val handlers = if (handlerCount > 0) " Handler validati: $handlerCount." else ""
    val details = diagnosticsMessage?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
    return status + handlers + details
}

private fun historyMeta(entry: ConversionHistoryEntry): String = buildString {
    append(entry.status.name)
    append(" - ")
    append(entry.runtimeKind?.name ?: "N/D")
    if (entry.usedFallback) {
        append(" - fallback")
    }
}
