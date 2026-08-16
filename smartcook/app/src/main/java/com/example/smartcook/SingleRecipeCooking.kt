package com.example.smartcook

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.smartcook.data.RecipeEntity
import com.example.smartcook.data.RecipeDatabase
import com.example.smartcook.data.cookingSteps
import com.example.smartcook.session.SessionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedSingleRecipeCooking(
    recipe: RecipeEntity,
    onNavigateBack: () -> Unit,
    resumeSessionId: Long? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Session management
    val database = remember { RecipeDatabase.getDatabase(context, scope) }
    val sessionManager = remember { SessionManager(context, database.cookingSessionDao()) }
    var currentSessionId by remember { mutableStateOf<Long>(-1) }

    // Use the actual cooking steps from the recipe
    val cookingSteps = recipe.cookingSteps

    var currentStepIndex by remember { mutableStateOf(0) }
    val currentStep = cookingSteps.getOrNull(currentStepIndex)

    var showExitDialog by remember { mutableStateOf(false) }
    var cookingStartTime by remember { mutableStateOf<Long?>(null) }
    var isRestoring by remember { mutableStateOf(resumeSessionId != null) }

    // Calculate estimated finish time 
    val estimatedFinishTime = remember(cookingSteps, cookingStartTime, currentStepIndex) {
        val startTime = cookingStartTime // Local variable for smart cast
        if (startTime == null) {
            // Before starting: estimate from now based on all remaining steps
            val remainingSteps = cookingSteps.drop(currentStepIndex)
            val remainingMinutes = remainingSteps.sumOf { it.durationMinutes }
            val finishTimeMs = System.currentTimeMillis() + (remainingMinutes * 60 * 1000)
            formatTime(finishTimeMs)
        } else {
            // After starting: calculate from the original start time
            val totalMinutes = cookingSteps.sumOf { it.durationMinutes }
            val finishTimeMs = startTime + (totalMinutes * 60 * 1000)
            formatTime(finishTimeMs)
        }
    }

    // Timer state only initialize when on a timed step
    val timerState = rememberCookingTimerState(
        context = context,
        initialMinutes = currentStep?.durationMinutes ?: 0,
        initialSeconds = 0,
        recipeName = recipe.name,
        currentStep = currentStep?.instruction ?: "",
        skipInitialSetup = resumeSessionId != null // Skip initial setup when resuming
    )

    // Update timer when step changes but not during initial restoration
    // Remove 'sessionRestored' from keys. Only run when currentStepIndex changes.
    LaunchedEffect(currentStepIndex) {
        // If we are currently restoring the session, dont update the timer
        // This prevents overwriting the restored remaining time with the full step duration.
        if (isRestoring) return@LaunchedEffect

        // Update timer when user navigates between steps
        currentStep?.let { step ->
            timerState.currentStep = step.instruction
            if (step.isTimed && step.durationMinutes > 0) {
                timerState.setTime(step.durationMinutes, 0)
            }
        }
    }

    // Create session immediately when entering cooking screen or restore from interruption
    LaunchedEffect(Unit) {
        if (resumeSessionId != null) {
            // Resuming from interrupted session
            currentSessionId = resumeSessionId
            scope.launch {
                val session = sessionManager.getActiveSession()
                if (session != null && session.sessionId == resumeSessionId) {
                    // Restore the step index and timer state from the session
                    currentStepIndex = session.currentStepIndex

                    // Set cooking start time from session
                    cookingStartTime = session.sessionStartTimestamp

                    // Retrieve the step object so we can access its static duration
                    val restoredStep = cookingSteps.getOrNull(session.currentStepIndex)

                    // Restore a timer that was actually RUNNING or PAUSED
                    if (session.timerTotalSeconds > 0 && (session.isTimerActive || session.timerRemainingSeconds > 0)) {
                        val currentTime = System.currentTimeMillis()
                        val elapsedSeconds = ((currentTime - session.lastUpdateTimestamp) / 1000).toInt()

                        // Calculate remaining time
                        val remainingSeconds = if (session.isTimerActive && !session.timerPaused) {
                            // Timer was running, subtract elapsed time
                            kotlin.math.max(0, session.timerRemainingSeconds - elapsedSeconds)
                        } else {
                            // Timer was paused, keep original time
                            session.timerRemainingSeconds
                        }

                        // Set the timer to the calculated remaining time
                        if (remainingSeconds > 0) {
                            timerState.setTime(remainingSeconds / 60, remainingSeconds % 60)
                            
                            // Restore the initial time so Reset works
                            val originalSeconds = session.timerTotalSeconds
                            timerState.setInitialTime(originalSeconds / 60, originalSeconds % 60)
                        } else {
                            // Timer expired while device was off
                            timerState.setTime(0, 0)
                            val originalSeconds = session.timerTotalSeconds
                            timerState.setInitialTime(originalSeconds / 60, originalSeconds % 60)
                        }
                    } else {
                        // The timer was not running (dormant/not started)
                        // Manually reset the timer to the step's full duration.
                        if (session.currentStepIsTimed && session.currentStepDuration > 0) {
                            timerState.setTime(session.currentStepDuration, 0)
                            timerState.setInitialTime(session.currentStepDuration, 0)
                        }
                    }

                    sessionManager.resumeSession(resumeSessionId)
                    // Add a small delay to ensure the "Step Change" effect runs 
                    // and sees isRestoring=true before we turn it off
                    delay(100)

                    // Mark restoration as complete after the state updates are queued
                    // This allows the LaunchedEffect(currentStepIndex) to run normally for future user clicks
                    isRestoring = false
                } else {
                    // If tried to resume but the session is gone (completed/deleted),
                    // fall back to creating a new session to avoid a broken state.
                    cookingStartTime = System.currentTimeMillis()
                    currentSessionId = sessionManager.createSession(
                        recipeId = recipe.id,
                        recipeName = recipe.name,
                        totalSteps = cookingSteps.size,
                        estimatedDurationMinutes = recipe.timeMinutes
                    )

                    // Explicitly set up the timer for the first step, no longer skipping setup
                    currentStep?.let { step ->
                        if (step.isTimed && step.durationMinutes > 0) {
                            timerState.setTime(step.durationMinutes, 0)
                        }

                        sessionManager.updateSession(
                            sessionId = currentSessionId,
                            currentStepIndex = 0,
                            currentStepInstruction = step.instruction,
                            currentStepDuration = step.durationMinutes,
                            currentStepIsTimed = step.isTimed,
                            isTimerActive = false,
                            timerRemainingSeconds = 0,
                            timerTotalSeconds = 0,
                            timerPaused = false
                        )
                    }
                    // Mark as restored so other LaunchedEffects can proceed normally
                    isRestoring = false
                }
            }
        } else {
            // Create new session immediately (Standard Flow)
            scope.launch {
                cookingStartTime = System.currentTimeMillis()
                currentSessionId = sessionManager.createSession(
                    recipeId = recipe.id,
                    recipeName = recipe.name,
                    totalSteps = cookingSteps.size,
                    estimatedDurationMinutes = recipe.timeMinutes
                )

                // Immediately save initial session state with current step
                currentStep?.let { step ->
                    sessionManager.updateSession(
                        sessionId = currentSessionId,
                        currentStepIndex = currentStepIndex,
                        currentStepInstruction = step.instruction,
                        currentStepDuration = step.durationMinutes,
                        currentStepIsTimed = step.isTimed,
                        isTimerActive = false,
                        timerRemainingSeconds = 0,
                        timerTotalSeconds = 0,
                        timerPaused = false
                    )
                }
            }
        }
    }

    // Link timer service to session
    LaunchedEffect(currentStepIndex, currentSessionId, timerState.timerService) {
        if (currentSessionId != -1L && timerState.timerService != null) {
            currentStep?.let { step ->
                timerState.timerService?.setSessionInfo(
                    sessionId = currentSessionId,
                    recipeId = recipe.id,
                    stepIndex = currentStepIndex,
                    totalSteps = cookingSteps.size,
                    stepDuration = step.durationMinutes,
                    stepIsTimed = step.isTimed
                )
            }
        }
    }

    val totalSteps = cookingSteps.size
    val progress = if (totalSteps > 0) (currentStepIndex + 1).toFloat() / totalSteps else 0f
    val isLastStep = currentStepIndex == totalSteps - 1

    fun checkAndStartTimer() {
        // Just start the timer directly
        timerState.start()
    }

    DisposableEffect(Unit) {
        onDispose {
            timerState.stopTimer()
        }
    }

    BackHandler {
        showExitDialog = true
    }

    // Dialogs
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("End Cooking Session?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure? Your progress will be lost.") },
            confirmButton = {
                Button(
                    onClick = {
                        timerState.stopTimer()
                        // Clean up session when exiting
                        scope.launch {
                            if (currentSessionId != -1L) {
                                sessionManager.deleteSession(currentSessionId)
                                
                                // Manually remove timer instance since not using cleanupSession
                                val timerId = timerState.getTimerId()
                                if (timerId != null) {
                                    timerState.timerService?.removeTimerInstance(timerId)
                                }
                            }
                            onNavigateBack()
                        }
                        showExitDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                ) {
                    Text("Yes, Exit")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExitDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recipe.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary) },
                navigationIcon = {
                    IconButton(onClick = { showExitDialog = true }) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Estimated finish time banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2196F3)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Estimated Finish Time",
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                        Text(
                            estimatedFinishTime,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            // Progress section
            Text(
                "Step ${currentStepIndex + 1} of $totalSteps",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp),
                    color = Color(0xFF4CAF50),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${(progress * 100).toInt()}%",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Current step card
            currentStep?.let { step ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (step.isTimed) Color(0xFFFF5252) else Color(0xFF4CAF50)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (step.isTimed) "⏰ TIMED STEP" else "✓ PREP STEP",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Surface(
                                color = Color.White.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    step.getDisplayDuration(),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Text(
                            step.instruction,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            lineHeight = 28.sp
                        )
                    }
                }

                // Timer section only show for timed steps
                AnimatedVisibility(visible = step.isTimed && step.durationMinutes > 0) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Timer",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Text(
                                timerState.getFormattedTime(),
                                fontSize = 56.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (timerState.isFinished()) Color(0xFFFF5252) else Color(0xFF2196F3),
                                modifier = Modifier.padding(vertical = 8.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (!timerState.soundStopped) {
                                    Button(
                                        onClick = {
                                            when {
                                                timerState.isFinished() -> timerState.stopSound()
                                                !timerState.isRunning -> checkAndStartTimer()
                                                timerState.isPaused -> timerState.resume()
                                                else -> timerState.pause()
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(52.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = when {
                                                timerState.isFinished() -> Color(0xFFFF5252)
                                                timerState.isPaused -> Color(0xFF4CAF50)
                                                timerState.isRunning -> Color(0xFFFF9800)
                                                else -> Color(0xFF4CAF50)
                                            }
                                        )
                                    ) {
                                        Text(
                                            when {
                                                timerState.isFinished() -> "DONE"
                                                !timerState.isRunning -> "START"
                                                timerState.isPaused -> "RESUME"
                                                else -> "PAUSE"
                                            },
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }

                                OutlinedButton(
                                    onClick = { timerState.reset() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp, 
                                        MaterialTheme.colorScheme.outline
                                    )
                                ) {
                                    Text("RESET", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Navigation Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (currentStepIndex > 0) {
                            // Stop the current timer before moving to the previous step
                            timerState.stopTimer()

                            // Move to previous step, update session before going back
                            val prevStepIndex = currentStepIndex - 1
                            val prevStep = cookingSteps[prevStepIndex]
                            scope.launch {
                                if (currentSessionId != -1L) {
                                    sessionManager.updateSession(
                                        sessionId = currentSessionId,
                                        currentStepIndex = prevStepIndex,
                                        currentStepInstruction = prevStep.instruction,
                                        currentStepDuration = prevStep.durationMinutes,
                                        currentStepIsTimed = prevStep.isTimed,
                                        isTimerActive = false,
                                        timerRemainingSeconds = 0,
                                        timerTotalSeconds = 0,
                                        timerPaused = false
                                    )
                                }
                            }
                            currentStepIndex = prevStepIndex
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    enabled = currentStepIndex > 0,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("PREVIOUS", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        // Recalculate to ensure we have the latest state
                        val actuallyLastStep = currentStepIndex >= cookingSteps.size - 1

                        if (actuallyLastStep) {
                            val timerId = timerState.getTimerId()
                            if (timerId != null && currentSessionId != -1L) {
                                timerState.timerService?.cleanupSession(currentSessionId, timerId) { onNavigateBack() }
                            } else {
                                scope.launch {
                                    if (currentSessionId != -1L) {
                                        sessionManager.completeSession(currentSessionId)
                                    }
                                    onNavigateBack()
                                }
                            }
                        } else {
                            // Stop the current timer before moving to the next step
                            timerState.stopTimer()

                            // Move to next step, update session before advancing
                            val nextStepIndex = currentStepIndex + 1
                            if (nextStepIndex < cookingSteps.size) {
                                val nextStep = cookingSteps[nextStepIndex]
                                scope.launch {
                                    if (currentSessionId != -1L) {
                                        sessionManager.updateSession(
                                            sessionId = currentSessionId,
                                            currentStepIndex = nextStepIndex,
                                            currentStepInstruction = nextStep.instruction,
                                            currentStepDuration = nextStep.durationMinutes,
                                            currentStepIsTimed = nextStep.isTimed,
                                            isTimerActive = false,
                                            timerRemainingSeconds = 0,
                                            timerTotalSeconds = 0,
                                            timerPaused = false
                                        )
                                    }
                                }
                                currentStepIndex = nextStepIndex
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLastStep) Color(0xFFFF5252) else Color(0xFF4CAF50)
                    )
                ) {
                    Text(
                        if (isLastStep) "FINISH" else "NEXT",
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (isLastStep) Icons.Default.Check else Icons.Default.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// Helper function to format time
private fun formatTime(timeMillis: Long): String {
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = timeMillis
    val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = calendar.get(java.util.Calendar.MINUTE)
    return String.format("%02d:%02d", hour, minute)
}