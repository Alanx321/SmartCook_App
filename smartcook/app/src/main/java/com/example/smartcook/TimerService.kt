package com.example.smartcook

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.example.smartcook.data.RecipeDatabase
import com.example.smartcook.data.SettingsDataStore
import com.example.smartcook.data.dataStore
import com.example.smartcook.session.SessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Data class to hold individual timer instance state
data class TimerInstance(
    val id: String,
    var timeInSeconds: Int = 0,
    var initialTimeInSeconds: Int = 0,
    var isRunning: Boolean = false,
    var isPaused: Boolean = false,
    var recipeName: String = "",
    var currentStep: String = "",
    var soundStopped: Boolean = false,
    var hasPlayedSound: Boolean = false,
    var timerJob: Job? = null,
    var mediaPlayer: MediaPlayer? = null,
    var sessionId: Long = -1,
    var recipeId: Long = -1,
    var stepIndex: Int = 0,
    var totalSteps: Int = 0,
    var stepDuration: Int = 0,
    var stepIsTimed: Boolean = false,
    var isMultiCookMode: Boolean = false,
    var multiCookGroupId: String? = null,
    var onTimerUpdate: ((Int) -> Unit)? = null,
    var onTimerFinished: (() -> Unit)? = null,
    var onSoundStopped: (() -> Unit)? = null
)

class TimerService : Service() {

    private val binder = TimerBinder()
    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null
    private lateinit var notificationManager: TimerNotificationManager

    // Session management
    private lateinit var sessionManager: SessionManager

    // Map to store multiple timer instances
    private val timerInstances = ConcurrentHashMap<String, TimerInstance>()

    // Track which multi-cook groups have active notifications to prevent duplicates
    private val activeMultiCookNotifications = ConcurrentHashMap<String, Boolean>()

    // Backwards compatibility properties for single timer access
    var timeInSeconds = 0
        private set
    var initialTimeInSeconds = 0
        private set
    var isRunning = false
        private set
    var isPaused = false
        private set
    var recipeName = ""
        private set
    var currentStep = ""
        private set
    var soundStopped = false
        private set

    private var hasPlayedSound = false
    private var timerJob: Job? = null

    var onTimerUpdate: ((Int) -> Unit)? = null
    var onTimerFinished: (() -> Unit)? = null
    var onSoundStopped: (() -> Unit)? = null

    inner class TimerBinder : Binder() {
        fun getService(): TimerService = this@TimerService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    // Create a new timer instance with a unique ID
    fun createTimerInstance(): String {
        val timerId = UUID.randomUUID().toString()
        timerInstances[timerId] = TimerInstance(id = timerId)
        return timerId
    }

    // Remove a timer instance
    fun removeTimerInstance(timerId: String) {
        val instance = timerInstances[timerId]
        if (instance != null) {
            instance.timerJob?.cancel()
            stopMediaPlayerForInstance(instance)

            // Clean up multi-cook notification tracking if this was the last timer in the group
            val groupId = instance.multiCookGroupId
            if (instance.isMultiCookMode && groupId != null) {
                timerInstances.remove(timerId)
                // Check if there are any other timers in this group
                val remainingInGroup = getTimersInGroup(groupId)
                if (remainingInGroup.isEmpty()) {
                    activeMultiCookNotifications.remove(groupId)
                }
            } else {
                timerInstances.remove(timerId)
            }
        } else {
            timerInstances.remove(timerId)
        }

        // If no more timers running, stop foreground
        if (timerInstances.isEmpty() || timerInstances.values.none { it.isRunning }) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationManager.cancelNotification()
            activeMultiCookNotifications.clear()
        }
    }

    // Get a timer instance by ID
    fun getTimerInstance(timerId: String): TimerInstance? {
        return timerInstances[timerId]
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = TimerNotificationManager(this)
        acquireWakeLock()
        initializeVibrator()

        // Initialize session manager
        val database = RecipeDatabase.getDatabase(applicationContext, serviceScope)
        sessionManager = SessionManager(applicationContext, database.cookingSessionDao())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TimerNotificationManager.ACTION_PAUSE -> {
                pause()
                provideHapticFeedback(HapticFeedbackType.LIGHT)
            }
            TimerNotificationManager.ACTION_RESUME -> {
                resume()
                provideHapticFeedback(HapticFeedbackType.MEDIUM)
            }
            TimerNotificationManager.ACTION_STOP -> {
                stopTimer()
                provideHapticFeedback(HapticFeedbackType.HEAVY)
                stopSelf()
            }
            TimerNotificationManager.ACTION_STOP_SOUND -> {
                stopSound()
                provideHapticFeedback(HapticFeedbackType.HEAVY)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun initializeVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // CPU wake lock for timer countdown
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SmartCook::TimerWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes max
        }
    }

    private fun acquireScreenWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // Screen wake lock for alarm
        screenWakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
            "SmartCook::ScreenWakeLock"
        ).apply {
            acquire(30 * 1000L) // 30 seconds max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun releaseScreenWakeLock() {
        screenWakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        screenWakeLock = null
    }

    fun setTimer(minutes: Int, seconds: Int, recipe: String, step: String, timerId: String? = null) {
        val instance = if (timerId != null) {
            timerInstances[timerId] ?: return
        } else {
            // Backwards compatibility use first timer or create default
            timerInstances.values.firstOrNull() ?: TimerInstance("default").also { timerInstances["default"] = it }
        }

        instance.timeInSeconds = minutes * 60 + seconds
        instance.initialTimeInSeconds = instance.timeInSeconds
        instance.recipeName = recipe
        instance.currentStep = step
        instance.hasPlayedSound = false
        instance.soundStopped = false

        // Update backwards compatibility properties
        timeInSeconds = instance.timeInSeconds
        initialTimeInSeconds = instance.initialTimeInSeconds
        recipeName = recipe
        currentStep = step
        hasPlayedSound = false
        soundStopped = false
    }

    // Set only the initial timer duration (used for reset) without affecting current time
    fun setInitialTime(minutes: Int, seconds: Int, timerId: String? = null) {
        val instance = if (timerId != null) {
            timerInstances[timerId] ?: return
        } else {
            timerInstances.values.firstOrNull() ?: return
        }

        instance.initialTimeInSeconds = minutes * 60 + seconds

        // Update backwards compatibility
        initialTimeInSeconds = instance.initialTimeInSeconds
    }

    // Set session information for state persistence
    fun setSessionInfo(
        sessionId: Long,
        recipeId: Long,
        stepIndex: Int,
        totalSteps: Int,
        stepDuration: Int,
        stepIsTimed: Boolean,
        timerId: String? = null,
        isMultiCookMode: Boolean = false,
        multiCookGroupId: String? = null
    ) {
        val instance = if (timerId != null) {
            timerInstances[timerId] ?: return
        } else {
            timerInstances.values.firstOrNull() ?: return
        }

        instance.sessionId = sessionId
        instance.recipeId = recipeId
        instance.stepIndex = stepIndex
        instance.totalSteps = totalSteps
        instance.stepDuration = stepDuration
        instance.stepIsTimed = stepIsTimed
        instance.isMultiCookMode = isMultiCookMode
        instance.multiCookGroupId = multiCookGroupId
    }

    fun startTimer(timerId: String? = null) {
        val instance = if (timerId != null) {
            timerInstances[timerId] ?: return
        } else {
            timerInstances.values.firstOrNull() ?: return
        }

        if (instance.timeInSeconds > 0 && !instance.isRunning) {
            instance.isRunning = true
            instance.isPaused = false
            instance.hasPlayedSound = false
            instance.soundStopped = false

            // For multi-cook mode, only create one notification per group
            val groupId = instance.multiCookGroupId
            if (instance.isMultiCookMode && groupId != null) {
                // Check if this group already has a notification
                if (!activeMultiCookNotifications.containsKey(groupId)) {
                    activeMultiCookNotifications[groupId] = true
                    startForeground(TimerNotificationManager.NOTIFICATION_ID, createMultiCookGroupNotification(groupId))
                } else {
                    // Just update the existing notification
                    updateMultiCookGroupNotification(groupId)
                }
            } else {
                // Single recipe mode create individual notification
                startForeground(TimerNotificationManager.NOTIFICATION_ID, createNotificationForInstance(instance))
            }

            startTimerCoroutineForInstance(instance)

            // Update backwards compatibility properties
            isRunning = instance.isRunning
            isPaused = instance.isPaused
        }
    }

    private fun startTimerCoroutineForInstance(instance: TimerInstance) {
        instance.timerJob?.cancel()
        instance.timerJob = serviceScope.launch {
            while (isActive && instance.isRunning && !instance.isPaused && instance.timeInSeconds > 0) {
                delay(1000L)
                instance.timeInSeconds--
                instance.onTimerUpdate?.invoke(instance.timeInSeconds)
                updateNotificationForInstance(instance)

                // Persist session state every 5 seconds
                if (instance.timeInSeconds % 5 == 0) {
                    persistSessionStateForInstance(instance)
                }

                // Haptic feedback at specific intervals
                when (instance.timeInSeconds) {
                    60 -> provideHapticFeedback(HapticFeedbackType.LIGHT)
                    30 -> provideHapticFeedback(HapticFeedbackType.MEDIUM)
                    10 -> provideHapticFeedback(HapticFeedbackType.MEDIUM)
                }

                if (instance.timeInSeconds == 0) {
                    // Wake up the screen
                    acquireScreenWakeLock()

                    // Show high priority alarm notification
                    val groupId = instance.multiCookGroupId
                    if (instance.isMultiCookMode && groupId != null) {
                        // For multi-cook, only show one alarm notification per group
                        // Check if this is the first timer to finish in the group
                        val finishedTimersInGroup = getTimersInGroup(groupId).count {
                            it.timeInSeconds == 0 && it.hasPlayedSound
                        }

                        if (finishedTimersInGroup == 0) {
                            // This is the first timer to finish, show the alarm
                            notificationManager.showAlarmNotification(
                                recipeName = "Multi-Cook Timer Finished!",
                                currentStep = "${instance.recipeName}: ${instance.currentStep}",
                                sessionId = instance.sessionId,
                                recipeId = instance.recipeId,
                                isMultiCookMode = true,
                                multiCookGroupId = groupId
                            )
                        }
                        // Always play sound for each finished timer
                    } else {
                        // Single recipe alarm
                        notificationManager.showAlarmNotification(
                            recipeName = instance.recipeName,
                            currentStep = instance.currentStep,
                            sessionId = instance.sessionId,
                            recipeId = instance.recipeId,
                            isMultiCookMode = false,
                            multiCookGroupId = null
                        )
                    }

                    // Play sound and vibrate
                    playTimerSoundForInstance(instance)
                    provideHapticFeedback(HapticFeedbackType.SUCCESS)

                    instance.onTimerFinished?.invoke()
                    instance.isRunning = false
                    instance.isPaused = false

                    // Persist final state
                    persistSessionStateForInstance(instance)
                }
            }
        }
    }

    private fun persistSessionStateForInstance(instance: TimerInstance) {
        if (instance.sessionId != -1L) {
            serviceScope.launch {
                try {
                    sessionManager.updateSession(
                        sessionId = instance.sessionId,
                        currentStepIndex = instance.stepIndex,
                        currentStepInstruction = instance.currentStep,
                        currentStepDuration = instance.stepDuration,
                        currentStepIsTimed = instance.stepIsTimed,
                        isTimerActive = instance.isRunning,
                        timerRemainingSeconds = instance.timeInSeconds,
                        timerTotalSeconds = instance.initialTimeInSeconds,
                        timerPaused = instance.isPaused
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun playTimerSoundForInstance(instance: TimerInstance) {
        if (!instance.hasPlayedSound) {
            try {
                stopMediaPlayerForInstance(instance)
                val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

                instance.mediaPlayer = MediaPlayer.create(this, notification)
                instance.mediaPlayer?.isLooping = true
                instance.mediaPlayer?.start()

                instance.hasPlayedSound = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun stopMediaPlayerForInstance(instance: TimerInstance) {
        try {
            instance.mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
            instance.mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationForInstance(instance: TimerInstance) = notificationManager.createTimerNotification(
        recipeName = instance.recipeName,
        currentStep = instance.currentStep,
        timeInSeconds = instance.timeInSeconds,
        initialTimeInSeconds = instance.initialTimeInSeconds,
        isRunning = instance.isRunning,
        isPaused = instance.isPaused,
        isFinished = instance.timeInSeconds == 0 && !instance.isRunning,
        soundStopped = instance.soundStopped,
        sessionId = instance.sessionId,
        recipeId = instance.recipeId,
        isMultiCookMode = instance.isMultiCookMode,
        multiCookGroupId = instance.multiCookGroupId
    )

    private fun updateNotificationForInstance(instance: TimerInstance) {
        // For multi-cook mode, update the group notification instead
        val groupId = instance.multiCookGroupId
        if (instance.isMultiCookMode && groupId != null) {
            updateMultiCookGroupNotification(groupId)
        } else {
            notificationManager.updateNotification(createNotificationForInstance(instance))
        }
    }

    // Get all timer instances in a multi-cook group
    private fun getTimersInGroup(groupId: String): List<TimerInstance> {
        return timerInstances.values.filter {
            it.isMultiCookMode && it.multiCookGroupId == groupId
        }
    }

    // Create a multi-cook group notification
    private fun createMultiCookGroupNotification(groupId: String): android.app.Notification {
        val timersInGroup = getTimersInGroup(groupId)
        if (timersInGroup.isEmpty()) {
            // Fallback to a basic notification if no timers found
            return notificationManager.createTimerNotification(
                recipeName = "Multi-Cook Session",
                currentStep = "No active timers",
                timeInSeconds = 0,
                initialTimeInSeconds = 0,
                isRunning = false,
                isPaused = false,
                isFinished = false,
                soundStopped = false,
                sessionId = -1L,
                recipeId = -1L,
                isMultiCookMode = true,
                multiCookGroupId = groupId
            )
        }

        // Get the first timer instance to extract common data
        val firstTimer = timersInGroup.first()

        // Count active timers
        val activeTimers = timersInGroup.count { it.isRunning && !it.isPaused }
        val pausedTimers = timersInGroup.count { it.isPaused }
        val totalRecipes = timersInGroup.size

        // Find the next timer that will finish
        val nextTimer = timersInGroup
            .filter { it.isRunning && !it.isPaused && it.timeInSeconds > 0 }
            .minByOrNull { it.timeInSeconds }

        val displayName = when {
            nextTimer != null -> "${nextTimer.recipeName}"
            activeTimers > 0 -> "Multi-Cook: $activeTimers active"
            pausedTimers > 0 -> "Multi-Cook: $pausedTimers paused"
            else -> "Multi-Cook Session"
        }

        val displayStep = when {
            nextTimer != null -> "Next: ${nextTimer.currentStep}"
            else -> "$totalRecipes recipes in progress"
        }

        return notificationManager.createTimerNotification(
            recipeName = displayName,
            currentStep = displayStep,
            timeInSeconds = nextTimer?.timeInSeconds ?: 0,
            initialTimeInSeconds = nextTimer?.initialTimeInSeconds ?: 0,
            isRunning = activeTimers > 0,
            isPaused = pausedTimers > 0 && activeTimers == 0,
            isFinished = timersInGroup.all { it.timeInSeconds == 0 && !it.isRunning },
            soundStopped = false,
            sessionId = firstTimer.sessionId,
            recipeId = firstTimer.recipeId,
            isMultiCookMode = true,
            multiCookGroupId = groupId
        )
    }

    // Update multi-cook group notification
    private fun updateMultiCookGroupNotification(groupId: String) {
        notificationManager.updateNotification(createMultiCookGroupNotification(groupId))
    }

    fun pause(timerId: String? = null) {
        val instance = if (timerId != null) {
            timerInstances[timerId] ?: return
        } else {
            timerInstances.values.firstOrNull() ?: return
        }

        instance.isPaused = true
        updateNotificationForInstance(instance)
        persistSessionStateForInstance(instance)

        // Update backwards compatibility
        isPaused = instance.isPaused
    }

    fun resume(timerId: String? = null) {
        val instance = if (timerId != null) {
            timerInstances[timerId] ?: return
        } else {
            timerInstances.values.firstOrNull() ?: return
        }

        if (instance.timeInSeconds > 0 && instance.isPaused) {
            instance.isPaused = false
            startTimerCoroutineForInstance(instance)
            updateNotificationForInstance(instance)
            persistSessionStateForInstance(instance)

            // Update backwards compatibility
            isPaused = instance.isPaused
        }
    }

    fun reset(timerId: String? = null) {
        val instance = if (timerId != null) {
            timerInstances[timerId] ?: return
        } else {
            timerInstances.values.firstOrNull() ?: return
        }

        instance.timeInSeconds = instance.initialTimeInSeconds
        instance.isRunning = false
        instance.isPaused = false
        instance.hasPlayedSound = false
        instance.soundStopped = false
        stopMediaPlayerForInstance(instance)
        releaseScreenWakeLock()
        instance.timerJob?.cancel()
        updateNotificationForInstance(instance)

        // Update backwards compatibility
        timeInSeconds = instance.timeInSeconds
        isRunning = instance.isRunning
        isPaused = instance.isPaused
    }

    fun stopTimer(timerId: String? = null) {
        val instance = if (timerId != null) {
            timerInstances[timerId] ?: return
        } else {
            timerInstances.values.firstOrNull() ?: return
        }

        instance.isRunning = false
        instance.isPaused = false
        instance.soundStopped = false
        instance.timerJob?.cancel()
        stopMediaPlayerForInstance(instance)
        releaseScreenWakeLock()

        // If no more timers running stop foreground
        if (timerInstances.values.none { it.isRunning }) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationManager.cancelNotification()
        }

        // Clean up session if needed
        persistSessionStateForInstance(instance)

        // Update backwards compatibility
        isRunning = instance.isRunning
        isPaused = instance.isPaused
    }

    fun stopSound(timerId: String? = null) {
        val instance = if (timerId != null) {
            timerInstances[timerId] ?: return
        } else {
            timerInstances.values.firstOrNull() ?: return
        }

        stopMediaPlayerForInstance(instance)
        instance.soundStopped = true
        instance.onSoundStopped?.invoke()
        releaseScreenWakeLock()

        // Stop foreground service and remove notification
        if (timerInstances.values.none { it.isRunning }) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationManager.cancelNotification()
        }

        // Update backwards compatibility
        soundStopped = instance.soundStopped
    }

    // Clean up session when cooking is completed or cancelled
    fun cleanupSession(sessionId: Long, timerId: String, onComplete: () -> Unit) {
        // This ensures the timer loop is cancelled before writing the completed state to DB
        removeTimerInstance(timerId)
        
        serviceScope.launch {
            try {
                if (sessionId != -1L) {
                    sessionManager.completeSession(sessionId)
                }
                removeTimerInstance(timerId)

                // Switch to main thread to call the UI callback
                withContext(Dispatchers.Main) {
                    onComplete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getFormattedTime(): String {
        val minutes = timeInSeconds / 60
        val seconds = timeInSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    fun isFinished(): Boolean = timeInSeconds == 0 && !isRunning

    private fun createNotification() = notificationManager.createTimerNotification(
        recipeName = recipeName,
        currentStep = currentStep,
        timeInSeconds = timeInSeconds,
        initialTimeInSeconds = initialTimeInSeconds,
        isRunning = isRunning,
        isPaused = isPaused,
        isFinished = isFinished(),
        soundStopped = soundStopped
    )

    private fun updateNotification() {
        notificationManager.updateNotification(createNotification())
    }

    enum class HapticFeedbackType {
        LIGHT, MEDIUM, HEAVY, SUCCESS
    }

    private fun provideHapticFeedback(type: HapticFeedbackType) {
        serviceScope.launch {
            try {
                // Check if vibration is enabled in settings
                val settingsDataStore = SettingsDataStore(applicationContext)
                val isVibrationEnabled = settingsDataStore.isVibrationEnabled.first()

                if (isVibrationEnabled) {
                    vibrator?.let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val effect = when (type) {
                                HapticFeedbackType.LIGHT -> VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                                HapticFeedbackType.MEDIUM -> VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                                HapticFeedbackType.HEAVY -> VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
                                HapticFeedbackType.SUCCESS -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                                    } else {
                                        VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)
                                    }
                                }
                            }
                            it.vibrate(effect)
                        } else {
                            @Suppress("DEPRECATION")
                            val duration = when (type) {
                                HapticFeedbackType.LIGHT -> 50L
                                HapticFeedbackType.MEDIUM -> 100L
                                HapticFeedbackType.HEAVY -> 200L
                                HapticFeedbackType.SUCCESS -> 300L
                            }
                            it.vibrate(duration)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop all timer instances
        timerInstances.values.forEach { instance ->
            instance.timerJob?.cancel()
            stopMediaPlayerForInstance(instance)
        }
        timerInstances.clear()

        timerJob?.cancel()
        serviceJob.cancel()
        releaseWakeLock()
        releaseScreenWakeLock()
        vibrator = null
    }
}