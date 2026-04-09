package com.p2r3.convert.data.history

import com.p2r3.convert.model.ConversionHistoryEntry
import com.p2r3.convert.model.ConversionStatus
import com.p2r3.convert.model.EngineRuntimeKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class RoomHistoryRepository @Inject constructor(
    private val historyDao: HistoryDao
) : HistoryRepository {

    override val history: Flow<List<ConversionHistoryEntry>> = historyDao.observeAll().map { list ->
        list.map(::toModel)
    }

    override suspend fun queueEntry(
        title: String,
        subtitle: String,
        inputCount: Int,
        presetTitle: String?,
        requestSnapshot: String?,
        routeToken: String?,
        runtimeKind: EngineRuntimeKind?,
        keepEntry: Boolean
    ): Long = historyDao.insert(
        HistoryEntryEntity(
            title = title,
            subtitle = subtitle,
            createdAt = System.currentTimeMillis(),
            status = ConversionStatus.QUEUED.name,
            inputCount = inputCount,
            outputCount = 0,
            outputUris = "",
            message = "Queued from the native conversion flow.",
            presetTitle = presetTitle,
            requestSnapshot = requestSnapshot,
            routeToken = routeToken,
            runtimeKind = runtimeKind?.name,
            usedFallback = false,
            diagnosticsMessage = null,
            keepEntry = keepEntry
        )
    )

    override suspend fun markRunning(id: Long) {
        update(id) { current ->
            current.copy(status = ConversionStatus.RUNNING.name, message = "Running on device.")
        }
    }

    override suspend fun markCompleted(
        id: Long,
        message: String,
        outputUris: List<String>,
        runtimeKind: EngineRuntimeKind,
        usedFallback: Boolean,
        diagnosticsMessage: String?,
        routeToken: String?
    ) {
        update(id) { current ->
            current.copy(
                status = ConversionStatus.COMPLETED.name,
                outputCount = outputUris.size,
                outputUris = outputUris.joinToString("\n"),
                message = message,
                runtimeKind = runtimeKind.name,
                usedFallback = usedFallback,
                diagnosticsMessage = diagnosticsMessage,
                routeToken = routeToken ?: current.routeToken
            )
        }
    }

    override suspend fun markFailed(id: Long, message: String, diagnosticsMessage: String?) {
        update(id) { current ->
            current.copy(
                status = ConversionStatus.FAILED.name,
                message = message,
                diagnosticsMessage = diagnosticsMessage
            )
        }
    }

    override suspend fun clearAll() {
        historyDao.clearAll()
    }

    override suspend fun getById(id: Long): ConversionHistoryEntry? = historyDao.getById(id)?.let(::toModel)

    private suspend fun update(id: Long, transform: (HistoryEntryEntity) -> HistoryEntryEntity) {
        val current = historyDao.getById(id) ?: return
        val updated = transform(current)
        if (!updated.keepEntry && updated.status in setOf(ConversionStatus.COMPLETED.name, ConversionStatus.FAILED.name)) {
            historyDao.deleteById(id)
            return
        }
        historyDao.update(updated)
    }

    private fun toModel(entity: HistoryEntryEntity): ConversionHistoryEntry = ConversionHistoryEntry(
        id = entity.id,
        title = entity.title,
        subtitle = entity.subtitle,
        createdAt = entity.createdAt,
        status = enumValueOf(entity.status),
        inputCount = entity.inputCount,
        outputCount = entity.outputCount,
        outputUris = entity.outputUris.split("\n").filter { it.isNotBlank() },
        message = entity.message,
        presetTitle = entity.presetTitle,
        requestSnapshot = entity.requestSnapshot,
        routeToken = entity.routeToken,
        runtimeKind = entity.runtimeKind?.let { enumValueOf<EngineRuntimeKind>(it) },
        usedFallback = entity.usedFallback,
        diagnosticsMessage = entity.diagnosticsMessage,
        keepEntry = entity.keepEntry
    )
}
