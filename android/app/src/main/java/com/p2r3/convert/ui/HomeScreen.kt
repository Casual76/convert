package com.p2r3.convert.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.p2r3.convert.model.CommonConversionPreset

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    uiState: MainUiState,
    reduceMotion: Boolean,
    onOpenConvert: () -> Unit,
    onPresetClick: (CommonConversionPreset) -> Unit
) {
    val diagnostics = uiState.engineDiagnostics
    ScreenContainer(
        title = "Convert to it!",
        subtitle = "Esperienza Android nativa, catalogo universale on-device e fallback bridge completamente invisibile.",
        reduceMotion = reduceMotion
    ) {
        item {
            AnimatedScreenBlock(index = 1, reduceMotion = reduceMotion) {
                HeroCard(
                    headline = uiState.engineHeadline,
                    body = uiState.engineBody,
                    primaryLabel = "Nuova conversione",
                    secondaryLabel = "Apri preset",
                    onPrimary = onOpenConvert,
                    onSecondary = onOpenConvert,
                    reduceMotion = reduceMotion
                )
            }
        }
        item {
            AnimatedScreenBlock(index = 2, reduceMotion = reduceMotion) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricPill(
                        label = "Catalogo",
                        value = "${diagnostics?.catalogFormatCount ?: 0} formati",
                        emphasized = true
                    )
                    MetricPill(
                        label = "Ingressi",
                        value = "${diagnostics?.inputFormatCount ?: 0} input"
                    )
                    MetricPill(
                        label = "Uscite",
                        value = "${diagnostics?.outputFormatCount ?: 0} output"
                    )
                    MetricPill(
                        label = "Handler",
                        value = "${diagnostics?.handlerCount ?: 0} attivi"
                    )
                }
            }
        }
        item {
            AnimatedScreenBlock(index = 3, reduceMotion = reduceMotion) {
                SectionTitle("Preset in evidenza", "Scorciatoie curate per partire subito dai casi d'uso piu comuni.")
            }
        }
        item {
            AnimatedScreenBlock(index = 4, reduceMotion = reduceMotion) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(uiState.featuredPresets) { index, preset ->
                        AnimatedScreenBlock(index = index, reduceMotion = reduceMotion) {
                            PresetCard(preset = preset, onClick = { onPresetClick(preset) })
                        }
                    }
                }
            }
        }
        item {
            AnimatedScreenBlock(index = 5, reduceMotion = reduceMotion) {
                SectionTitle("Stato motore", "Il runtime Android resta nativo nella UI e usa il bridge solo quando serve davvero.")
            }
        }
        item {
            AnimatedScreenBlock(index = 6, reduceMotion = reduceMotion) {
                StatusCard(
                    title = uiState.engineHeadline,
                    body = uiState.engineBody
                )
            }
        }
        item {
            AnimatedScreenBlock(index = 7, reduceMotion = reduceMotion) {
                SectionTitle("Recenti", "Gli ultimi job completati o in coda restano subito accessibili.")
            }
        }
        if (uiState.history.isEmpty()) {
            item {
                AnimatedScreenBlock(index = 8, reduceMotion = reduceMotion) {
                    EmptyStateCard(
                        title = "Ancora nessuna conversione",
                        body = "Apri la sezione Converti o scegli un preset per avviare il primo job."
                    )
                }
            }
        } else {
            itemsIndexed(uiState.history.take(4)) { index, entry ->
                AnimatedScreenBlock(index = 8 + index, reduceMotion = reduceMotion) {
                    HistoryEntryCard(entry = entry)
                }
            }
        }
    }
}
