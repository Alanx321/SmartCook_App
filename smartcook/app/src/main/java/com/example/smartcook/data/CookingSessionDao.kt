package com.example.smartcook.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// Data Access Object for CookingSession operations

@Dao
interface CookingSessionDao {

    // Insert a new cooking session
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: CookingSessionEntity): Long

    // Update an existing session
    @Update
    suspend fun updateSession(session: CookingSessionEntity)

    // Get the active cooking session
    @Query("SELECT * FROM cooking_sessions WHERE isCompleted = 0 ORDER BY lastUpdateTimestamp DESC LIMIT 1")
    suspend fun getActiveSession(): CookingSessionEntity?

    // Get active session as Flow for reactive updates
    @Query("SELECT * FROM cooking_sessions WHERE isCompleted = 0 ORDER BY lastUpdateTimestamp DESC LIMIT 1")
    fun getActiveSessionFlow(): Flow<CookingSessionEntity?>

    // Get session by ID
    @Query("SELECT * FROM cooking_sessions WHERE sessionId = :sessionId")
    suspend fun getSessionById(sessionId: Long): CookingSessionEntity?

    // Get completed sessions for history
    @Query("SELECT * FROM cooking_sessions WHERE isCompleted = 1 ORDER BY lastUpdateTimestamp DESC")
    fun getCompletedSessionsFlow(): Flow<List<CookingSessionEntity>>

    // Mark a specific session as completed
    @Query("UPDATE cooking_sessions SET isCompleted = 1, lastUpdateTimestamp = :timestamp WHERE sessionId = :sessionId")
    suspend fun markSessionAsCompleted(sessionId: Long, timestamp: Long)

    // Get all interrupted sessions for recovery
    @Query("SELECT * FROM cooking_sessions WHERE wasInterrupted = 1 AND isCompleted = 0 ORDER BY lastUpdateTimestamp DESC")
    suspend fun getInterruptedSessions(): List<CookingSessionEntity>

    // Mark session as interrupted
    @Query("UPDATE cooking_sessions SET wasInterrupted = 1 WHERE sessionId = :sessionId")
    suspend fun markSessionAsInterrupted(sessionId: Long)

    // Mark all active sessions as interrupted
    @Query("UPDATE cooking_sessions SET wasInterrupted = 1 WHERE wasInterrupted = 0 AND isCompleted = 0")
    suspend fun markAllSessionsAsInterrupted()

    // Delete a specific session
    @Query("DELETE FROM cooking_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    // Delete all sessions for cleanup
    @Query("DELETE FROM cooking_sessions")
    suspend fun deleteAllSessions()

    // Delete old interrupted sessions cleanup
    @Query("DELETE FROM cooking_sessions WHERE wasInterrupted = 1 AND lastUpdateTimestamp < :timestampThreshold")
    suspend fun deleteOldInterruptedSessions(timestampThreshold: Long)

    // Check if there's an active session
    @Query("SELECT COUNT(*) > 0 FROM cooking_sessions WHERE wasInterrupted = 0 AND isCompleted = 0")
    suspend fun hasActiveSession(): Boolean

    // Check if there are interrupted sessions
    @Query("SELECT COUNT(*) > 0 FROM cooking_sessions WHERE wasInterrupted = 1 AND isCompleted = 0")
    suspend fun hasInterruptedSessions(): Boolean

    // Multi-cook session queries
    // Get all sessions in a multi-cook group
    @Query("SELECT * FROM cooking_sessions WHERE multiCookGroupId = :groupId ORDER BY sessionId ASC")
    suspend fun getSessionsByGroupId(groupId: String): List<CookingSessionEntity>

    // Mark all sessions in a group as completed
    @Query("UPDATE cooking_sessions SET isCompleted = 1, lastUpdateTimestamp = :timestamp WHERE multiCookGroupId = :groupId")
    suspend fun markGroupAsCompleted(groupId: String, timestamp: Long)

    // Delete all sessions in a multi-cook group
    @Query("DELETE FROM cooking_sessions WHERE multiCookGroupId = :groupId")
    suspend fun deleteSessionsByGroupId(groupId: String)

    // Get the most recent multi-cook group ID (active/interrupted only)
    @Query("SELECT multiCookGroupId FROM cooking_sessions WHERE isMultiCookMode = 1 AND wasInterrupted = 1 AND isCompleted = 0 ORDER BY lastUpdateTimestamp DESC LIMIT 1")
    suspend fun getMostRecentMultiCookGroupId(): String?

    // Check if a session is part of a multi-cook group
    @Query("SELECT isMultiCookMode FROM cooking_sessions WHERE sessionId = :sessionId")
    suspend fun isMultiCookSession(sessionId: Long): Boolean?
}
