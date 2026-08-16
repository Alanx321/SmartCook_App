package com.example.smartcook

import com.example.smartcook.data.RecipeEntity
import com.example.smartcook.data.cookingSteps

// Enhanced Multi-Cook Coordinator with improved step handling
object MultiCookCoordinator {

    data class RecipeSchedule(
        val recipeId: Long,
        val totalDurationMinutes: Int,
        val criticalPathMinutes: Int, // Only timed steps
        val prepTimeMinutes: Int,      // Non-timed steps
        val delayMinutes: Int,
        val targetStartTime: Long,
        val targetFinishTime: Long,
        val recommendedPrepStartTime: Long // When to start prep work
    )

    // Enhanced schedule generation considering timed vs non-timed steps
    fun generateSchedule(
        recipes: List<RecipeEntity>, 
        baseTime: Long = System.currentTimeMillis(),
        prepBufferMinutes: Int = 5 // Buffer between prep and cooking
    ): Map<Long, RecipeSchedule> {
        if (recipes.isEmpty()) return emptyMap()

        // Analyze each recipe's timing structure
        val recipeAnalysis = recipes.map { recipe ->
            val timedSteps = recipe.cookingSteps.filter { it.isTimed && it.durationMinutes > 0 }
            val nonTimedSteps = recipe.cookingSteps.filter { !it.isTimed }
            
            RecipeAnalysis(
                recipe = recipe,
                criticalPathMinutes = timedSteps.sumOf { it.durationMinutes },
                prepTimeMinutes = nonTimedSteps.sumOf { it.durationMinutes },
                totalSteps = recipe.cookingSteps.size
            )
        }

        // Looks at Prep + Cooking time
        // Find the longest TOTAL duration (Prep + Cook) among all recipes
        val maxTotalDuration = recipeAnalysis.maxOf { it.prepTimeMinutes + it.criticalPathMinutes }

        // Set the finish time based on that total duration
        val targetFinishTime = baseTime + (maxTotalDuration * 60 * 1000)

        // Calculate schedules with prep time consideration
        return recipeAnalysis.associate { analysis ->
            val recipe = analysis.recipe

            // Total duration for THIS recipe
            val recipeTotalDuration = analysis.criticalPathMinutes + analysis.prepTimeMinutes
            
            // Calculate delay based on Total duration difference
            // If this recipe is shorter than the longest one, it starts later.
            val delayMinutes = maxTotalDuration - recipeTotalDuration
            
            // Calculate Start Times
            // Prep starts after the delay
            val prepStartTime = baseTime + (delayMinutes * 60 * 1000)
            
            // Cooking starts after prep is done (plus buffer)
            val cookingStartTime = prepStartTime + ((analysis.prepTimeMinutes + prepBufferMinutes) * 60 * 1000)
            
            recipe.id to RecipeSchedule(
                recipeId = recipe.id,
                totalDurationMinutes = recipeTotalDuration,
                criticalPathMinutes = analysis.criticalPathMinutes,
                prepTimeMinutes = analysis.prepTimeMinutes,
                delayMinutes = delayMinutes,
                targetStartTime = cookingStartTime,
                targetFinishTime = targetFinishTime,
                recommendedPrepStartTime = prepStartTime
            )
        }
    }

    // Enhanced action determination with prep vs cooking distinction
    fun determineNextAction(
        recipeStates: List<MultiRecipeCookingState>,
        timerStates: List<CookingTimerState>,
        schedules: Map<Long, RecipeSchedule>
    ): NextActionInfo {
        val now = System.currentTimeMillis()

        // PRIORITY 1: Active Timers that finished
        val finishedTimer = timerStates.zip(recipeStates)
            .firstOrNull { (timer, _) -> timer.isFinished() && !timer.soundStopped }
        if (finishedTimer != null) {
            return NextActionInfo(
                message = "⚠️ Timer Finished: ${finishedTimer.second.recipe.name}",
                type = ActionType.CRITICAL,
                recipeId = finishedTimer.second.recipe.id
            )
        }

        // PRIORITY 2: Recipes that MUST start cooking NOW
        val needsCookingStart = recipeStates
            .filter { it.status == com.example.smartcook.data.CookingStatus.NOT_STARTED }
            .firstOrNull { state ->
                val schedule = schedules[state.recipe.id]
                val targetTime = schedule?.targetStartTime ?: Long.MAX_VALUE
                
                // Check if we are past the target time (or within 30 seconds of it)
                val isAtCookingTime = targetTime <= (now + 30_000) 

                val hasTimedSteps = state.recipe.cookingSteps.any { it.isTimed }
                isAtCookingTime && hasTimedSteps
            }
        
        if (needsCookingStart != null) {
            return NextActionInfo(
                message = "🔥 START COOKING: ${needsCookingStart.recipe.name}",
                type = ActionType.START_NEEDED,
                recipeId = needsCookingStart.recipe.id
            )
        }

        // PRIORITY 3: Recipes ready for prep work (earlier, flexible timing)
        val activeStep = recipeStates.zip(timerStates)
            .firstOrNull { (state, timer) ->
                state.status == com.example.smartcook.data.CookingStatus.IN_PROGRESS && 
                !timer.isRunning && 
                !timer.isFinished()
            }
        
        if (activeStep != null) {
            val step = activeStep.first.recipe.cookingSteps
                .getOrNull(activeStep.first.currentStepIndex)
            val instruction = step?.instruction ?: "Next Step"
            return NextActionInfo(
                message = "👉 ${activeStep.first.recipe.name}: $instruction",
                type = ActionType.STEP_ACTION,
                recipeId = activeStep.first.recipe.id
            )
        }

        // PRIORITY 4: PARALLEL PREP WORK Multitasking
        // Even if other timers are running, check if we can start prepping another dish.
        // Coordinating gaps in cooking.
        val readyForPrep = recipeStates
            .filter { it.status == com.example.smartcook.data.CookingStatus.NOT_STARTED }
            .firstOrNull { state ->
                val schedule = schedules[state.recipe.id]
                val prepTime = schedule?.recommendedPrepStartTime ?: Long.MAX_VALUE
                
                // Compare the time clearly
                val isPrepTime = prepTime <= now
                
                val firstStep = state.recipe.cookingSteps.firstOrNull()
                val needsPrep = firstStep != null
                isPrepTime && needsPrep
            }

        if (readyForPrep != null) {
            val firstInstruction = readyForPrep.recipe.cookingSteps.firstOrNull()?.instruction 
                ?: "Start Recipe"
            // Adjust message: If it's a timed step, say "Start", otherwise "Prep"
            val firstStep = readyForPrep.recipe.cookingSteps.firstOrNull()
            val actionWord = if (firstStep?.isTimed == true) "Start" else "Prep"

            return NextActionInfo(
                message = "🔪 $actionWord ${readyForPrep.recipe.name}: $firstInstruction",
                type = ActionType.STEP_ACTION, // Use Green/Action color
                recipeId = readyForPrep.recipe.id
            )
        }

        // PRIORITY 5: LOOKAHEAD WARNING
        // Warn user if a critical Cooking start is coming soon
        val upcomingStart = recipeStates
            .filter { it.status == com.example.smartcook.data.CookingStatus.NOT_STARTED }
            .firstOrNull { state ->
                val schedule = schedules[state.recipe.id]
                val targetTime = schedule?.targetStartTime ?: Long.MAX_VALUE
                val diff = targetTime - now
                // Warn if between 30 sec and 2 minutes away
                diff in 30_000..120_000
            }
            
        if (upcomingStart != null) {
            val diffSeconds = (schedules[upcomingStart.recipe.id]!!.targetStartTime - now) / 1000
            return NextActionInfo(
                message = "⚠️ Heads up: ${upcomingStart.recipe.name} starts in ${diffSeconds/60}m",
                type = ActionType.UPCOMING,
                recipeId = upcomingStart.recipe.id
            )
        }

        // PRIORITY 6: WAITING
        if (timerStates.any { it.isRunning }) {
            // Find the timer that finishes soonest to give context
            val shortestTimer = timerStates
                .filter { it.isRunning }
                .minByOrNull { it.timeInSeconds }
            
            val remainingStr = shortestTimer?.getFormattedTime() ?: ""
            val recipeName = shortestTimer?.recipeName ?: "dish"

            return NextActionInfo(
                message = "⏳ Cooking... ($recipeName done in $remainingStr)",
                type = ActionType.WAITING,
                recipeId = null
            )
        }

        // PRIORITY 7: WAITING FOR START
        val nextUp = recipeStates
            .filter { it.status == com.example.smartcook.data.CookingStatus.NOT_STARTED }
            .minByOrNull { schedules[it.recipe.id]?.recommendedPrepStartTime ?: Long.MAX_VALUE }

        if (nextUp != null) {
            val schedule = schedules[nextUp.recipe.id]
            val prepTime = schedule?.recommendedPrepStartTime ?: Long.MAX_VALUE
            val diffMinutes = ((prepTime - now) / 1000 / 60).coerceAtLeast(0)
            
            return NextActionInfo(
                message = "💤 Relax. Next prep in $diffMinutes min (${nextUp.recipe.name})",
                type = ActionType.WAITING,
                recipeId = nextUp.recipe.id
            )
        }

        return NextActionInfo(
            message = "✅ All Done!",
            type = ActionType.COMPLETED,
            recipeId = null
        )
    }

    data class NextActionInfo(
        val message: String, 
        val type: ActionType,
        val recipeId: Long? = null
    )

    enum class ActionType {
        CRITICAL,       // Alarm ringing
        START_NEEDED,   // Recipe MUST start cooking NOW (timed steps)
        STEP_ACTION,    // User needs to do something
        UPCOMING,
        WAITING,        // Everything running or waiting
        COMPLETED       // All done
    }

    private data class RecipeAnalysis(
        val recipe: RecipeEntity,
        val criticalPathMinutes: Int,  // Only timed steps
        val prepTimeMinutes: Int,       // Estimated prep time
        val totalSteps: Int
    )
}

data class MultiRecipeCookingState(
    val recipe: RecipeEntity,
    val currentStepIndex: Int = 0,
    val status: com.example.smartcook.data.CookingStatus = com.example.smartcook.data.CookingStatus.NOT_STARTED,
    val sessionId: Long = -1,
    val startTime: Long = 0L
)