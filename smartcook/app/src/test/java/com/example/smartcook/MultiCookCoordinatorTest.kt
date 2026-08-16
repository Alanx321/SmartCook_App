package com.example.smartcook

import com.example.smartcook.data.RecipeEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiCookCoordinatorTest {

    @Test
    fun `generateSchedule calculates correct delay for shorter recipe`() {
        // 1. Create two dummy recipes
        // Recipe A takes 30 mins total (all timed/critical)
        val longRecipe = RecipeEntity(
                id = 1, name = "Roast Chicken", difficulty = "Hard", servings = 4, timeMinutes = 30,
                imageName = "", steps = "Step 1|30|true"
        )

        // Recipe B takes 10 mins total (all timed/critical)
        val shortRecipe = RecipeEntity(
                id = 2, name = "Salad", difficulty = "Easy", servings = 2, timeMinutes = 10,
                imageName = "", steps = "Step 1|10|true"
        )

        val recipes = listOf(longRecipe, shortRecipe)

        // 2. Run the scheduler
        val scheduleMap = MultiCookCoordinator.generateSchedule(recipes)

        // 3. Check the results
        // The Long Recipe (30m) should have 0 delay
        assertEquals(0, scheduleMap[1L]?.delayMinutes)

        // The Short Recipe (10m) should have 20 mins delay (30 - 10 = 20)
        // so that they finish at the exact same time
        assertEquals(20, scheduleMap[2L]?.delayMinutes)
    }
}