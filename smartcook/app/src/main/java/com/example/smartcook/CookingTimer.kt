package com.example.smartcook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat

class CookingTimerState(private val context: Context) {
    var timeInSeconds by mutableStateOf(0)
        private set

    private var initialTimeInSeconds = 0

    var isRunning by mutableStateOf(false)
        private set

    var isPaused by mutableStateOf(false)
        private set

    var soundStopped by mutableStateOf(false)
        private set

    var recipeName by mutableStateOf("")
    var currentStep by mutableStateOf("")

    var timerService by mutableStateOf<TimerService?>(null)
        private set
    private var isBound = false

    // Unique timer ID for this instance
    private var timerId: String? = null

    private var vibrator: Vibrator? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TimerService.TimerBinder
            timerService = binder.getService()
            isBound = true

            // Create a unique timer instance in the service
            timerId = timerService?.createTimerInstance()

            // When connecting after reboot, sync the current remaining time,
            // not just the initial time. 
            if (timerId != null) {
                // Send the current remaining time to the service
                // Use timeInSeconds (remaining) instead of initialTimeInSeconds (total)
                if (timeInSeconds > 0) {
                    timerService?.setTimer(
                        timeInSeconds / 60,
                        timeInSeconds % 60,
                        recipeName,
                        currentStep,
                        timerId
                    )
                } else if (initialTimeInSeconds > 0) {
                    // Fallback to initial time if current time is 0
                     timerService?.setTimer(
                        initialTimeInSeconds / 60,
                        initialTimeInSeconds % 60,
                        recipeName,
                        currentStep,
                        timerId
                    )
                }

                // Explicitly sync the Initial time (for Reset functionality)
                if (initialTimeInSeconds > 0) {
                    timerService?.setInitialTime(
                        initialTimeInSeconds / 60,
                        initialTimeInSeconds % 60,
                        timerId
                    )
                }
            }

            // Set up callbacks to receive updates from the service for this specific timer
            timerService?.let { boundService ->
                timerId?.let { id ->
                    boundService.getTimerInstance(id)?.let { instance ->
                        instance.onTimerUpdate = { time ->
                            timeInSeconds = time
                        }

                        instance.onTimerFinished = {
                            isRunning = false
                            isPaused = false
                        }

                        instance.onSoundStopped = {
                            soundStopped = true
                        }

                        // If the service was already running (restoring background state),
                        // sync the UI state to match the service.
                        if (instance.isRunning && instance.timeInSeconds > 0) {
                            timeInSeconds = instance.timeInSeconds
                            initialTimeInSeconds = instance.initialTimeInSeconds
                            isRunning = true
                            isPaused = instance.isPaused
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            timerService = null
            isBound = false
        }
    }

    init {
        initializeVibrator()
    }

    private fun initializeVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun bindService() {
        val intent = Intent(context, TimerService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService() {
        if (isBound) {
            // Clear callbacks to prevent leaks
            timerId?.let { id ->
                timerService?.getTimerInstance(id)?.let { instance ->
                    instance.onTimerUpdate = null
                    instance.onTimerFinished = null
                    instance.onSoundStopped = null
                }
            }

            context.unbindService(serviceConnection)
            isBound = false
        }
    }

    fun setTime(minutes: Int, seconds: Int) {
        val totalSeconds = minutes * 60 + seconds
        timeInSeconds = totalSeconds
        initialTimeInSeconds = totalSeconds

        // Always try to update service if bound
        if (isBound && timerService != null && timerId != null) {
            timerService?.setTimer(minutes, seconds, recipeName, currentStep, timerId)
        }
        // If not bound, the onServiceConnected logic will handle it using initialTimeInSeconds
    }

    // Set only the initial timer duration (used for reset) without affecting current display time
    fun setInitialTime(minutes: Int, seconds: Int) {
        val totalSeconds = minutes * 60 + seconds
        initialTimeInSeconds = totalSeconds

        // Update service if bound
        if (isBound && timerService != null && timerId != null) {
            timerService?.setInitialTime(minutes, seconds, timerId)
        }
    }

    fun start() {
        if (initialTimeInSeconds > 0) {
            // Haptic feedback on start
            provideHapticFeedback(HapticType.MEDIUM)

            // Ensure service has state before starting
            if (isBound && timerId != null) {
                timerService?.setTimer(
                    timeInSeconds / 60,
                    timeInSeconds % 60,
                    recipeName,
                    currentStep,
                    timerId
                )

                // Must re-assert the initial time afterwards
                // because setTimer() in the Service overwrites initialTime to match timeInSeconds
                // Need initialTime to remain as the "Total Duration" for reset purposes
                if (initialTimeInSeconds > timeInSeconds) {
                    timerService?.setInitialTime(
                        initialTimeInSeconds / 60,
                        initialTimeInSeconds % 60,
                        timerId
                    )
                }
            }

            // Start foreground service
            val intent = Intent(context, TimerService::class.java)
            ContextCompat.startForegroundService(context, intent)

            // Small delay to ensure service is ready
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                timerId?.let { id ->
                    timerService?.startTimer(id)
                }
                isRunning = true
                isPaused = false
            }, 100)
        }
    }

    fun pause() {
        provideHapticFeedback(HapticType.LIGHT)
        timerId?.let { id ->
            timerService?.pause(id)
        }
        isPaused = true
    }

    fun resume() {
        if (timeInSeconds > 0) {
            provideHapticFeedback(HapticType.MEDIUM)
            timerId?.let { id ->
                timerService?.resume(id)
            }
            isPaused = false
        }
    }

    fun reset() {
        provideHapticFeedback(HapticType.LIGHT)
        timerId?.let { id ->
            timerService?.reset(id)
        }
        timeInSeconds = initialTimeInSeconds
        isRunning = false
        isPaused = false
        soundStopped = false
    }

    fun stopSound() {
        provideHapticFeedback(HapticType.HEAVY)
        timerId?.let { id ->
            timerService?.stopSound(id)
        }
        soundStopped = true
    }

    fun stopTimer() {
        provideHapticFeedback(HapticType.HEAVY)
        timerId?.let { id ->
            timerService?.stopTimer(id)
        }
        isRunning = false
        isPaused = false
        soundStopped = false
    }

    fun getTimerId(): String? = timerId

    fun getFormattedTime(): String {
        val minutes = timeInSeconds / 60
        val seconds = timeInSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    fun isFinished(): Boolean = timeInSeconds == 0 && initialTimeInSeconds > 0 && !isRunning

    fun getInitialTime(): Int = initialTimeInSeconds

    enum class HapticType {
        LIGHT, MEDIUM, HEAVY
    }

    private fun provideHapticFeedback(type: HapticType) {
        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = when (type) {
                    HapticType.LIGHT -> VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                    HapticType.MEDIUM -> VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                    HapticType.HEAVY -> VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                it.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val duration = when (type) {
                    HapticType.LIGHT -> 30L
                    HapticType.MEDIUM -> 50L
                    HapticType.HEAVY -> 100L
                }
                it.vibrate(duration)
            }
        }
    }

    fun cleanup() {
        // Remove this timer instance from the service
        timerId?.let { id ->
            timerService?.removeTimerInstance(id)
        }

        unbindService()
        vibrator = null
        timerId = null
    }
}

@Composable
fun rememberCookingTimerState(
    context: Context,
    initialMinutes: Int = 0,
    initialSeconds: Int = 10,
    recipeName: String = "",
    currentStep: String = "",
    skipInitialSetup: Boolean = false
): CookingTimerState {
    val timerState = remember {
        CookingTimerState(context).apply {
            this.recipeName = recipeName
            this.currentStep = currentStep
        }
    }

    // Bind service first
    DisposableEffect(Unit) {
        timerState.bindService()

        onDispose {
            timerState.cleanup()
        }
    }

    // Set initial time after first composition
    LaunchedEffect(skipInitialSetup) {
        if (!skipInitialSetup) {
            timerState.setTime(initialMinutes, initialSeconds)
        }
    }

    return timerState
}