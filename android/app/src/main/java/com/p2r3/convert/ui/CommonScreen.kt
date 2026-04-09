package com.p2r3.convert.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.p2r3.convert.model.CommonConversionPreset

@Composable
fun CommonScreen(
    presets: List<CommonConversionPreset>,
    reduceMotion: Boolean,
    onPresetClick: (CommonConversionPreset) -> Unit
) {
    val grouped = presets.groupBy { it.category }

    ScreenContainer {
        if (grouped.isEmpty()) {
            item {
                AnimatedScreenBlock(index = 0, reduceMotion = reduceMotion) {
                    EmptyStateCard(
                        title = "Nessun preset disponibile",
                        body = "Il catalogo preset comparira qui quando il motore completa l'inizializzazione."
                    )
                }
            }
        } else {
            var blockIndex = 0
            grouped.forEach { (category, categoryPresets) ->
                item {
                    AnimatedScreenBlock(index = blockIndex++, reduceMotion = reduceMotion) {
                        SectionTitle(
                            title = category,
                            body = "Scegli una scorciatoia pronta e passa subito alla schermata di conversione."
                        )
                    }
                }
                item {
                    AnimatedScreenBlock(index = blockIndex++, reduceMotion = reduceMotion) {
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                categoryPresets.forEachIndexed { index, preset ->
                                    if (index > 0) {
                                        HorizontalDivider()
                                    }
                                    ListItem(
                                        headlineContent = { Text(preset.title) },
                                        supportingContent = {
                                            Text(
                                                preset.subtitle,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onPresetClick(preset) },
                                        overlineContent = { Text("Tocca per aprire il flusso") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
