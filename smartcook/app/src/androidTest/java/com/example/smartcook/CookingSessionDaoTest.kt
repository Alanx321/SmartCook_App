package com.example.smartcook

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.smartcook.data.CookingSessionDao
import com.example.smartcook.data.CookingSessionEntity
import com.example.smartcook.data.RecipeDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class CookingSessionDaoTest {
    private lateinit var cookingSessionDao: CookingSessionDao
    private lateinit var db: RecipeDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Use an in-memory database (it disappears after the test runs)
        db = Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java).build()
        cookingSessionDao = db.cookingSessionDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    @Throws(Exception::class)
    fun writeSessionAndReadInList() = runBlocking {
        // 1. Create a session entity
        val session = CookingSessionEntity(
                sessionId = 1, recipeId = 100, recipeName = "Test Pasta",
                currentStepIndex = 0, totalSteps = 5, isTimerActive = false,
                timerRemainingSeconds = 0, timerTotalSeconds = 0, timerPaused = false,
                sessionStartTimestamp = System.currentTimeMillis(),
                lastUpdateTimestamp = System.currentTimeMillis(),
                estimatedFinishTimestamp = 0L, currentStepInstruction = "Boil water",
                currentStepDuration = 10, currentStepIsTimed = true
        )

        // 2. Insert into DB
        cookingSessionDao.insertSession(session)

        // 3. Retrieve by ID and check if it matches
        val loadedSession = cookingSessionDao.getSessionById(1)
        assertEquals("Test Pasta", loadedSession?.recipeName)
        assertEquals(100L, loadedSession?.recipeId)
    }
}