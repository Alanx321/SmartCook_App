package com.example.smartcook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class TimerNotificationManager(private val context: Context) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    companion object {
        const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "timer_channel"
        private const val CHANNEL_NAME = "Cooking Timer"
        private const val ALARM_CHANNEL_ID = "timer_alarm_channel"
        private const val ALARM_CHANNEL_NAME = "Timer Alarms"

        const val ACTION_PAUSE = "com.example.smartcook.PAUSE"
        const val ACTION_RESUME = "com.example.smartcook.RESUME"
        const val ACTION_STOP = "com.example.smartcook.STOP"
        const val ACTION_STOP_SOUND = "com.example.smartcook.STOP_SOUND"
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Regular timer channel
            val timerChannel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows cooking timer status"
                setSound(null, null)
                enableVibration(false)
            }

            // Alarm channel
            val alarmChannel = NotificationChannel(
                ALARM_CHANNEL_ID,
                ALARM_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Timer finished alarms"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                setBypassDnd(true)
            }

            notificationManager.createNotificationChannel(timerChannel)
            notificationManager.createNotificationChannel(alarmChannel)
        }
    }

    fun createTimerNotification(
        recipeName: String,
        currentStep: String,
        timeInSeconds: Int,
        initialTimeInSeconds: Int,
        isRunning: Boolean,
        isPaused: Boolean,
        isFinished: Boolean,
        soundStopped: Boolean,
        sessionId: Long = -1L,
        recipeId: Long = -1L,
        isMultiCookMode: Boolean = false,
        multiCookGroupId: String? = null
    ): Notification {
        val notificationIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // Add session restoration data
            if (isMultiCookMode && multiCookGroupId != null) {
                // For multi-cook, prioritize group ID
                putExtra("multi_cook_group_id", multiCookGroupId)
                putExtra("is_multi_cook_mode", true)
                // Still include session/recipe ID but they won't be used for routing
                putExtra("resume_session_id", sessionId)
                putExtra("recipe_id", recipeId)
            } else if (sessionId != -1L && recipeId != -1L) {
                // Single recipe mode
                putExtra("resume_session_id", sessionId)
                putExtra("recipe_id", recipeId)
                putExtra("is_multi_cook_mode", false)
            }
        }

        // Use unique request code for multi-cook to prevent intent extras collision
        val requestCode = if (isMultiCookMode && multiCookGroupId != null) {
            multiCookGroupId.hashCode()
        } else {
            sessionId.toInt()
        }

        val pendingIntent = PendingIntent.getActivity(
            context, requestCode, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = PendingIntent.getService(
            context, 1,
            Intent(context, TimerService::class.java).apply { action = ACTION_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resumeIntent = PendingIntent.getService(
            context, 2,
            Intent(context, TimerService::class.java).apply { action = ACTION_RESUME },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            context, 3,
            Intent(context, TimerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopSoundIntent = PendingIntent.getService(
            context, 4,
            Intent(context, TimerService::class.java).apply { action = ACTION_STOP_SOUND },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = getNotificationTitle(recipeName, isFinished, soundStopped, isPaused, timeInSeconds)
        val message = getContextAwareMessage(
            timeInSeconds,
            recipeName,
            isFinished,
            soundStopped,
            isPaused
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentIntent(pendingIntent)
            .setOngoing(!isFinished || !soundStopped)
            .setPriority(if (isFinished && !soundStopped) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("${message}\n\n$currentStep"))
            .setShowWhen(true)
            .setUsesChronometer(isRunning && !isPaused)
            .setWhen(System.currentTimeMillis() - ((initialTimeInSeconds - timeInSeconds) * 1000L))

        // Add appropriate action buttons based on timer state
        when {
            isFinished && !soundStopped -> {
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop Sound",
                    stopSoundIntent
                )
            }
            isFinished && soundStopped -> {
                // No actions needed when sound is stopped
            }
            isPaused -> {
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    "Resume",
                    resumeIntent
                )
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    stopIntent
                )
            }
            isRunning -> {
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "Pause",
                    pauseIntent
                )
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    stopIntent
                )
            }
        }

        return builder.build()
    }

    fun showAlarmNotification(
        recipeName: String,
        currentStep: String,
        sessionId: Long = -1L,
        recipeId: Long = -1L,
        isMultiCookMode: Boolean = false,
        multiCookGroupId: String? = null
    ) {
        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // Add session restoration data
            if (isMultiCookMode && multiCookGroupId != null) {
                // For multi-cook, prioritize group ID
                putExtra("multi_cook_group_id", multiCookGroupId)
                putExtra("is_multi_cook_mode", true)
                // Still include session/recipe ID but they won't be used for routing
                putExtra("resume_session_id", sessionId)
                putExtra("recipe_id", recipeId)
            } else if (sessionId != -1L && recipeId != -1L) {
                // Single recipe mode
                putExtra("resume_session_id", sessionId)
                putExtra("recipe_id", recipeId)
                putExtra("is_multi_cook_mode", false)
            }
        }

        // Use unique request code for multi-cook to prevent intent extras collision
        val requestCode = if (isMultiCookMode && multiCookGroupId != null) {
            multiCookGroupId.hashCode()
        } else {
            sessionId.toInt()
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, requestCode, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopSoundIntent = PendingIntent.getService(
            context, 4,
            Intent(context, TimerService::class.java).apply { action = ACTION_STOP_SOUND },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmNotification = NotificationCompat.Builder(context, ALARM_CHANNEL_ID)
            .setContentTitle("⏰ ${recipeName} - TIMER FINISHED!")
            .setContentText(currentStep)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .setVibrate(longArrayOf(0, 1000, 500, 1000, 500, 1000))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("${currentStep}\n\nTap to open app or stop the alarm."))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Alarm",
                stopSoundIntent
            )
            .build()

        notificationManager.notify(NOTIFICATION_ID, alarmNotification)
    }

    fun updateNotification(notification: Notification) {
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun getFormattedTime(timeInSeconds: Int): String {
        val minutes = timeInSeconds / 60
        val seconds = timeInSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun getContextAwareMessage(
        timeInSeconds: Int,
        recipeName: String,
        isFinished: Boolean,
        soundStopped: Boolean,
        isPaused: Boolean
    ): String {
        return when {
            isFinished && !soundStopped -> "⏰ Timer finished! Time to check your ${recipeName}!"
            isFinished && soundStopped -> "✅ Alarm stopped - Timer completed"
            isPaused -> "⏸️ Timer paused at ${getFormattedTime(timeInSeconds)}"
            timeInSeconds <= 10 -> "🔥 Almost done! ${getFormattedTime(timeInSeconds)} remaining"
            timeInSeconds <= 30 -> "⚠️ Less than 30 seconds left!"
            timeInSeconds <= 60 -> "⏱️ One minute remaining"
            else -> "⏳ ${getFormattedTime(timeInSeconds)} remaining"
        }
    }

    private fun getNotificationTitle(
        recipeName: String,
        isFinished: Boolean,
        soundStopped: Boolean,
        isPaused: Boolean,
        timeInSeconds: Int
    ): String {
        return when {
            isFinished && !soundStopped -> "⏰ ${recipeName} - Timer Finished!"
            isFinished && soundStopped -> "✅ ${recipeName} - Completed"
            isPaused -> "⏸️ ${recipeName} - Paused"
            timeInSeconds <= 60 -> "⚠️ ${recipeName} - Hurry!"
            else -> "⏳ ${recipeName}"
        }
    }
}