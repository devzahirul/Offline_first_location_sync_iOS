package com.rtls.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Monitors device battery level and emits changes as a Flow.
 * Battery level is normalized to 0.0–1.0.
 */
class BatteryMonitor(private val context: Context) {

    /** Flow of battery level values (0.0–1.0). Emits on each system battery change broadcast. */
    val levels: Flow<Float> = callbackFlow {
        // Emit current level immediately
        val initialLevel = currentBatteryLevel()
        if (initialLevel >= 0f) trySend(initialLevel)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    trySend(level.toFloat() / scale.toFloat())
                }
            }
        }

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        awaitClose { context.unregisterReceiver(receiver) }
    }

    private fun currentBatteryLevel(): Float {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return -1f
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level >= 0) level / 100f else -1f
    }
}
