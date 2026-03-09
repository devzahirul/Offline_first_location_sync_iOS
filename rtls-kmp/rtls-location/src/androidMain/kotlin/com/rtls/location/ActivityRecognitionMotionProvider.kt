package com.rtls.location

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Motion state for adaptive tracking policies.
 * Maps to iOS MotionState enum.
 */
enum class MotionState {
    STATIONARY, WALKING, RUNNING, DRIVING, UNKNOWN
}

/**
 * Provides motion state changes using Google Activity Recognition API.
 * Applies 30-second hysteresis to prevent rapid state flapping.
 *
 * Requires `com.google.android.gms.permission.ACTIVITY_RECOGNITION` permission (API 29+).
 */
class ActivityRecognitionMotionProvider(private val context: Context) {

    companion object {
        private const val ACTION_TRANSITION = "com.rtls.location.ACTIVITY_TRANSITION"
        private const val HYSTERESIS_MS = 30_000L
    }

    /**
     * Flow of motion state changes with 30s hysteresis.
     */
    val motionStates: Flow<MotionState> = callbackFlow {
        var lastEmittedState = MotionState.UNKNOWN
        var candidateState = MotionState.UNKNOWN
        var candidateSinceMs: Long? = null

        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.WALKING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.RUNNING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.ON_BICYCLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        )

        val request = ActivityTransitionRequest(transitions)

        val intent = Intent(ACTION_TRANSITION)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!ActivityTransitionResult.hasResult(intent)) return
                val result = ActivityTransitionResult.extractResult(intent) ?: return

                for (event in result.transitionEvents) {
                    if (event.transitionType != ActivityTransition.ACTIVITY_TRANSITION_ENTER) continue
                    val newState = mapActivityType(event.activityType)

                    if (newState == lastEmittedState) {
                        candidateState = newState
                        candidateSinceMs = null
                        continue
                    }

                    val now = System.currentTimeMillis()
                    if (newState == candidateState) {
                        val since = candidateSinceMs
                        if (since != null && now - since >= HYSTERESIS_MS) {
                            lastEmittedState = newState
                            candidateSinceMs = null
                            trySend(newState)
                        }
                    } else {
                        candidateState = newState
                        candidateSinceMs = now
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(ACTION_TRANSITION), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(ACTION_TRANSITION))
        }

        try {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(request, pendingIntent)
        } catch (_: SecurityException) {
            // Permission not granted
        }

        awaitClose {
            try {
                ActivityRecognition.getClient(context)
                    .removeActivityTransitionUpdates(pendingIntent)
            } catch (_: Exception) {}
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    private fun mapActivityType(type: Int): MotionState = when (type) {
        DetectedActivity.STILL -> MotionState.STATIONARY
        DetectedActivity.WALKING -> MotionState.WALKING
        DetectedActivity.RUNNING -> MotionState.RUNNING
        DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE -> MotionState.DRIVING
        else -> MotionState.UNKNOWN
    }
}
