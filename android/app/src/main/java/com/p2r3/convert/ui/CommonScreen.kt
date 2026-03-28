package com.p2r3.convert.ui

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import com.p2r3.convert.model.CommonConversionPreset

@Composable
fun CommonScreen(
    presets: List<CommonConversionPreset>,
    reduceMotion: Boolean,
    onPresetClick: (CommonConversionPreset) -> Unit
) {
    val grouped = presets.groupBy { it.category }
    ScreenContainer(
        title = "Conversioni comuni",
        subtitle = "Preset curati per i casi d'uso piu frequenti, ordinati per categoria e pronti al tocco.",
        reduceMotion = reduceMotion
    ) {
        var blockIndex = 1
        grouped.forEach { (category, categoryPresets) ->
            item {
                AnimatedScreenBlock(index = blockIndex++, reduceMotion = reduceMotion) {
                    SectionTitle(category, "Preset ordinati per utilita immediata.")
                }
            }
            itemsIndexed(categoryPresets) { index, preset ->
                AnimatedScreenBlock(index = blockIndex + index, reduceMotion = reduceMotion) {
                    PresetRow(preset = preset, onClick = { onPresetClick(preset) })
                }
            }
            blockIndex += categoryPresets.size
        }
    }
}
