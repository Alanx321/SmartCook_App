package com.example.smartcook.ui.theme

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartcook.CookingTimerState
import com.example.smartcook.data.*
import com.example.smartcook.rememberCookingTimerState
import com.example.smartcook.session.SessionManager
import com.example.smartcook.viewmodel.RecipeViewModel
import com.example.smartcook.MultiCookCoordinator
import com.example.smartcook.MultiRecipeCookingState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiCookModeScreen(
    selectedRecipes: List<Recipe>,
    viewModel: RecipeViewModel,
    onBackClick: () -> Unit = {},
    onTimelineViewClick: () -> Unit = {},
    restoreGroupId: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Sheet state
    var showTimelineSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // Fetch RecipeEntity objects for selected recipes
    val allRecipesFromDb by viewModel.allRecipes.observeAsState(emptyList())
    val recipeEntities = remember(selectedRecipes, allRecipesFromDb) {
        selectedRecipes.mapNotNull { selectedRecipe ->
            allRecipesFromDb.find { it.id.toInt() == selectedRecipe.id }
        }
    }

    // Session and database management
    val database = remember { RecipeDatabase.getDatabase(context, scope) }
    val sessionManager = remember { SessionManager(context, database.cookingSessionDao()) }

    // State for each recipe
    var recipeStates by remember { mutableStateOf<List<MultiRecipeCookingState>>(emptyList()) }

    var scheduleMap by remember { mutableStateOf<Map<Long, MultiCookCoordinator.RecipeSchedule>>(emptyMap()) }
    var currentTimeTrigger by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while(true) {
            kotlinx.coroutines.delay(30000)
            currentTimeTrigger = System.currentTimeMillis()
        }
    }

    // Multi-cook group ID use provided ID for restoration or generate new one
    val multiCookGroupId = remember { restoreGroupId ?: java.util.UUID.randomUUID().toString() }

    // Initialize recipe states when entities are loaded
    LaunchedEffect(recipeEntities) {
        if (recipeStates.isEmpty() && recipeEntities.isNotEmpty()) {
            recipeStates = recipeEntities.map { entity ->
                MultiRecipeCookingState(recipe = entity)
            }
        }
    }

    // Timer states for each recipe
    val timerStates = recipeEntities.mapIndexed { index, recipe ->
        val currentStep = recipe.cookingSteps.getOrNull(
            recipeStates.getOrNull(index)?.currentStepIndex ?: 0
        )
        rememberCookingTimerState(
            context = context,
            initialMinutes = currentStep?.durationMinutes ?: 0,
            initialSeconds = 0,
            recipeName = recipe.name,
            currentStep = currentStep?.instruction ?: "",
            skipInitialSetup = restoreGroupId != null
        )
    }

    // Set multi-cook group ID on each timer immediately when service is bound
    timerStates.forEachIndexed { index, timerState ->
        LaunchedEffect(timerState.timerService) {
            if (timerState.timerService != null) {
                val state = recipeStates.getOrNull(index)
                if (state != null && state.sessionId != -1L) {
                    val currentStep = state.recipe.cookingSteps.getOrNull(state.currentStepIndex)
                    currentStep?.let { step ->
                        timerState.timerService?.setSessionInfo(
                            sessionId = state.sessionId,
                            recipeId = state.recipe.id,
                            stepIndex = state.currentStepIndex,
                            totalSteps = state.recipe.cookingSteps.size,
                            stepDuration = step.durationMinutes,
                            stepIsTimed = step.isTimed,
                            timerId = timerState.getTimerId(),
                            isMultiCookMode = true,
                            multiCookGroupId = multiCookGroupId
                        )
                    }
                }
            }
        }
    }

    var showExitDialog by remember { mutableStateOf(false) }
    var multiCookStartTime by remember { mutableStateOf<Long?>(null) }

    // Track expanded state for each recipe card
    var expandedRecipeIndices by remember { mutableStateOf(setOf<Int>()) }

    // Depend on recipeStates (step changes), timerStates (tick updates), and currentTimeTrigger (minute updates)
    val dynamicFinishTime by remember(recipeStates, currentTimeTrigger) {
        derivedStateOf {
            calculateDynamicFinishTime(recipeStates, timerStates)
        }
    }

    // Find next action next timed step to handle across all recipes
    val nextActionInfo = remember(recipeStates, timerStates, currentTimeTrigger) {
        MultiCookCoordinator.determineNextAction(recipeStates, timerStates, scheduleMap)
    }

    // Create sessions on start or restore from existing sessions
    LaunchedEffect(Unit) {
        if (restoreGroupId != null) {
            // Restore sessions from group ID
            val savedSessions = sessionManager.getSessionsByGroupId(restoreGroupId)
            if (savedSessions.isNotEmpty()) {
                // Retrieve the original start time from the saved session
                val originalStartTime = savedSessions.first().sessionStartTimestamp
                multiCookStartTime = originalStartTime

                // Regenerate the schedule using the original start time as the base
                // This ensures the coordinator knows exactly where we are in the original plan
                scheduleMap = MultiCookCoordinator.generateSchedule(
                    recipes = recipeEntities,
                    baseTime = originalStartTime
                )

                recipeStates = recipeEntities.mapNotNull { entity ->
                    val session = savedSessions.find { it.recipeId == entity.id }
                    session?.let {
                        // Determine if recipe was actually started by checking if it has step data
                        val wasActuallyStarted = it.currentStepInstruction.isNotEmpty() ||
                                it.currentStepIndex > 0 ||
                                it.isTimerActive

                        MultiRecipeCookingState(
                            recipe = entity,
                            currentStepIndex = it.currentStepIndex,
                            status = if (it.currentStepIndex >= entity.cookingSteps.size) {
                                CookingStatus.COMPLETED
                            } else if (wasActuallyStarted) {
                                CookingStatus.IN_PROGRESS
                            } else {
                                CookingStatus.NOT_STARTED
                            },
                            sessionId = it.sessionId,
                            startTime = it.sessionStartTimestamp
                        )
                    }
                }
            }
        } else {
            // Generate Schedule first
            scheduleMap = MultiCookCoordinator.generateSchedule(recipeEntities)
            val scheduleBaseTime = System.currentTimeMillis()
            // Create new sessions
            multiCookStartTime = System.currentTimeMillis()
            recipeStates = recipeStates.mapIndexed { _, state ->
                // Get calculated schedule for this recipe
                val schedule = scheduleMap[state.recipe.id]
                val sessionId = sessionManager.createSession(
                    recipeId = state.recipe.id,
                    recipeName = state.recipe.name,
                    totalSteps = state.recipe.cookingSteps.size,
                    estimatedDurationMinutes = state.recipe.timeMinutes,
                    multiCookGroupId = multiCookGroupId,
                    isMultiCookMode = true
                )
                // Use targetStartTime from schedule, or fallback to now
                state.copy(
                    sessionId = sessionId,
                    startTime = schedule?.targetStartTime ?: scheduleBaseTime
                )
            }
        }
    }

    // Restore timer states from saved sessions
    // Wait for all timer services to be bound before restoring
    val allTimersReady = remember {
        derivedStateOf {
            timerStates.all { it.timerService != null }
        }
    }

    LaunchedEffect(restoreGroupId, recipeStates, allTimersReady.value) {
        if (restoreGroupId != null && recipeStates.isNotEmpty() && allTimersReady.value) {
            val savedSessions = sessionManager.getSessionsByGroupId(restoreGroupId)
            recipeStates.zip(timerStates).forEachIndexed { index, (state, timerState) ->
                val session = savedSessions.find { it.recipeId == state.recipe.id }
                session?.let { savedSession ->
                    // Get the current step for this recipe
                    val currentStep = state.recipe.cookingSteps.getOrNull(savedSession.currentStepIndex)

                    // Restore timer state if there was an active or paused timer
                    if (savedSession.isTimerActive || savedSession.timerPaused) {
                        // Calculate elapsed time since last update
                        val currentTime = System.currentTimeMillis()
                        val elapsedSeconds = ((currentTime - savedSession.lastUpdateTimestamp) / 1000).toInt()

                        // Recalculate remaining time
                        val recalculatedRemainingSeconds = if (savedSession.isTimerActive && !savedSession.timerPaused) {
                            // Timer was running subtract elapsed time
                            kotlin.math.max(0, savedSession.timerRemainingSeconds - elapsedSeconds)
                        } else {
                            // Timer was paused keep the same value
                            savedSession.timerRemainingSeconds
                        }

                        val remainingMinutes = recalculatedRemainingSeconds / 60
                        val remainingSeconds = recalculatedRemainingSeconds % 60

                        // Set the timer to the recalculated remaining time
                        timerState.setTime(remainingMinutes, remainingSeconds)
                        timerState.currentStep = currentStep?.instruction ?: ""

                        // Set the original timer duration for reset functionality
                        // This ensures the reset button restores to the step's original duration, not the remaining time
                        val originalMinutes = savedSession.timerTotalSeconds / 60
                        val originalSeconds = savedSession.timerTotalSeconds % 60
                        timerState.setInitialTime(originalMinutes, originalSeconds)

                        // Restore timer running/paused state
                        if (savedSession.isTimerActive && !savedSession.timerPaused) {
                            // Timer was running start it (unless it expired)
                            if (recalculatedRemainingSeconds > 0) {
                                timerState.start()
                            }
                        } else if (savedSession.timerPaused) {
                            // Timer was paused set it up but don't start
                            timerState.start()
                            timerState.pause()
                        }
                    } else {
                        // Timer was not active set it to the step's duration if it's a timed step
                        if (currentStep != null && currentStep.isTimed && currentStep.durationMinutes > 0) {
                            timerState.setTime(currentStep.durationMinutes, 0)
                            timerState.currentStep = currentStep.instruction
                        }
                    }

                    // Mark session as resumed
                    sessionManager.resumeSession(savedSession.sessionId, incrementRecoveryAttempts = true)
                }
            }
        }
    }

    // Update timer services when recipe states change (step navigation)
    LaunchedEffect(recipeStates) {
        recipeStates.zip(timerStates).forEach { (state, timerState) ->
            if (state.sessionId != -1L && timerState.timerService != null) {
                val currentStep = state.recipe.cookingSteps.getOrNull(state.currentStepIndex)
                currentStep?.let { step ->
                    timerState.timerService?.setSessionInfo(
                        sessionId = state.sessionId,
                        recipeId = state.recipe.id,
                        stepIndex = state.currentStepIndex,
                        totalSteps = state.recipe.cookingSteps.size,
                        stepDuration = step.durationMinutes,
                        stepIsTimed = step.isTimed,
                        timerId = timerState.getTimerId(),
                        isMultiCookMode = true,
                        multiCookGroupId = multiCookGroupId
                    )
                }
            }
        }
    }

    if (showTimelineSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTimelineSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            // Prepare data for the view
            val recipeNames = remember(recipeEntities) {
                recipeEntities.associate { it.id to it.name }
            }
            
            CookingTimelineView(
                schedules = scheduleMap,
                recipeNames = recipeNames
            )
            // Add some spacing at the bottom for navigation bars
            Spacer(modifier = Modifier.height(48.dp))
        }
    }

    BackHandler {
        showExitDialog = true
    }

    // Exit dialog
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("End Multi-Cook Session?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure? Your progress will be lost for all recipes.") },
            confirmButton = {
                Button(
                    onClick = {
                        timerStates.forEach { it.cleanup() }
                        scope.launch {
                            // Mark group as completed instead of deleting
                            sessionManager.completeSessionGroup(multiCookGroupId)
                            // Navigate only after DB update is done
                            onBackClick()
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
                title = {
                    Text(
                        "Multi-Cook Mode",
                        color = Color.White,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { showExitDialog = true }) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showTimelineSheet = true }) {
                        Icon(
                            // Can use DateRange, List, or Info if ViewTimeline isn't available
                            imageVector = Icons.Default.DateRange,
                            contentDescription = "Timeline",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFFF9470)
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
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
                            text = dynamicFinishTime,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
            
            // Dynamic color animation
            val actionColor by animateColorAsState(
                targetValue = when (nextActionInfo.type) {
                    MultiCookCoordinator.ActionType.CRITICAL -> Color(0xFFFF5252)       // Red (Alarm)
                    MultiCookCoordinator.ActionType.START_NEEDED -> Color(0xFFFF9800)   // Orange (Start Now)
                    MultiCookCoordinator.ActionType.STEP_ACTION -> Color(0xFF4CAF50)    // Green (Next Step)
                    MultiCookCoordinator.ActionType.UPCOMING -> Color(0xFFFFD600)       // Yellow (Heads up)
                    MultiCookCoordinator.ActionType.WAITING -> Color(0xFF78909C)        // Blue-Grey (Wait)
                    MultiCookCoordinator.ActionType.COMPLETED -> Color(0xFF4CAF50)      // Green (Done)
                },
                label = "color"
            )

            // Next Action Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = actionColor),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Determine text color based on background
                    val textColor = if (nextActionInfo.type == MultiCookCoordinator.ActionType.UPCOMING)
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f) else Color.White

                    Text(
                        "NEXT ACTION:",
                        color = textColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        // Use message from Coordinator
                        text = nextActionInfo.message,
                        color = textColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f), // Allows text to fill space
                        maxLines = 5,                   // Allow wrapping to a second line
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            // Cooking Sessions
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(recipeStates.size) { index ->
                    val state = recipeStates[index]
                    val timerState = timerStates[index]

                    MultiRecipeCookingSessionCard(
                        state = state,
                        timerState = timerState,
                        schedule = scheduleMap[state.recipe.id],
                        sessionManager = sessionManager,
                        currentTime = currentTimeTrigger,
                        isExpanded = expandedRecipeIndices.contains(index),
                        onExpandToggle = {
                            expandedRecipeIndices = if (expandedRecipeIndices.contains(index)) {
                                expandedRecipeIndices - index
                            } else {
                                expandedRecipeIndices + index
                            }
                        },
                        onStartRecipe = {
                            // Update database session to mark recipe as started
                            val firstStep = state.recipe.cookingSteps.firstOrNull()
                            scope.launch {
                                if (state.sessionId != -1L && firstStep != null) {
                                    sessionManager.updateSession(
                                        sessionId = state.sessionId,
                                        currentStepIndex = 0,
                                        currentStepInstruction = firstStep.instruction,
                                        currentStepDuration = firstStep.durationMinutes,
                                        currentStepIsTimed = firstStep.isTimed,
                                        isTimerActive = false,
                                        timerRemainingSeconds = 0,
                                        timerTotalSeconds = 0,
                                        timerPaused = false
                                    )
                                }
                            }
                            // Update UI state
                            recipeStates = recipeStates.toMutableList().also {
                                it[index] = state.copy(status = CookingStatus.IN_PROGRESS)
                            }
                        },
                        onNextStep = {
                            val nextStepIndex = state.currentStepIndex + 1
                            if (nextStepIndex < state.recipe.cookingSteps.size) {
                                val nextStep = state.recipe.cookingSteps[nextStepIndex]
                                scope.launch {
                                    if (state.sessionId != -1L) {
                                        sessionManager.updateSession(
                                            sessionId = state.sessionId,
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
                                recipeStates = recipeStates.toMutableList().also {
                                    it[index] = state.copy(currentStepIndex = nextStepIndex)
                                }

                                // Update timer for next step
                                if (nextStep.isTimed && nextStep.durationMinutes > 0) {
                                    timerState.setTime(nextStep.durationMinutes, 0)
                                    timerState.currentStep = nextStep.instruction
                                }
                            } else {
                                // Recipe completed
                                scope.launch {
                                    if (state.sessionId != -1L) {
                                        // Update database with completion state
                                        sessionManager.updateSession(
                                            sessionId = state.sessionId,
                                            currentStepIndex = state.recipe.cookingSteps.size, // Set to total steps for completion detection
                                            currentStepInstruction = "", // No more steps
                                            currentStepDuration = 0,
                                            currentStepIsTimed = false,
                                            isTimerActive = false,
                                            timerRemainingSeconds = 0,
                                            timerTotalSeconds = 0,
                                            timerPaused = false
                                        )
                                    }
                                }
                                recipeStates = recipeStates.toMutableList().also {
                                    it[index] = state.copy(status = CookingStatus.COMPLETED)
                                }
                                // Don't delete individual sessions they're part of a group
                                // The group will be deleted when all recipes are done or user exits
                            }
                        },
                        onPreviousStep = {
                            if (state.currentStepIndex > 0) {
                                val prevStepIndex = state.currentStepIndex - 1
                                val prevStep = state.recipe.cookingSteps[prevStepIndex]
                                scope.launch {
                                    if (state.sessionId != -1L) {
                                        sessionManager.updateSession(
                                            sessionId = state.sessionId,
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
                                recipeStates = recipeStates.toMutableList().also {
                                    it[index] = state.copy(currentStepIndex = prevStepIndex)
                                }

                                // Update timer for previous step
                                if (prevStep.isTimed && prevStep.durationMinutes > 0) {
                                    timerState.setTime(prevStep.durationMinutes, 0)
                                    timerState.currentStep = prevStep.instruction
                                }
                            }
                        }
                    )
                }
            }

            // Check if all recipes are completed
            val allRecipesCompleted = remember(recipeStates) {
                recipeStates.isNotEmpty() && recipeStates.all { it.status == CookingStatus.COMPLETED }
            }

            // Return to Home button only visible when all recipes are completed
            AnimatedVisibility(
                visible = allRecipesCompleted,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Button(
                    onClick = {
                        // Stop all timers
                        timerStates.forEach { it.stopTimer() }
                        // Delete all sessions in the group
                        scope.launch {
                            sessionManager.deleteSessionGroup(multiCookGroupId)

                            // Navigate back to home
                            onBackClick()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Home,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "RETURN TO HOME",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun MultiRecipeCookingSessionCard(
    state: MultiRecipeCookingState,
    timerState: CookingTimerState,
    sessionManager: SessionManager,
    currentTime: Long,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    onStartRecipe: () -> Unit,
    onNextStep: () -> Unit,
    onPreviousStep: () -> Unit,
    schedule: MultiCookCoordinator.RecipeSchedule? = null
) {
    val currentStep = state.recipe.cookingSteps.getOrNull(state.currentStepIndex)
    val totalSteps = state.recipe.cookingSteps.size
    val progress = if (totalSteps > 0) (state.currentStepIndex + 1).toFloat() / totalSteps else 0f

    // Use 'currentTime' for calculations
    val timeToPrep = remember(schedule, currentTime) {
        if (schedule != null) (schedule.recommendedPrepStartTime - currentTime) / 60000 else 0
    }

    val timeToCook = remember(schedule, currentTime) {
        if (schedule != null) (schedule.targetStartTime - currentTime) / 60000 else 0
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onExpandToggle() },
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.recipe.name,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (state.status) {
                            CookingStatus.IN_PROGRESS -> MaterialTheme.colorScheme.onSurface
                            CookingStatus.NOT_STARTED -> Color.Gray
                            CookingStatus.COMPLETED -> Color(0xFF4CAF50)
                        }
                    )
                    if (state.status == CookingStatus.NOT_STARTED) {
                        if (schedule != null) {
                            when {
                                // Delayed Start Phase
                                currentTime < schedule.recommendedPrepStartTime && timeToPrep > 0 -> {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.HourglassEmpty,
                                                contentDescription = "Delayed",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "Prep: Start In ${timeToPrep}m",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                // Prep Window: Action Needed
                                currentTime < schedule.targetStartTime -> {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Surface(
                                        color = Color(0xFFFFF3E0), // Light Orange
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Restaurant,
                                                contentDescription = "Prep",
                                                tint = Color(0xFFFF9800),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "Start Prep Now! (Cook in ${timeToCook}m)",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFE65100)
                                            )
                                        }
                                    }
                                }
                                // Late: Ready to Cook
                                else -> {
                                    Text(
                                        "Ready to start!",
                                        fontSize = 14.sp,
                                        color = Color(0xFF4CAF50),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        } else {
                            Text("Not Started", fontSize = 14.sp, color = Color.Gray)
                        }
                    } else {
                        Text(
                            when (state.status) {
                                CookingStatus.NOT_STARTED -> "Not Started"
                                CookingStatus.IN_PROGRESS -> "Step ${state.currentStepIndex + 1} of $totalSteps"
                                CookingStatus.COMPLETED -> "Completed ✓"
                            },
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress Bar
            if (state.status != CookingStatus.NOT_STARTED) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFE0E0E0))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                when (state.status) {
                                    CookingStatus.IN_PROGRESS -> Color(0xFF4A90E2)
                                    CookingStatus.COMPLETED -> Color(0xFF4CAF50)
                                    else -> Color(0xFFE0E0E0)
                                }
                            )
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${(progress * 100).toInt()}%",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                }
            }

            // Expanded content
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    when (state.status) {
                        CookingStatus.NOT_STARTED -> {
                            Button(
                                onClick = onStartRecipe,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF4CAF50)
                                )
                            ) {
                                Text("START COOKING", fontWeight = FontWeight.Bold)
                            }
                        }
                        CookingStatus.IN_PROGRESS -> {
                            currentStep?.let { step ->
                                // Current step card
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (step.isTimed) Color(0xFFFF5252) else Color(0xFF4CAF50)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                if (step.isTimed) "⏰ TIMED STEP" else "✓ PREP STEP",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            Surface(
                                                color = Color.White.copy(alpha = 0.3f),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    step.getDisplayDuration(),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = Color.White,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }

                                        Spacer(Modifier.height(8.dp))

                                        Text(
                                            step.instruction,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White,
                                            lineHeight = 22.sp
                                        )
                                    }
                                }

                                Spacer(Modifier.height(12.dp))

                                // Timer section for timed steps
                                if (step.isTimed && step.durationMinutes > 0) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                "Timer",
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )

                                            Text(
                                                timerState.getFormattedTime(),
                                                fontSize = 40.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (timerState.isFinished()) Color(0xFFFF5252) else Color(0xFF2196F3),
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            )

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                if (!timerState.soundStopped) {
                                                    Button(
                                                        onClick = {
                                                            when {
                                                                timerState.isFinished() -> timerState.stopSound()
                                                                !timerState.isRunning -> timerState.start()
                                                                timerState.isPaused -> timerState.resume()
                                                                else -> timerState.pause()
                                                            }
                                                        },
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .height(44.dp),
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
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }

                                                OutlinedButton(
                                                    onClick = { timerState.reset() },
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(44.dp)
                                                ) {
                                                    Text("RESET", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(12.dp))
                                }

                                // Navigation buttons
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = onPreviousStep,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp),
                                        enabled = state.currentStepIndex > 0
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowBack,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("PREV", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = onNextStep,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (state.currentStepIndex >= totalSteps - 1)
                                                Color(0xFFFF5252) else Color(0xFF4CAF50)
                                        )
                                    ) {
                                        Text(
                                            if (state.currentStepIndex >= totalSteps - 1) "FINISH" else "NEXT",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            if (state.currentStepIndex >= totalSteps - 1)
                                                Icons.Default.Check else Icons.Default.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                        CookingStatus.COMPLETED -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Recipe Completed!",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                        }
                    }
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

@Composable
fun CookingTimelineView(
    schedules: Map<Long, com.example.smartcook.MultiCookCoordinator.RecipeSchedule>,
    recipeNames: Map<Long, String>
) {
    if (schedules.isEmpty()) return

    // Calculate the total time range for the chart
    val startTime = schedules.values.minOf { it.recommendedPrepStartTime }
    val finishTime = schedules.values.maxOf { it.targetFinishTime }
    val totalDuration = (finishTime - startTime).toFloat()

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            "Cooking Plan",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        schedules.forEach { (recipeId, schedule) ->
            val name = recipeNames[recipeId] ?: "Recipe"

            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                // Recipe Name
                Text(name, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))

                // The Timeline Bar Container
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                ) {
                    // Calculate positions (0.0 to 1.0)
                    // Safeguard against division by zero if duration is 0
                    val safeDuration = if (totalDuration == 0f) 1f else totalDuration

                    val prepStartFraction = (schedule.recommendedPrepStartTime - startTime) / safeDuration
                    val cookStartFraction = (schedule.targetStartTime - startTime) / safeDuration
                    val cookEndFraction = (schedule.targetFinishTime - startTime) / safeDuration

                    // Prep Time Bar (Yellow/Orange)
                    if (schedule.recommendedPrepStartTime < schedule.targetStartTime) {
                        // Use a Row with Weights to simulate the positioning roughly for simplicity
                        // in a real Gantt chart, Canvas is more precise, but this works for simple bars
                        Row(modifier = Modifier.fillMaxSize()) {
                            // Empty space before prep
                            if (prepStartFraction > 0) {
                                Spacer(modifier = Modifier.weight(prepStartFraction))
                            }
                            // Prep Bar
                            Box(
                                modifier = Modifier
                                    .weight((cookStartFraction - prepStartFraction).coerceAtLeast(0.01f))
                                    .fillMaxHeight()
                                    .background(Color(0xFFFFB74D)) // Light Orange
                            )
                            // Space after prep
                            if (1f - cookStartFraction > 0) {
                                Spacer(modifier = Modifier.weight(1f - cookStartFraction))
                            }
                        }
                    }

                    // Cook Time Bar (Red/Dark Orange)
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Empty space before cooking
                        if (cookStartFraction > 0) {
                            Spacer(modifier = Modifier.weight(cookStartFraction))
                        }
                        // Cook Bar
                        Box(
                            modifier = Modifier
                                .weight((cookEndFraction - cookStartFraction).coerceAtLeast(0.01f))
                                .fillMaxHeight()
                                .background(Color(0xFFFF5252)) // Red
                        )
                        // Space after cooking
                        if (1f - cookEndFraction > 0) {
                            Spacer(modifier = Modifier.weight(1f - cookEndFraction))
                        }
                    }
                }

                // Time Labels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatTime(schedule.recommendedPrepStartTime),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatTime(schedule.targetFinishTime),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Legend
        Row(modifier = Modifier.padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(12.dp).background(Color(0xFFFFB74D), RoundedCornerShape(2.dp)))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Prep", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(12.dp).background(Color(0xFFFF5252), RoundedCornerShape(2.dp)))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Cook", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun calculateDynamicFinishTime(
    recipeStates: List<MultiRecipeCookingState>,
    timerStates: List<CookingTimerState>
): String {
    if (recipeStates.isEmpty()) return "--:--"

    val now = System.currentTimeMillis()
    var maxRemainingMinutes = 0

    recipeStates.forEachIndexed { index, state ->
        // If completed, remaining time is 0
        if (state.status == CookingStatus.COMPLETED) return@forEachIndexed

        // If not started, remaining time is total recipe time
        if (state.status == CookingStatus.NOT_STARTED) {
            if (state.recipe.timeMinutes > maxRemainingMinutes) {
                maxRemainingMinutes = state.recipe.timeMinutes
            }
            return@forEachIndexed
        }

        // If In Progress: Calculate Future Steps + Current Step

        // Sum duration of all SUBSEQUENT steps
        val futureSteps = state.recipe.cookingSteps.drop(state.currentStepIndex + 1)
        val futureDuration = futureSteps.sumOf { it.durationMinutes }

        // Calculate duration of CURRENT step
        val currentStepDuration = if (index < timerStates.size) {
            val timer = timerStates[index]
            if (timer.isRunning || timer.isPaused || timer.timeInSeconds > 0) {
                // If timer is active, use actual remaining seconds (converted to mins, rounded up)
                (timer.timeInSeconds / 60.0).let { if (it > 0 && it < 1) 1 else kotlin.math.ceil(it).toInt() }
            } else {
                // If timer not active (e.g., untimed prep step), use static duration
                state.recipe.cookingSteps.getOrNull(state.currentStepIndex)?.durationMinutes ?: 0
            }
        } else {
            0
        }

        val totalRemaining = futureDuration + currentStepDuration

        if (totalRemaining > maxRemainingMinutes) {
            maxRemainingMinutes = totalRemaining.toInt()
        }
    }

    // Add max remaining minutes to current time
    val finishTimeMillis = now + (maxRemainingMinutes * 60 * 1000)

    // Format the time
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = finishTimeMillis
    val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = calendar.get(java.util.Calendar.MINUTE)

    return String.format("%02d:%02d", hour, minute)
}