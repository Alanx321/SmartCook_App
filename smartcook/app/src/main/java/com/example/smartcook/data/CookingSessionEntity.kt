package com.example.smartcook.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// Entity representing an active cooking session (Used to persist cooking state across app crashes and device reboots)
@Entity(tableName = "cooking_sessions")
data class CookingSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val sessionId: Long = 0,

    // Recipe information
    val recipeId: Long,
    val recipeName: String,

    val isCompleted: Boolean = false,
    
    // Multi-cook session grouping
    val multiCookGroupId: String? = null, // If non-null, this session is part of a multi-cook group
    val isMultiCookMode: Boolean = false, // True if this is a multi-cook session

    // Current progress
    val currentStepIndex: Int,
    val totalSteps: Int,

    // Timer state
    val isTimerActive: Boolean,
    val timerRemainingSeconds: Int, // Remaining time in seconds when session was saved
    val timerTotalSeconds: Int, // Original timer duration
    val timerPaused: Boolean,

    // Timing information
    val sessionStartTimestamp: Long, // When cooking started (System.currentTimeMillis())
    val lastUpdateTimestamp: Long, // Last time session state was saved
    val estimatedFinishTimestamp: Long, // When cooking should finish

    // Step information
    val currentStepInstruction: String,
    val currentStepDuration: Int,
    val currentStepIsTimed: Boolean,

    // Recovery metadata
    val wasInterrupted: Boolean = false, // Set to true on reboot/crash detection
    val recoveryAttempts: Int = 0 // Track how many times recovery was attempted
)

// Data class for session recovery information
data class SessionRecoveryInfo(
    val session: CookingSessionEntity,
    val elapsedTimeSinceLastUpdate: Long, // Milliseconds since last update
    val recalculatedTimerSeconds: Int, // New timer value after accounting for elapsed time
    val isTimerExpired: Boolean // Whether timer would have expired during interruption
)
