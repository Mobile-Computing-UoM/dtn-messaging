package com.oppmesh.dtn.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey
    val nodeId: String, // 8-character ID, e.g. "HX3ASMBW"
    val alias: String,  // e.g. "Hospital", "Alice", "Headquarters"
    val notes: String = "",
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)
