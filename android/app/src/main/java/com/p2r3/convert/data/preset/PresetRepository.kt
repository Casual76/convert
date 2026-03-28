package com.p2r3.convert.data.preset

import com.p2r3.convert.model.CommonConversionPreset
import kotlinx.coroutines.flow.Flow

interface PresetRepository {
    val presets: Flow<List<CommonConversionPreset>>
    val featuredPresets: Flow<List<CommonConversionPreset>>

    suspend fun findById(id: String): CommonConversionPreset?
}
