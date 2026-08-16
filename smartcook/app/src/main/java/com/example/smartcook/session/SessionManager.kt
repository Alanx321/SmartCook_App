package com.example.smartcook.session

import android.content.Context
import android.util.Log
import com.example.smartcook.data.CookingSessionDao
import com.example.smartcook.data.CookingSessionEntity
import com.example.smartcook.data.SessionRecoveryInfo
import kotlinx.coroutines.flow.Flow
import kotlin.math.max

// Manages cooking session state persistence and recovery
class SessionManager(
    private val context: Context,
    private val cookingSessionDao: CookingSessionDao
) {
    companion object {
        private const val TAG = "SessionManager"
        private const val SESSION_TIMEOUT_HOURS = 24 // Clean up sessions older than 24 hours
    }

    // Create a new cooking session
    suspend fun createSession(
        recipeId: Long,
        recipeName: String,
        totalSteps: Int,
        estimatedDurationMinutes: Int,
        multiCookGroupId: String? = null,
        isMultiCookMode: Boolean = false
    ): Long {
        val currentTime = System.currentTimeMillis()

        val session = CookingSessionEntity(
            recipeId = recipeId,
            recipeName = recipeName,
            multiCookGroupId = multiCookGroupId,
            isMultiCookMode = isMultiCookMode,
            currentStepIndex = 0,
            totalSteps = totalSteps,
            isTimerActive = false,
            timerRemainingSeconds = 0,
            timerTotalSeconds = 0,
            timerPaused = false,
            sessionStartTimestamp = currentTime,
            lastUpdateTimestamp = currentTime,
            estimatedFinishTimestamp = currentTime + (estimatedDurationMinutes * 60 * 1000),
            currentStepInstruction = "",
            currentStepDuration = 0,
            currentStepIsTimed = false,
            wasInterrupted = false,
            recoveryAttempts = 0
        )

        val sessionId = cookingSessionDao.insertSession(session)
        val modeType = if (isMultiCookMode) "multi-cook" else "single"
        Log.d(TAG, "Created new $modeType cooking session: $sessionId for recipe: $recipeName")
        return sessionId
    }

    // Update session state whenever cooking state changes
    suspend fun updateSession(
        sessionId: Long,
        currentStepIndex: Int,
        currentStepInstruction: String,
        currentStepDuration: Int,
        currentStepIsTimed: Boolean,
        isTimerActive: Boolean,
        timerRemainingSeconds: Int,
        timerTotalSeconds: Int,
        timerPaused: Boolean
    ) {
        val session = cookingSessionDao.getSessionById(sessionId)
        if (session != null) {
            val updatedSession = session.copy(
                currentStepIndex = currentStepIndex,
                currentStepInstruction = currentStepInstruction,
                currentStepDuration = currentStepDuration,
                currentStepIsTimed = currentStepIsTimed,
                isTimerActive = isTimerActive,
                timerRemainingSeconds = timerRemainingSeconds,
                timerTotalSeconds = timerTotalSeconds,
                timerPaused = timerPaused,
                lastUpdateTimestamp = System.currentTimeMillis()
            )
            cookingSessionDao.updateSession(updatedSession)
            Log.d(TAG, "Updated session $sessionId: step $currentStepIndex, timer: $timerRemainingSeconds s")
        }
    }

    // Get active session
    suspend fun getActiveSession(): CookingSessionEntity? {
        return cookingSessionDao.getActiveSession()
    }

    // Get active session as Flow
    fun getActiveSessionFlow(): Flow<CookingSessionEntity?> {
        return cookingSessionDao.getActiveSessionFlow()
    }

    //Check for interrupted sessions and calculate recovery information
    suspend fun checkForInterruptedSessions(): List<SessionRecoveryInfo> {
        val interruptedSessions = cookingSessionDao.getInterruptedSessions()

        return interruptedSessions.map { session ->
            val currentTime = System.currentTimeMillis()
            val elapsedTime = currentTime - session.lastUpdateTimestamp

            // Calculate how much time has passed since the timer was last updated
            val elapsedSeconds = (elapsedTime / 1000).toInt()

            // Recalculate timer based on elapsed time
            val recalculatedTimer = if (session.isTimerActive && !session.timerPaused) {
                // Timer was running, subtract elapsed time
                max(0, session.timerRemainingSeconds - elapsedSeconds)
            } else {
                // Timer was paused or not running, keep the same value
                session.timerRemainingSeconds
            }

            val isExpired = session.isTimerActive &&
                           !session.timerPaused &&
                           recalculatedTimer == 0

            SessionRecoveryInfo(
                session = session,
                elapsedTimeSinceLastUpdate = elapsedTime,
                recalculatedTimerSeconds = recalculatedTimer,
                isTimerExpired = isExpired
            )
        }
    }

    // Mark all active sessions as interrupted
    suspend fun markAllSessionsAsInterrupted() {
        cookingSessionDao.markAllSessionsAsInterrupted()
        Log.d(TAG, "Marked all active sessions as interrupted (device rebooted)")
    }

    // Resume a session after recovery
    suspend fun resumeSession(sessionId: Long, incrementRecoveryAttempts: Boolean = true) {
        val session = cookingSessionDao.getSessionById(sessionId)
        if (session != null) {
            val updatedSession = session.copy(
                wasInterrupted = false,
                recoveryAttempts = if (incrementRecoveryAttempts) session.recoveryAttempts + 1 else session.recoveryAttempts,
                lastUpdateTimestamp = System.currentTimeMillis()
            )
            cookingSessionDao.updateSession(updatedSession)
            Log.d(TAG, "Resumed session $sessionId")
        }
    }

    // Delete a session when cooking is completed or cancelled
    suspend fun deleteSession(sessionId: Long) {
        cookingSessionDao.deleteSession(sessionId)
        Log.d(TAG, "Deleted session $sessionId")
    }

    // Clean up old interrupted sessions
    suspend fun cleanupOldSessions() {
        val thresholdTime = System.currentTimeMillis() - (SESSION_TIMEOUT_HOURS * 60 * 60 * 1000)
        cookingSessionDao.deleteOldInterruptedSessions(thresholdTime)
        Log.d(TAG, "Cleaned up sessions older than $SESSION_TIMEOUT_HOURS hours")
    }

    // Check if there are any interrupted sessions
    suspend fun hasInterruptedSessions(): Boolean {
        return cookingSessionDao.hasInterruptedSessions()
    }

    // Check if there's an active session
    suspend fun hasActiveSession(): Boolean {
        return cookingSessionDao.hasActiveSession()
    }

    // Format elapsed time for display
    fun formatElapsedTime(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "$hours hour${if (hours > 1) "s" else ""} ago"
            minutes > 0 -> "$minutes minute${if (minutes > 1) "s" else ""} ago"
            else -> "$seconds second${if (seconds > 1) "s" else ""} ago"
        }
    }

    // Format timer duration for display
    fun formatTimerDuration(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
    }

    // Multi-cook group management
    // Get all sessions in a multi-cook group
    suspend fun getSessionsByGroupId(groupId: String): List<CookingSessionEntity> {
        return cookingSessionDao.getSessionsByGroupId(groupId)
    }

    // Delete all sessions in a multi-cook group
    suspend fun deleteSessionGroup(groupId: String) {
        cookingSessionDao.deleteSessionsByGroupId(groupId)
        Log.d(TAG, "Deleted all sessions in group: $groupId")
    }

    // Check if a session is part of a multi-cook group
    suspend fun isMultiCookSession(sessionId: Long): Boolean {
        return cookingSessionDao.isMultiCookSession(sessionId) ?: false
    }

    // Get the most recent multi-cook group ID for restoration
    suspend fun getMostRecentMultiCookGroupId(): String? {
        return cookingSessionDao.getMostRecentMultiCookGroupId()
    }

    suspend fun completeSession(sessionId: Long) {
        cookingSessionDao.markSessionAsCompleted(sessionId, System.currentTimeMillis())
        Log.d(TAG, "Marked session $sessionId as completed (History saved)")
    }

    suspend fun completeSessionGroup(groupId: String) {
        cookingSessionDao.markGroupAsCompleted(groupId, System.currentTimeMillis())
        Log.d(TAG, "Marked group $groupId as completed")
    }

    fun getCompletedSessionsFlow(): Flow<List<CookingSessionEntity>> {
        return cookingSessionDao.getCompletedSessionsFlow()
    }
}
