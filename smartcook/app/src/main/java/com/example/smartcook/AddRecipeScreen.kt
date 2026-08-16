package com.example.smartcook

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartcook.data.RecipeEntity
import com.example.smartcook.viewmodel.RecipeViewModel

data class StepInput(
    val instruction: String = "",
    val durationMinutes: String = "0",
    val requiresTimer: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedAddRecipeScreen(
    viewModel: RecipeViewModel,
    onNavigateBack: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var difficulty by remember { mutableStateOf("Easy") }
    var servings by remember { mutableStateOf("") }
    var imageName by remember { mutableStateOf("") }
    var showSuccessDialog by remember { mutableStateOf(false) }

    var ingredients by remember { mutableStateOf(listOf("")) }
    var steps by remember { mutableStateOf(listOf(StepInput())) }

    val difficulties = listOf("Easy", "Medium", "Hard")
    var expanded by remember { mutableStateOf(false) }

    // Calculate total time
    val totalTime = remember(steps) {
        steps.sumOf { it.durationMinutes.toIntOrNull() ?: 0 }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Add New Recipe",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFFF7961))
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Recipe Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = difficulty,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Difficulty") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    difficulties.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                difficulty = option
                                expanded = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = servings,
                onValueChange = { servings = it.filter { char -> char.isDigit() } },
                label = { Text("Servings") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Show calculated total time
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Total Estimated Time:",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "$totalTime minutes",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            OutlinedTextField(
                value = imageName,
                onValueChange = { imageName = it },
                label = { Text("Image Name (optional)") },
                placeholder = { Text("e.g., pasta_carbonara") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Ingredients Section
            Text(
                text = "Ingredients",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            ingredients.forEachIndexed { index, ingredient ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = ingredient,
                        onValueChange = { newValue ->
                            ingredients = ingredients.toMutableList().apply {
                                this[index] = newValue
                            }
                        },
                        label = { Text("Ingredient ${index + 1}") },
                        placeholder = { Text("e.g., 400g spaghetti") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedTextColor = MaterialTheme.colorScheme.onBackground,
                            unfocusedTextColor = MaterialTheme.colorScheme.onBackground
                        )
                    )

                    if (ingredients.size > 1) {
                        IconButton(
                            onClick = {
                                ingredients = ingredients.toMutableList().apply {
                                    removeAt(index)
                                }
                            }
                        ) {
                            Icon(Icons.Default.Delete, "Remove", tint = Color(0xFFFF5252))
                        }
                    }
                }
            }

            Button(
                onClick = { ingredients = ingredients + "" },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Add Ingredient")
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Steps Section with timing
            Text(
                text = "Cooking Steps",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Add duration and check if step needs active timer monitoring",
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            steps.forEachIndexed { index, step ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (step.requiresTimer)
                            MaterialTheme.colorScheme.errorContainer
                        else 
                            MaterialTheme.colorScheme.surface
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Step ${index + 1}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (step.requiresTimer) 
                                    MaterialTheme.colorScheme.onErrorContainer 
                                else 
                                    MaterialTheme.colorScheme.onSurface
                            )
                            if (steps.size > 1) {
                                IconButton(
                                    onClick = {
                                        steps = steps.toMutableList().apply { removeAt(index) }
                                    }
                                ) {
                                    Icon(Icons.Default.Delete, "Remove", tint = Color(0xFFFF5252))
                                }
                            }
                        }

                        OutlinedTextField(
                            value = step.instruction,
                            onValueChange = { newValue ->
                                steps = steps.toMutableList().apply {
                                    this[index] = this[index].copy(instruction = newValue)
                                }
                            },
                            label = { Text("Instruction") },
                            placeholder = { Text("Describe this step...") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = step.durationMinutes,
                                onValueChange = { newValue ->
                                    steps = steps.toMutableList().apply {
                                        this[index] = this[index].copy(
                                            durationMinutes = newValue.filter { it.isDigit() }
                                        )
                                    }
                                },
                                label = { Text("Duration (min)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text("Needs Timer?", fontSize = 12.sp, color = Color.Gray)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = step.requiresTimer,
                                        onCheckedChange = { checked ->
                                            steps = steps.toMutableList().apply {
                                                this[index] = this[index].copy(requiresTimer = checked)
                                            }
                                        }
                                    )
                                    Text(
                                        if (step.requiresTimer) "Yes" else "No",
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { steps = steps + StepInput() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Add Step")
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    if (name.isNotBlank() && servings.isNotBlank()) {
                        val ingredientsString = ingredients
                            .filter { it.isNotBlank() }
                            .joinToString("|")

                        val stepsString = steps
                            .filter { it.instruction.isNotBlank() }
                            .joinToString("||") { step ->
                                "${step.instruction}|${step.durationMinutes}|${step.requiresTimer}"
                            }

                        val recipe = RecipeEntity(
                            name = name,
                            difficulty = difficulty,
                            servings = servings.toIntOrNull() ?: 1,
                            timeMinutes = totalTime,
                            imageName = imageName.ifBlank { "ic_launcher_foreground" },
                            isFavorite = false,
                            ingredients = ingredientsString,
                            steps = stepsString
                        )
                        viewModel.insertRecipe(recipe)
                        showSuccessDialog = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                enabled = name.isNotBlank() && servings.isNotBlank()
            ) {
                Text("SAVE RECIPE", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Success!") },
            text = { Text("Recipe has been added successfully with $totalTime minutes total cooking time.") },
            confirmButton = {
                Button(
                    onClick = {
                        showSuccessDialog = false
                        onNavigateBack()
                    }
                ) {
                    Text("OK")
                }
            }
        )
    }
}