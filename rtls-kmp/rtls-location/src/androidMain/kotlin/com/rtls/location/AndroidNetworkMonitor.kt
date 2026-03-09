package com.rtls.location

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.rtls.core.ConnectionType
import com.rtls.core.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

class AndroidNetworkMonitor(private val context: Context) : NetworkMonitor {

    override suspend fun isOnline(): Boolean = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@withContext false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return@withContext false
            val caps = cm.getNetworkCapabilities(network) ?: return@withContext false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    override suspend fun connectionType(): ConnectionType = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@withContext ConnectionType.UNKNOWN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return@withContext ConnectionType.UNKNOWN
            val caps = cm.getNetworkCapabilities(network) ?: return@withContext ConnectionType.UNKNOWN
            when {
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> ConnectionType.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.CELLULAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
                else -> ConnectionType.UNKNOWN
            }
        } else {
            ConnectionType.UNKNOWN
        }
    }

    override val onlineFlow: Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(true) }
            override fun onLost(network: Network) { trySend(false) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(callback)
        } else {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        }
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }
}
