package com.example.smartcook

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.example.smartcook.data.RecipeEntity
import com.example.smartcook.data.getImageRes
import com.example.smartcook.ui.theme.SimpleSearchBar
import com.example.smartcook.viewmodel.RecipeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartCookHomePage(
    viewModel: RecipeViewModel,
    onNavigateToAddRecipe: () -> Unit,
    onRecipeClick: (Long) -> Unit,
    onNavigateToMultiCook: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {} 
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    // Use rememberSaveable to persist state across navigation
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { 2 }
    )

    val context = LocalContext.current

    // Preserve scroll states for both tabs
    val allRecipesScrollState = rememberLazyListState()
    val favoritesScrollState = rememberLazyListState()

    // Observe recipes
    val allRecipes by viewModel.allRecipes.observeAsState(emptyList())
    val favoriteRecipes by viewModel.favoriteRecipes.observeAsState(emptyList())

    // ═══════════════════════════════════════════════════════════
    // NEW: Collect search states
    // ═══════════════════════════════════════════════════════════
    val isSearchExpanded by viewModel.isSearchExpanded.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    // Filter recipes based on search query
    val filteredAllRecipes = remember(allRecipes, searchQuery) {
        if (searchQuery.isEmpty()) {
            allRecipes
        } else {
            allRecipes.filter { recipe ->
                recipe.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val filteredFavoriteRecipes = remember(favoriteRecipes, searchQuery) {
        if (searchQuery.isEmpty()) {
            favoriteRecipes
        } else {
            favoriteRecipes.filter { recipe ->
                recipe.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    // ═══════════════════════════════════════════════════════════

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                onCloseDrawer = {
                    coroutineScope.launch {
                        drawerState.close()
                    }
                },
                onNavigateToHome = {
                    coroutineScope.launch {
                        pagerState.scrollToPage(0)
                    }
                    coroutineScope.launch {
                        drawerState.close()
                    }
                },
                onNavigateToFavorites = {
                    coroutineScope.launch {
                        pagerState.scrollToPage(1)
                    }
                    coroutineScope.launch {
                        drawerState.close()
                    }
                },
                onNavigateToSettings = {
                    coroutineScope.launch {
                        drawerState.close()
                    }
                    onNavigateToSettings()
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                SmartCookTopBar(
                    onMenuClick = {
                        coroutineScope.launch {
                            drawerState.open()
                        }
                    },
                    onAddRecipeClick = onNavigateToAddRecipe
                )
            },
            bottomBar = {
                StartMultiCookButton(onClick = onNavigateToMultiCook)
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // Tabs + Search
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TabRow(
                        selectedTabIndex = pagerState.currentPage,
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = Color(0xFFFF7961),
                        divider = {},
                        indicator = { tabPositions ->
                            TabRowDefaults.Indicator(
                                modifier = Modifier.tabIndicatorOffset(
                                    tabPositions[pagerState.currentPage]
                                ),
                                color = Color(0xFFFF7961),
                                height = 3.dp
                            )
                        }
                    ) {
                        Tab(
                            selected = pagerState.currentPage == 0,
                            onClick = {
                                coroutineScope.launch { pagerState.animateScrollToPage(0) }
                            },
                            text = { Text("All", fontWeight = FontWeight.Bold) }
                        )
                        Tab(
                            selected = pagerState.currentPage == 1,
                            onClick = {
                                coroutineScope.launch { pagerState.animateScrollToPage(1) }
                            },
                            text = { Text("Favorites", fontWeight = FontWeight.Bold) }
                        )
                    }

                    // ═══════════════════════════════════════════════════════════
                    // MODIFIED: Search icon now toggles search bar
                    // ═══════════════════════════════════════════════════════════
                    IconButton(onClick = { viewModel.toggleSearch() }) {
                        Icon(
                            imageVector = if (isSearchExpanded) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (isSearchExpanded) "Close search" else "Search",
                            tint = if (isSearchExpanded) Color(0xFFFF7961) else Color.Gray
                        )
                    }
                }

                // ═══════════════════════════════════════════════════════════
                // NEW: Search bar (expands when search icon is clicked)
                // ═══════════════════════════════════════════════════════════
                SimpleSearchBar(
                    isExpanded = isSearchExpanded,
                    searchQuery = searchQuery,
                    onQueryChange = { viewModel.updateSearchQuery(it) },
                    onClear = { viewModel.clearSearch() }
                )

                // Recipes pager
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    // ═══════════════════════════════════════════════════════════
                    // MODIFIED: Use filtered recipes instead of original
                    // ═══════════════════════════════════════════════════════════
                    val recipesToShow = when (page) {
                        0 -> filteredAllRecipes
                        1 -> filteredFavoriteRecipes
                        else -> filteredAllRecipes
                    }

                    val scrollState = when (page) {
                        0 -> allRecipesScrollState
                        1 -> favoritesScrollState
                        else -> allRecipesScrollState
                    }

                    // Show empty state if no results
                    if (recipesToShow.isEmpty() && searchQuery.isNotEmpty()) {
                        EmptySearchResult(searchQuery = searchQuery)
                    } else {
                        RecipeList(
                            recipes = recipesToShow,
                            context = context,
                            scrollState = scrollState,
                            onFavoriteToggle = { recipeId, isFavorite ->
                                viewModel.toggleFavorite(recipeId, isFavorite)
                            },
                            onRecipeClick = onRecipeClick
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// NEW: Empty search result composable
// ═══════════════════════════════════════════════════════════
@Composable
private fun EmptySearchResult(searchQuery: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color.Gray
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No recipes found",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No results for \"$searchQuery\"",
                fontSize = 14.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}

// Separate composable for the recipe list to improve recomposition scope
@Composable
private fun RecipeList(
    recipes: List<RecipeEntity>,
    context: android.content.Context,
    scrollState: androidx.compose.foundation.lazy.LazyListState,
    onFavoriteToggle: (Long, Boolean) -> Unit,
    onRecipeClick: (Long) -> Unit
) {
    LazyColumn(
        state = scrollState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(
            count = recipes.size,
            key = { index -> recipes[index].id }
        ) { index ->
            val recipe = recipes[index]
            RecipeCard(
                recipe = recipe,
                imageRes = recipe.getImageRes(context),
                onFavoriteToggle = {
                    onFavoriteToggle(recipe.id, !recipe.isFavorite)
                },
                onClick = {
                    onRecipeClick(recipe.id)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartCookTopBar(
    onMenuClick: () -> Unit = {},
    onAddRecipeClick: () -> Unit = {}
) {
    TopAppBar(
        title = {
            Text(
                text = "SmartCook",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Menu",
                    tint = Color.White
                )
            }
        },
        actions = {
            IconButton(onClick = onAddRecipeClick) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add Recipe",
                    tint = Color.White
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFFFF7961)
        )
    )
}

@Composable
fun RecipeCard(
    recipe: RecipeEntity,
    imageRes: Int,
    onFavoriteToggle: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Image
            Image(
                painter = painterResource(id = imageRes),
                contentDescription = recipe.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            )

            // Info section
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = recipe.name,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onFavoriteToggle,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            if (recipe.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (recipe.isFavorite) Color(0xFFFF5252) else Color.Gray
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFC107),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        // FIXED: added color =
                        Text(recipe.difficulty, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        // FIXED: added color =
                        Text("${recipe.servings} (servings)", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AccessTime,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        // FIXED: added color =
                        Text("${recipe.timeMinutes}min", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun StartMultiCookButton(onClick: () -> Unit = {}) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF4CAF50)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = "START MULTI-COOK",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}