package com.example.smartcook.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartcook.MultiCookCoordinator
import com.example.smartcook.data.Recipe
import com.example.smartcook.viewmodel.RecipeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectRecipesScreen(
    viewModel: RecipeViewModel,
    onBackClick: () -> Unit = {},
    onStartMultiCook: (List<Recipe>) -> Unit = {}
) {
    // Observe all recipes from the database
    val recipeEntities by viewModel.allRecipes.observeAsState(emptyList())

    // Convert RecipeEntity to Recipe format and maintain selection state
    var recipes by remember { mutableStateOf<List<Recipe>>(emptyList()) }

    // Update recipes when database recipes change, preserving selection state
    LaunchedEffect(recipeEntities) {
        recipes = recipeEntities.map { entity ->
            val existingRecipe = recipes.find { it.id == entity.id.toInt() }
            Recipe(
                id = entity.id.toInt(),
                name = entity.name,
                emoji = "",
                duration = entity.timeMinutes,
                isSelected = existingRecipe?.isSelected ?: false
            )
        }
    }

    val selectedRecipes = recipes.filter { it.isSelected }

    // Get the actual entities for the selected recipes to use with the Coordinator
    val selectedRecipeEntities = remember(selectedRecipes, recipeEntities) {
        val selectedIds = selectedRecipes.map { it.id }
        recipeEntities.filter { it.id.toInt() in selectedIds }
    }

    // Calculate estimated finish time using the MultiCookCoordinator logic
    // Ensures the time shown here matches the Multi-Cook Mode
    val estimatedFinishTime = remember(selectedRecipeEntities) {
        if (selectedRecipeEntities.isEmpty()) {
            "--:--"
        } else {
            // Ask the Coordinator to generate the schedule (optimizing prep times)
            val schedules = MultiCookCoordinator.generateSchedule(selectedRecipeEntities)
            
            // Find the latest finish time among all scheduled recipes
            val finishTimeMillis = schedules.values.maxOfOrNull { it.targetFinishTime } 
                ?: System.currentTimeMillis()

            val calendar = java.util.Calendar.getInstance()
            calendar.timeInMillis = finishTimeMillis

            val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
            val minute = calendar.get(java.util.Calendar.MINUTE)

            String.format("%02d:%02d", hour, minute)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Select Recipes",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Selected: ${selectedRecipes.size}/3",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "(minimum 2 recipes)",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    recipes.forEach { recipe ->
                        RecipeSelectItem(
                            recipe = recipe,
                            onToggle = {
                                recipes = recipes.map {
                                    if (it.id == recipe.id) {
                                        it.copy(isSelected = !it.isSelected)
                                    } else it
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "🕐",
                            fontSize = 24.sp,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Column {
                            Text(
                                "Estimated Finish Time",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                estimatedFinishTime,
                                fontSize = 24.sp,
                                color = Color(0xFF4A90E2), 
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { onStartMultiCook(selectedRecipes) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                ),
                shape = RoundedCornerShape(12.dp),
                enabled = selectedRecipes.size >= 2
            ) {
                Text(
                    "START MULTI-COOK",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun RecipeSelectItem(
    recipe: Recipe,
    onToggle: () -> Unit
) {
    val borderColor = if (recipe.isSelected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
    
    val backgroundColor = if (recipe.isSelected) 
        MaterialTheme.colorScheme.surface 
    else 
        MaterialTheme.colorScheme.background

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable { onToggle() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = recipe.isSelected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFF4A90E2),
                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )

        Text(
            recipe.name,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        )

        Text(
            "⏱ ~${recipe.duration} min",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}