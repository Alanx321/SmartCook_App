package com.example.smartcook

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.smartcook.ui.theme.SmartcookTheme
import com.example.smartcook.viewmodel.RecipeViewModel
import com.example.smartcook.viewmodel.RecipeViewModelFactory
import com.example.smartcook.viewmodel.SettingsViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val isDarkMode by settingsViewModel.isDarkMode.collectAsState()

            SmartcookTheme(darkTheme = isDarkMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()

                    val recipeViewModel: RecipeViewModel = viewModel(
                        factory = RecipeViewModelFactory(
                            (application as SmartCookApplication).repository
                        )
                    )

                    // Request notification permission on app startup
                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        // Permission granted or denied, proceed with app
                    }

                    // Request permission on first composition
                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    // State for handling the Intent (Notification click)
                    var pendingSessionId by remember { mutableLongStateOf(intent.getLongExtra("resume_session_id", -1L)) }
                    var pendingRecipeId by remember { mutableLongStateOf(intent.getLongExtra("recipe_id", -1L)) }
                    var pendingIsMultiCook by remember { mutableStateOf(intent.getBooleanExtra("is_multi_cook_mode", false)) }
                    var pendingMultiCookGroupId by remember { mutableStateOf(intent.getStringExtra("multi_cook_group_id")) }

                    // Effect to handle the initial navigation from notification
                    LaunchedEffect(pendingSessionId, pendingRecipeId, pendingIsMultiCook, pendingMultiCookGroupId) {
                        // Prioritize multi-cook group ID over individual session/recipe IDs
                        if (pendingMultiCookGroupId != null && pendingMultiCookGroupId!!.isNotEmpty()) {
                            // Navigate to multi-cook restoration
                            navController.navigate("multiCookRestore/$pendingMultiCookGroupId") {
                                popUpTo("home") { inclusive = false }
                            }
                            // Clear the state immediately to prevent Sticky Intent bug.
                            pendingSessionId = -1L
                            pendingRecipeId = -1L
                            pendingIsMultiCook = false
                            pendingMultiCookGroupId = null
                        } else if (pendingSessionId != -1L && pendingRecipeId != -1L) {
                            // Navigate to single recipe cooking restoration
                            navController.navigate("cooking/$pendingRecipeId?sessionId=$pendingSessionId") {
                                popUpTo("home") { inclusive = false }
                            }
                            // Clear the state immediately to prevent Sticky Intent bug.
                            pendingSessionId = -1L
                            pendingRecipeId = -1L
                            pendingIsMultiCook = false
                            pendingMultiCookGroupId = null
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = "home"
                    ) {
                        composable(
                            route = "home",
                            enterTransition = {
                                slideInHorizontally(
                                    initialOffsetX = { -it },
                                    animationSpec = tween(300)
                                )
                            },
                            exitTransition = {
                                slideOutHorizontally(
                                    targetOffsetX = { -it },
                                    animationSpec = tween(300)
                                )
                            }
                        ) {
                            SmartCookHomePage(
                                viewModel = recipeViewModel,
                                onNavigateToAddRecipe = {
                                    navController.navigate("addRecipe")
                                },
                                onRecipeClick = { recipeId ->
                                    navController.navigate("recipeDetail/$recipeId")
                                },
                                // Multi-Cook Navigation
                                onNavigateToMultiCook = {
                                    navController.navigate("selectRecipes")
                                },
                                // Settings Navigation
                                onNavigateToSettings = {
                                    navController.navigate("settings")
                                }
                            )
                        }

                        composable(
                            route = "addRecipe",
                            enterTransition = {
                                slideInHorizontally(
                                    initialOffsetX = { it },
                                    animationSpec = tween(300)
                                )
                            },
                            exitTransition = {
                                slideOutHorizontally(
                                    targetOffsetX = { it },
                                    animationSpec = tween(300)
                                )
                            }
                        ) {
                            EnhancedAddRecipeScreen(
                                viewModel = recipeViewModel,
                                onNavigateBack = {
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable(
                            route = "recipeDetail/{recipeId}",
                            arguments = listOf(
                                navArgument("recipeId") { type = NavType.LongType }
                            ),
                            enterTransition = {
                                slideInHorizontally(
                                    initialOffsetX = { it },
                                    animationSpec = tween(300)
                                ) + fadeIn(animationSpec = tween(300))
                            },
                            exitTransition = {
                                slideOutHorizontally(
                                    targetOffsetX = { it },
                                    animationSpec = tween(300)
                                ) + fadeOut(animationSpec = tween(300))
                            }
                        ) { backStackEntry ->
                            val recipeId = backStackEntry.arguments?.getLong("recipeId") ?: 0L
                            RecipeDetailPage(
                                recipeId = recipeId,
                                viewModel = recipeViewModel,
                                onNavigateBack = {
                                    navController.popBackStack()
                                },
                                onNavigateToCooking = { recipe ->
                                    // Normal navigation no session ID provided, uses default -1
                                    navController.navigate("cooking/${recipe.id}")
                                }
                            )
                        }

                        // Cooking screen with smart timing
                        composable(
                            // Update route to accept optional sessionId query parameter
                            route = "cooking/{recipeId}?sessionId={sessionId}",
                            arguments = listOf(
                                navArgument("recipeId") { type = NavType.LongType },
                                // Define the optional argument with a default value of -1
                                navArgument("sessionId") {
                                    type = NavType.LongType
                                    defaultValue = -1L
                                }
                            ),
                            enterTransition = {
                                slideInVertically(
                                    initialOffsetY = { it },
                                    animationSpec = tween(300)
                                ) + fadeIn(animationSpec = tween(300))
                            },
                            exitTransition = {
                                slideOutVertically(
                                    targetOffsetY = { it },
                                    animationSpec = tween(300)
                                ) + fadeOut(animationSpec = tween(300))
                            }
                        ) { backStackEntry ->
                            val recipeId = backStackEntry.arguments?.getLong("recipeId") ?: 0L

                            // Retrieve the sessionId from the navigation arguments
                            val sessionIdArg = backStackEntry.arguments?.getLong("sessionId") ?: -1L

                            val allRecipes by recipeViewModel.allRecipes.observeAsState(emptyList())
                            val recipe = remember(allRecipes, recipeId) {
                                allRecipes.find { it.id == recipeId }
                            }

                            recipe?.let {
                                EnhancedSingleRecipeCooking(
                                    recipe = it,
                                    onNavigateBack = {
                                        navController.popBackStack()
                                    },
                                    // Pass the argument value. If it's -1 (default), pass null.
                                    resumeSessionId = if (sessionIdArg != -1L) sessionIdArg else null
                                )
                            }
                        }

                        // Select recipes for multi-cook
                        composable(
                            route = "selectRecipes",
                            enterTransition = {
                                slideInHorizontally(
                                    initialOffsetX = { it },
                                    animationSpec = tween(300)
                                )
                            },
                            exitTransition = {
                                slideOutHorizontally(
                                    targetOffsetX = { it },
                                    animationSpec = tween(300)
                                )
                            }
                        ) {
                            var selectedRecipes by remember { mutableStateOf<List<com.example.smartcook.data.Recipe>>(emptyList()) }

                            com.example.smartcook.ui.theme.SelectRecipesScreen(
                                viewModel = recipeViewModel,
                                onBackClick = {
                                    navController.popBackStack()
                                },
                                onStartMultiCook = { recipes ->
                                    selectedRecipes = recipes
                                    navController.navigate("multiCook/${recipes.joinToString(",") { it.id.toString() }}")
                                }
                            )
                        }

                        // Multi-cook mode
                        composable(
                            route = "multiCook/{recipeIds}",
                            arguments = listOf(
                                navArgument("recipeIds") { type = NavType.StringType }
                            ),
                            enterTransition = {
                                slideInVertically(
                                    initialOffsetY = { it },
                                    animationSpec = tween(300)
                                ) + fadeIn(animationSpec = tween(300))
                            },
                            exitTransition = {
                                slideOutVertically(
                                    targetOffsetY = { it },
                                    animationSpec = tween(300)
                                ) + fadeOut(animationSpec = tween(300))
                            }
                        ) { backStackEntry ->
                            val recipeIdsString = backStackEntry.arguments?.getString("recipeIds") ?: ""
                            val allRecipes by recipeViewModel.allRecipes.observeAsState(emptyList())

                            val selectedRecipes = remember(recipeIdsString, allRecipes) {
                                val recipeIds = recipeIdsString.split(",").mapNotNull { it.toIntOrNull() }
                                allRecipes.filter { it.id.toInt() in recipeIds }.map { entity ->
                                    com.example.smartcook.data.Recipe(
                                        id = entity.id.toInt(),
                                        name = entity.name,
                                        emoji = "",
                                        duration = entity.timeMinutes,
                                        isSelected = true
                                    )
                                }
                            }

                            if (selectedRecipes.isNotEmpty()) {
                                com.example.smartcook.ui.theme.MultiCookModeScreen(
                                    selectedRecipes = selectedRecipes,
                                    viewModel = recipeViewModel,
                                    onBackClick = {
                                        navController.popBackStack()
                                    }
                                )
                            }
                        }

                        // Multi-cook restoration route
                        composable(
                            route = "multiCookRestore/{groupId}",
                            arguments = listOf(
                                navArgument("groupId") { type = NavType.StringType }
                            ),
                            enterTransition = {
                                slideInVertically(
                                    initialOffsetY = { it },
                                    animationSpec = tween(300)
                                ) + fadeIn(animationSpec = tween(300))
                            },
                            exitTransition = {
                                slideOutVertically(
                                    targetOffsetY = { it },
                                    animationSpec = tween(300)
                                ) + fadeOut(animationSpec = tween(300))
                            }
                        ) { backStackEntry ->
                            val groupId = backStackEntry.arguments?.getString("groupId") ?: ""
                            val allRecipes by recipeViewModel.allRecipes.observeAsState(emptyList())

                            // Fetch the sessions for this group and convert to Recipe list
                            var selectedRecipes by remember { mutableStateOf<List<com.example.smartcook.data.Recipe>>(emptyList()) }

                            LaunchedEffect(groupId, allRecipes) {
                                if (groupId.isNotEmpty() && allRecipes.isNotEmpty()) {
                                    val database = com.example.smartcook.data.RecipeDatabase.getDatabase(
                                        applicationContext,
                                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
                                    )
                                    val sessionManager = com.example.smartcook.session.SessionManager(
                                        applicationContext,
                                        database.cookingSessionDao()
                                    )

                                    val sessions = sessionManager.getSessionsByGroupId(groupId)
                                    selectedRecipes = sessions.mapNotNull { session ->
                                        allRecipes.find { it.id == session.recipeId }?.let { entity ->
                                            com.example.smartcook.data.Recipe(
                                                id = entity.id.toInt(),
                                                name = entity.name,
                                                emoji = "",
                                                duration = entity.timeMinutes,
                                                isSelected = true
                                            )
                                        }
                                    }
                                }
                            }

                            if (selectedRecipes.isNotEmpty()) {
                                com.example.smartcook.ui.theme.MultiCookModeScreen(
                                    selectedRecipes = selectedRecipes,
                                    viewModel = recipeViewModel,
                                    onBackClick = {
                                        navController.popBackStack()
                                    },
                                    restoreGroupId = groupId
                                )
                            }
                        }

                        composable(
                            route = "settings",
                            enterTransition = {
                                slideInHorizontally(
                                    initialOffsetX = { it },
                                    animationSpec = tween(300)
                                )
                            },
                            exitTransition = {
                                slideOutHorizontally(
                                    targetOffsetX = { it },
                                    animationSpec = tween(300)
                                )
                            }
                        ) {
                            SettingsScreen(
                                settingsViewModel = settingsViewModel,
                                onNavigateBack = {
                                    navController.popBackStack()
                                },
                                onNavigateToCookingHistory = {
                                    navController.navigate("cookingHistory")
                                },
                                onNavigateToPrivacyPolicy = {
                                    // TODO: Implement when privacy policy feature is ready
                                }
                            )
                        }

                        composable(
                            route = "cookingHistory",
                            enterTransition = {
                                slideInHorizontally(
                                    initialOffsetX = { it },
                                    animationSpec = tween(300)
                                )
                            },
                            exitTransition = {
                                slideOutHorizontally(
                                    targetOffsetX = { it },
                                    animationSpec = tween(300)
                                )
                            }
                        ) {
                            val database = (application as SmartCookApplication).database
                            val cookingSessionDao = database.cookingSessionDao()
                            val scope = rememberCoroutineScope()

                            val sessions by cookingSessionDao.getCompletedSessionsFlow()
                                .collectAsState(initial = emptyList())

                            CookingHistoryScreen(
                                sessions = sessions,
                                onNavigateBack = {
                                    navController.popBackStack()
                                },
                                onDeleteSession = { sessionId ->
                                    scope.launch {
                                        cookingSessionDao.deleteSession(sessionId)
                                    }
                                },
                                onClearAllHistory = {
                                    scope.launch {
                                        cookingSessionDao.deleteAllSessions()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val resumeSessionId = intent.getLongExtra("resume_session_id", -1L)
        val resumeRecipeId = intent.getLongExtra("recipe_id", -1L)
        val isMultiCook = intent.getBooleanExtra("is_multi_cook_mode", false)
        val multiCookGroupId = intent.getStringExtra("multi_cook_group_id")

        // Handle both multi-cook and single recipe restoration
        if (multiCookGroupId != null && multiCookGroupId.isNotEmpty()) {
            recreate()
        } else if (resumeSessionId != -1L && resumeRecipeId != -1L) {
            recreate()
        }
    }
}