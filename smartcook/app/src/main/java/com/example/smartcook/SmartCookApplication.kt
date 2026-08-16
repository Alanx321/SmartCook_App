package com.example.smartcook

import android.app.Application
import com.example.smartcook.data.RecipeDatabase
import com.example.smartcook.data.RecipeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class SmartCookApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob())

    val database by lazy { RecipeDatabase.getDatabase(this, applicationScope) }
    val repository by lazy { RecipeRepository(database.recipeDao()) }
}