package com.example.smartcook.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.smartcook.MainActivity
import com.example.smartcook.R
import com.example.smartcook.data.RecipeDatabase
import com.example.smartcook.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// BroadcastReceiver that listens for device boot completion and checks for interrupted cooking sessions and notifies the user
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val CHANNEL_ID = "session_recovery_channel"
        private const val NOTIFICATION_ID = 9999

        const val ACTION_DISMISS_SESSION = "com.example.smartcook.DISMISS_SESSION"
        const val ACTION_DISMISS_MULTI_COOK_GROUP = "com.example.smartcook.DISMISS_MULTI_COOK_GROUP"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_GROUP_ID = "group_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Device boot completed, checking for interrupted sessions")
                handleBootCompleted(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "App updated, checking for interrupted sessions")
                handleBootCompleted(context)
            }
            ACTION_DISMISS_SESSION -> {
                val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1)
                if (sessionId != -1L) {
                    dismissSession(context, sessionId)
                }
            }
            ACTION_DISMISS_MULTI_COOK_GROUP -> {
                val groupId = intent.getStringExtra(EXTRA_GROUP_ID)
                if (groupId != null) {
                    dismissMultiCookGroup(context, groupId)
                }
            }
        }
    }

    private fun handleBootCompleted(context: Context) {
        // Use a coroutine to handle database operations
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                val database = RecipeDatabase.getDatabase(context, scope)
                val sessionManager = SessionManager(context, database.cookingSessionDao())

                // Mark all active sessions as interrupted
                sessionManager.markAllSessionsAsInterrupted()

                // Check for interrupted sessions
                val interruptedSessions = sessionManager.checkForInterruptedSessions()

                if (interruptedSessions.isNotEmpty()) {
                    Log.d(TAG, "Found ${interruptedSessions.size} interrupted session(s)")

                    // Group sessions by multi-cook group ID
                    val multiCookGroups = mutableMapOf<String, MutableList<com.example.smartcook.data.SessionRecoveryInfo>>()
                    val singleRecipeSessions = mutableListOf<com.example.smartcook.data.SessionRecoveryInfo>()

                    interruptedSessions.forEach { recoveryInfo ->
                        val session = recoveryInfo.session
                        if (session.isMultiCookMode && session.multiCookGroupId != null) {
                            // Multi-cook session group it
                            multiCookGroups.getOrPut(session.multiCookGroupId) { mutableListOf() }.add(recoveryInfo)
                        } else {
                            // Single recipe session
                            singleRecipeSessions.add(recoveryInfo)
                        }
                    }

                    // Show one notification per multi-cook group
                    multiCookGroups.forEach { (groupId, sessions) ->
                        showMultiCookRecoveryNotification(context, groupId, sessions)
                    }

                    // Show individual notifications for single recipe sessions
                    singleRecipeSessions.forEach { recoveryInfo ->
                        showRecoveryNotification(context, recoveryInfo, sessionManager)
                    }
                } else {
                    Log.d(TAG, "No interrupted sessions found")
                }

                // Clean up old sessions
                sessionManager.cleanupOldSessions()

            } catch (e: Exception) {
                Log.e(TAG, "Error checking for interrupted sessions", e)
            }
        }
    }

    private fun showRecoveryNotification(
        context: Context,
        recoveryInfo: com.example.smartcook.data.SessionRecoveryInfo,
        sessionManager: SessionManager
    ) {
        createNotificationChannel(context)

        val session = recoveryInfo.session

        // Create intent to open the app and resume the session
        val resumeIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // Include all session data for proper routing
            if (session.isMultiCookMode && session.multiCookGroupId != null) {
                putExtra("multi_cook_group_id", session.multiCookGroupId)
                putExtra("is_multi_cook_mode", true)
                putExtra("resume_session_id", session.sessionId)
                putExtra("recipe_id", session.recipeId)
            } else {
                putExtra("resume_session_id", session.sessionId)
                putExtra("recipe_id", session.recipeId)
                putExtra("is_multi_cook_mode", false)
            }
        }

        val resumePendingIntent = PendingIntent.getActivity(
            context,
            session.sessionId.toInt(),
            resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create intent to dismiss the session
        val dismissIntent = Intent(context, BootReceiver::class.java).apply {
            action = ACTION_DISMISS_SESSION
            putExtra(EXTRA_SESSION_ID, session.sessionId)
        }

        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            session.sessionId.toInt() + 10000,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build notification message
        val elapsedTimeText = sessionManager.formatElapsedTime(recoveryInfo.elapsedTimeSinceLastUpdate)
        val stepInfo = "Step ${session.currentStepIndex + 1} of ${session.totalSteps}"

        val notificationText = when {
            recoveryInfo.isTimerExpired -> {
                "Your timer expired while your device was off. ($elapsedTimeText)"
            }
            session.isTimerActive && !session.timerPaused -> {
                val remainingTime = sessionManager.formatTimerDuration(recoveryInfo.recalculatedTimerSeconds)
                "Timer adjusted to $remainingTime based on elapsed time. ($elapsedTimeText)"
            }
            session.timerPaused -> {
                "You had a paused timer. Ready to continue?"
            }
            else -> {
                "You were at: ${session.currentStepInstruction}"
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("⏰ Interrupted Cooking Session")
            .setContentText("${session.recipeName} - $stepInfo")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Recipe: ${session.recipeName}\n$stepInfo\n\n$notificationText")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(resumePendingIntent)
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Resume",
                resumePendingIntent
            )
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Dismiss",
                dismissPendingIntent
            )
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID + session.sessionId.toInt(), notification)

        Log.d(TAG, "Showed recovery notification for session ${session.sessionId}")
    }

    private fun showMultiCookRecoveryNotification(
        context: Context,
        groupId: String,
        sessions: List<com.example.smartcook.data.SessionRecoveryInfo>
    ) {
        createNotificationChannel(context)

        if (sessions.isEmpty()) return

        // Create intent to open the app and resume the multi-cook session
        val resumeIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // Pass multi-cook group ID to restore the entire multi-cook session
            putExtra("multi_cook_group_id", groupId)
            putExtra("is_multi_cook_mode", true)
            // Also include first session info as fallback
            putExtra("resume_session_id", sessions.first().session.sessionId)
            putExtra("recipe_id", sessions.first().session.recipeId)
        }

        val resumePendingIntent = PendingIntent.getActivity(
            context,
            groupId.hashCode(), // Use group ID hash as unique request code
            resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create intent to dismiss all sessions in the group
        val dismissIntent = Intent(context, BootReceiver::class.java).apply {
            action = ACTION_DISMISS_MULTI_COOK_GROUP
            putExtra(EXTRA_GROUP_ID, groupId)
        }

        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            groupId.hashCode() + 10000,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build notification content
        val recipeNames = sessions.joinToString("\n") { "• ${it.session.recipeName}" }
        val notificationTitle = "⏰ Interrupted Multi-Cook Session"
        val notificationSummary = "${sessions.size} recipes were interrupted"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(notificationTitle)
            .setContentText(notificationSummary)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$notificationSummary:\n\n$recipeNames\n\nTap to resume all recipes in multi-cook mode.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(resumePendingIntent)
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Resume All",
                resumePendingIntent
            )
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Dismiss",
                dismissPendingIntent
            )
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID + groupId.hashCode(), notification)

        Log.d(TAG, "Showed multi-cook recovery notification for group $groupId with ${sessions.size} recipes")
    }

    private fun dismissSession(context: Context, sessionId: Long) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                val database = RecipeDatabase.getDatabase(context, scope)
                val sessionManager = SessionManager(context, database.cookingSessionDao())

                sessionManager.deleteSession(sessionId)

                // Cancel the notification
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NOTIFICATION_ID + sessionId.toInt())

                Log.d(TAG, "Dismissed session $sessionId")
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing session", e)
            }
        }
    }

    private fun dismissMultiCookGroup(context: Context, groupId: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                val database = RecipeDatabase.getDatabase(context, scope)
                val sessionManager = SessionManager(context, database.cookingSessionDao())

                // Delete all sessions in the group
                sessionManager.deleteSessionGroup(groupId)

                // Cancel the notification
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NOTIFICATION_ID + groupId.hashCode())

                Log.d(TAG, "Dismissed multi-cook group $groupId")
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing multi-cook group", e)
            }
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Session Recovery",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for recovering interrupted cooking sessions"
                enableVibration(true)
                setShowBadge(true)
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
