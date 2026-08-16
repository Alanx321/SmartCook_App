package com.example.smartcook

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.smartcook.data.RecipeDao
import com.example.smartcook.data.RecipeEntity
import com.example.smartcook.data.RecipeRepository
import com.example.smartcook.viewmodel.RecipeViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddRecipeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun addRecipeButton_Disabled_WhenInputsEmpty() {
        // 1. Use a Manual Fake instead of MockK.
        val fakeDao = FakeRecipeDao()
        val repository = RecipeRepository(fakeDao)
        val viewModel = RecipeViewModel(repository)

        // 2. Load the Screen
        composeTestRule.setContent {
            EnhancedAddRecipeScreen(
                viewModel = viewModel,
                onNavigateBack = {}
            )
        }

        // 3. Find the button with text "SAVE RECIPE" and check it is disabled
        composeTestRule.onNodeWithText("SAVE RECIPE").assertIsNotEnabled()
    }
}

// By creating this simple class, we bypass the Android Security restrictions.
// This is often considered a "Best Practice" for stable UI tests.
class FakeRecipeDao : RecipeDao {
    // Return empty lists for the ViewModel to load successfully
    override fun getAllRecipes(): Flow<List<RecipeEntity>> = flowOf(emptyList())
    override fun getFavoriteRecipes(): Flow<List<RecipeEntity>> = flowOf(emptyList())

    // These methods are required by the interface but not used in this specific test.
    // Leave them empty or returning default values.
    override suspend fun getRecipeById(id: Long): RecipeEntity? = null
    override suspend fun insertRecipe(recipe: RecipeEntity): Long = 0L
    override suspend fun insertRecipes(recipes: List<RecipeEntity>) {}
    override suspend fun updateRecipe(recipe: RecipeEntity) {}
    override suspend fun updateFavoriteStatus(id: Long, isFavorite: Boolean) {}
    override suspend fun deleteRecipe(recipe: RecipeEntity) {}
    override suspend fun deleteAllRecipes() {}
    override suspend fun getRecipeCount(): Int = 0
    override fun searchRecipesByName(searchQuery: String): Flow<List<RecipeEntity>> = flowOf(emptyList())
    override fun searchFavoritesByName(searchQuery: String): Flow<List<RecipeEntity>> = flowOf(emptyList())
}