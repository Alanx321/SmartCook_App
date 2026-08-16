package com.example.smartcook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.platform.LocalContext
import com.example.smartcook.data.RecipeEntity
import com.example.smartcook.data.getImageRes
import com.example.smartcook.viewmodel.RecipeViewModel

@Composable
fun RecipeDetailPage(
    recipeId: Long,
    viewModel: RecipeViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToCooking: (RecipeEntity) -> Unit = {}
) {
    val context = LocalContext.current
    val allRecipes by viewModel.allRecipes.observeAsState(emptyList())

    // Find the recipe by ID
    val recipe = allRecipes.find { it.id == recipeId }

    recipe?.let { entity ->
        RecipeDetailScreen(
            recipe = entity,  // Pass RecipeEntity directly
            imageRes = entity.getImageRes(context),  // Get image via extension
            onNavigateBack = onNavigateBack,
            onFavoriteToggle = {
                viewModel.toggleFavorite(entity.id, !entity.isFavorite)
            },
            onDeleteRecipe = {
                viewModel.deleteRecipe(entity)
                onNavigateBack()
            },
            onStartCooking = {
                onNavigateToCooking(entity)  // Pass entity directly
            }
        )
    }
}