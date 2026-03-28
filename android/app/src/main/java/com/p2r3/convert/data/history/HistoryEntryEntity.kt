package com.p2r3.convert.data.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversion_history")
data class HistoryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val subtitle: String,
    val createdAt: Long,
    val status: String,
    val inputCount: Int,
    val outputCount: Int,
    val outputUris: String,
    val message: String,
    val presetTitle: String?,
    val requestSnapshot: String?,
    val routeToken: String?,
    val runtimeKind: String?,
    val usedFallback: Boolean,
    val keepEntry: Boolean
)
