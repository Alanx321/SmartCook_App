package com.example.smartcook.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Database(entities = [RecipeEntity::class, CookingSessionEntity::class], version = 6, exportSchema = false)
abstract class RecipeDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao
    abstract fun cookingSessionDao(): CookingSessionDao

    companion object {
        @Volatile
        private var INSTANCE: RecipeDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): RecipeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RecipeDatabase::class.java,
                    "recipe_database"
                )
                    // This allows Room to destroy the old DB and recreate it if the version changes
                    .fallbackToDestructiveMigration() 
                    .addCallback(RecipeDatabaseCallback(scope))
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class RecipeDatabaseCallback(
            private val scope: CoroutineScope
        ) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    scope.launch {
                        populateDatabase(database.recipeDao())
                    }
                }
            }
        }

        suspend fun populateDatabase(recipeDao: RecipeDao) {
            recipeDao.deleteAllRecipes()

            // Build recipe with automatic time calculation
            val pastaCarbonara = buildRecipe(
                name = "Pasta Carbonara",
                difficulty = "Medium",
                servings = 4,
                imageName = "pasta_carbonara",
                isFavorite = true,
                ingredients = "400g spaghetti|200g pancetta|4 eggs|100g Parmesan cheese|Salt & pepper|2 cloves garlic",
                steps = arrayOf(
                    Triple("Bring a large pot of salted water to boil", 4, false),
                    Triple("Cook spaghetti according to package directions", 10, true),
                    Triple("While pasta cooks, dice the pancetta into small cubes", 2, false),
                    Triple("Fry pancetta in a large skillet until crispy", 8, true),
                    Triple("In a bowl, whisk together eggs and grated Parmesan cheese", 1, false),
                    Triple("Drain pasta, reserving 1 cup of pasta water", 1, false),
                    Triple("Add hot pasta to the pan with pancetta and remove from heat", 1, false),
                    Triple("Quickly stir in egg mixture, adding pasta water to create a creamy sauce", 2, false),
                    Triple("Season with black pepper and serve immediately", 1, false)
                )
            )

            val chickenCurry = buildRecipe(
                name = "Chicken Curry",
                difficulty = "Easy",
                servings = 5,
                imageName = "chicken_curry",
                isFavorite = true,
                ingredients = "500g chicken breast|2 onions|3 cloves garlic|2 tbsp curry powder|400ml coconut milk|2 tomatoes|1 tbsp oil|Salt to taste",
                steps = arrayOf(
                    Triple("Cut chicken into bite-sized pieces and season with salt", 4, false),
                    Triple("Chop onions, mince garlic, and dice tomatoes", 4, false),
                    Triple("Heat oil in a large pan over medium heat", 2, false),
                    Triple("Sauté chopped onions until golden brown", 8, true),
                    Triple("Add minced garlic and curry powder, cook until fragrant", 2, true),
                    Triple("Add chicken pieces and brown on all sides", 10, true),
                    Triple("Add chopped tomatoes and cook until softened", 5, true),
                    Triple("Pour in coconut milk and bring to a simmer", 3, false),
                    Triple("Simmer uncovered for 20 minutes, stirring occasionally", 20, true),
                    Triple("Adjust seasoning with salt and serve with rice", 2, false)
                )
            )

            val porkRoast = buildRecipe(
                name = "Pork Roast",
                difficulty = "Hard",
                servings = 6,
                imageName = "pork_roast",
                isFavorite = false,
                ingredients = "1.5kg pork loin|4 cloves garlic|2 tbsp olive oil|2 tsp rosemary|2 tsp thyme|Salt & pepper|1 cup chicken broth",
                steps = arrayOf(
                    Triple("Preheat oven to 180°C (350°F)", 8, true),
                    Triple("Slice garlic cloves thinly", 2, false),
                    Triple("Make small slits all over the pork and insert garlic slices", 3, false),
                    Triple("Rub the pork with olive oil, rosemary, thyme, salt, and pepper", 3, false),
                    Triple("Place pork in a roasting pan and pour chicken broth around it", 2, false),
                    Triple("Roast in the oven for 60 minutes, basting every 20 minutes", 60, true),
                    Triple("Check internal temperature reaches 65°C (145°F)", 2, false),
                    Triple("Remove from oven and tent with foil", 1, false),
                    Triple("Let rest for 10 minutes before slicing", 7, true),
                    Triple("Slice and serve with pan juices", 2, false)
                )
            )

            val quickStirFry = buildRecipe(
                name = "Quick Stir-Fry",
                difficulty = "Easy",
                servings = 2,
                imageName = "ic_launcher_foreground",
                isFavorite = false,
                ingredients = "300g chicken|1 bell pepper|1 onion|2 cloves garlic|Soy sauce|Oil",
                steps = arrayOf(
                    Triple("Slice chicken, pepper, and onion into strips", 4, false),
                    Triple("Mince garlic cloves", 1, false),
                    Triple("Heat oil in a wok or large pan over high heat", 2, false),
                    Triple("Stir-fry chicken until cooked through", 7, true),
                    Triple("Add vegetables and garlic, stir-fry until tender-crisp", 5, true),
                    Triple("Add soy sauce, toss to combine, and serve immediately", 1, false)
                )
            )

            recipeDao.insertRecipes(listOf(pastaCarbonara, chickenCurry, porkRoast, quickStirFry))
        }

        // Helper function to build a recipe with automatic time calculation
        private fun buildRecipe(
            name: String,
            difficulty: String,
            servings: Int,
            imageName: String,
            isFavorite: Boolean,
            ingredients: String,
            steps: Array<Triple<String, Int, Boolean>>
        ): RecipeEntity {
            // Calculate total time from steps
            val totalTime = steps.sumOf { it.second }

            return RecipeEntity(
                name = name,
                difficulty = difficulty,
                servings = servings,
                timeMinutes = totalTime,
                imageName = imageName,
                isFavorite = isFavorite,
                ingredients = ingredients,
                steps = buildSteps(*steps)
            )
        }

        // Helper function to build steps string with timing information
        private fun buildSteps(vararg steps: Triple<String, Int, Boolean>): String {
            return steps.joinToString("||") { (instruction, duration, isTimed) ->
                "$instruction|$duration|$isTimed"
            }
        }
    }
}