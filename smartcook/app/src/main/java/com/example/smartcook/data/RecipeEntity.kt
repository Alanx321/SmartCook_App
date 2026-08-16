package com.example.smartcook.data

import android.content.Context
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.smartcook.R

// RecipeEntity with timing info
@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val difficulty: String,
    val servings: Int,
    val timeMinutes: Int, // Total estimated time
    val imageName: String,
    val isFavorite: Boolean = false,
    val ingredients: String = "", // Pipe-separated
    val steps: String = "" // "instruction|duration|is_timed||instruction|duration|is_timed"
)

// Represent a single cooking step with timing info
data class CookingStep(
    val stepNumber: Int,
    val instruction: String,
    val durationMinutes: Int, // 0 for instant steps
    val isTimed: Boolean, // true if requires active timer
    val isCompleted: Boolean = false
) {
    val durationSeconds: Int
        get() = durationMinutes * 60

    fun getDisplayDuration(): String {
        return when {
            durationMinutes == 0 -> "Quick step"
            durationMinutes == 1 -> "1 minute"
            else -> "$durationMinutes minutes"
        }
    }
}

// Parses encoded steps into CookingStep list
fun RecipeEntity.parseSteps(): List<CookingStep> {
    if (steps.isBlank()) return emptyList()

    return steps.split("||").mapIndexed { index, stepData ->
        val parts = stepData.split("|")
        CookingStep(
            stepNumber = index + 1,
            instruction = parts.getOrNull(0) ?: "",
            durationMinutes = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            isTimed = parts.getOrNull(2)?.toBoolean() ?: false
        )
    }.filter { it.instruction.isNotBlank() }
}

// Parsed cooking steps convenience property
val RecipeEntity.cookingSteps: List<CookingStep>
    get() = parseSteps()

// Ingredients as a list
val RecipeEntity.ingredientsList: List<String>
    get() = ingredients.split("|").filter { it.isNotBlank() }

// Get steps as plain string list
fun RecipeEntity.getStepsAsStrings(): List<String> {
    return cookingSteps.map { it.instruction }
}

// Get image resource from image name
fun RecipeEntity.getImageRes(context: Context): Int {
    return context.resources.getIdentifier(
        imageName,
        "drawable",
        context.packageName
    ).takeIf { it != 0 } ?: R.drawable.ic_launcher_foreground
}

// Calculate estimated finish time
fun RecipeEntity.getEstimatedFinishTime(): String {
    val totalMinutes = cookingSteps.sumOf { it.durationMinutes }
    val finishTimeMs = System.currentTimeMillis() + (totalMinutes * 60 * 1000)

    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = finishTimeMs

    val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = calendar.get(java.util.Calendar.MINUTE)

    return String.format("%02d:%02d", hour, minute)
}

// Total timed (Critical) cooking time
fun RecipeEntity.getCriticalTimeMinutes(): Int {
    return cookingSteps.filter { it.isTimed }.sumOf { it.durationMinutes }
}

// Get total prep time
fun RecipeEntity.getPrepTimeMinutes(): Int {
    return cookingSteps.filter { !it.isTimed }.sumOf { it.durationMinutes }
}

// Get a summary of the recipe timing
fun RecipeEntity.getTimingSummary(): String {
    val prepTime = getPrepTimeMinutes()
    val cookTime = getCriticalTimeMinutes()

    return buildString {
        if (prepTime > 0) append("Prep: ${prepTime}m ")
        if (cookTime > 0) append("Cook: ${cookTime}m ")
        append("Total: ${timeMinutes}m")
    }
}

// Calculate total estimated cooking time
fun List<CookingStep>.getTotalEstimatedMinutes(): Int {
    return this.sumOf { it.durationMinutes }
}

// Calculate critical (timed) cooking time only
fun List<CookingStep>.getCriticalTimeMinutes(): Int {
    return this.filter { it.isTimed }.sumOf { it.durationMinutes }
}

// Get estimated finish time from now
fun List<CookingStep>.getEstimatedFinishTime(): String {
    val totalMinutes = getTotalEstimatedMinutes()
    val now = System.currentTimeMillis()
    val finishTime = now + (totalMinutes * 60 * 1000)

    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = finishTime

    val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = calendar.get(java.util.Calendar.MINUTE)

    return String.format("%02d:%02d", hour, minute)
}