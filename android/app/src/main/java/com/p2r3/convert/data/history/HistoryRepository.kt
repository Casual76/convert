package com.p2r3.convert.data.history

import com.p2r3.convert.model.ConversionHistoryEntry
import com.p2r3.convert.model.EngineRuntimeKind
import com.p2r3.convert.model.ConversionStatus
import kotlinx.coroutines.flow.Flow

interface HistoryRepository {
    val history: Flow<List<ConversionHistoryEntry>>

    suspend fun queueEntry(
        title: String,
        subtitle: String,
        inputCount: Int,
        presetTitle: String? = null,
        requestSnapshot: String? = null,
        routeToken: String? = null,
        runtimeKind: EngineRuntimeKind? = null,
        keepEntry: Boolean = true
    ): Long

    suspend fun markRunning(id: Long)
    suspend fun markCompleted(
        id: Long,
        message: String,
        outputUris: List<String>,
        runtimeKind: EngineRuntimeKind,
        usedFallback: Boolean,
        diagnosticsMessage: String? = null,
        routeToken: String? = null
    )
    suspend fun markFailed(id: Long, message: String, diagnosticsMessage: String? = null)
    suspend fun clearAll()
    suspend fun getById(id: Long): ConversionHistoryEntry?
}
