package com.example.smartcook.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.smartcook.data.RecipeEntity
import com.example.smartcook.data.RecipeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RecipeViewModel(private val repository: RecipeRepository) : ViewModel() {

    val allRecipes = repository.allRecipes.asLiveData()
    val favoriteRecipes = repository.favoriteRecipes.asLiveData()

    // ═══════════════════════════════════════════════════════════
    // NEW: Search state for UC7
    // ═══════════════════════════════════════════════════════════

    // Whether search bar is expanded
    private val _isSearchExpanded = MutableStateFlow(false)
    val isSearchExpanded: StateFlow<Boolean> = _isSearchExpanded.asStateFlow()

    // Current search query text
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Toggle search bar visibility
    fun toggleSearch() {
        _isSearchExpanded.value = !_isSearchExpanded.value
        // Clear search when closing
        if (!_isSearchExpanded.value) {
            _searchQuery.value = ""
        }
    }

    // Update search query
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // Clear search query
    fun clearSearch() {
        _searchQuery.value = ""
    }

    // ═══════════════════════════════════════════════════════════
    // Existing methods (unchanged)
    // ═══════════════════════════════════════════════════════════

    fun insertRecipe(recipe: RecipeEntity) = viewModelScope.launch {
        repository.insertRecipe(recipe)
    }

    fun updateRecipe(recipe: RecipeEntity) = viewModelScope.launch {
        repository.updateRecipe(recipe)
    }

    fun toggleFavorite(id: Long, isFavorite: Boolean) = viewModelScope.launch {
        repository.toggleFavorite(id, isFavorite)
    }

    fun deleteRecipe(recipe: RecipeEntity) = viewModelScope.launch {
        repository.deleteRecipe(recipe)
    }
}

class RecipeViewModelFactory(private val repository: RecipeRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RecipeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RecipeViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}