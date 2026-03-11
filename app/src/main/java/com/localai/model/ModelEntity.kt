package com.localai.model

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── Entity ───────────────────────────────────────────────────────────────────

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val promptTemplate: String,
    val downloadedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long   = 0L,
    val isActive: Boolean  = false
)

// ── DAO ──────────────────────────────────────────────────────────────────────

@Dao
interface ModelDao {

    @Query("SELECT * FROM models ORDER BY lastUsedAt DESC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models ORDER BY lastUsedAt DESC")
    suspend fun getAll(): List<ModelEntity>

    @Query("SELECT * FROM models WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ModelEntity?

    @Query("SELECT * FROM models WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): ModelEntity?

    @Query("SELECT * FROM models WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<ModelEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: ModelEntity)

    @Delete
    suspend fun delete(model: ModelEntity)

    @Query("UPDATE models SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE models SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: String)

    @Query("UPDATE models SET lastUsedAt = :ts WHERE id = :id")
    suspend fun updateLastUsed(id: String, ts: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM models")
    suspend fun count(): Int
}
