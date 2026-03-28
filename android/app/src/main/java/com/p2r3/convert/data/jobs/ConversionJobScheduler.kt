package com.p2r3.convert.data.jobs

import com.p2r3.convert.model.ConversionRequest

interface ConversionJobScheduler {
    suspend fun schedule(
        request: ConversionRequest,
        title: String,
        subtitle: String,
        presetTitle: String? = null
    ): Long
}
