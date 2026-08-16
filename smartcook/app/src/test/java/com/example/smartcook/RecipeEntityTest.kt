package com.example.smartcook

import com.example.smartcook.data.RecipeEntity
import com.example.smartcook.data.getTimingSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeEntityTest {

    @Test
    fun `getTimingSummary generates correct string for mixed steps`() {
        // 1. Create a dummy recipe with specific steps
        // Step 1: "Chop onions" | 10 mins | false (This is Prep)
        // Step 2: "Boil water"  | 20 mins | true  (This is Active Cooking)
        val recipe = RecipeEntity(
            name = "Test Stew",
            difficulty = "Medium",
            servings = 4,
            timeMinutes = 30, // Total time
            imageName = "stew",
            // The format is: instruction|duration|isTimed
            steps = "Chop onions|10|false||Boil water|20|true"
        )

        // 2. Call the extension function to get the formatted summary
        val summary = recipe.getTimingSummary()

        // 3. Verify the string matches the expected format
        // It should separate Prep time from Cook time
        assertEquals("Prep: 10m Cook: 20m Total: 30m", summary)
    }
}