package com.rtls.kmp

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import java.io.File

object RTLSKmp {

    fun createLocationSyncClient(
        context: Context,
        baseUrl: String,
        userId: String,
        deviceId: String,
        accessToken: String,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        batchingPolicy: BatchingPolicy = BatchingPolicy(),
        retryPolicy: SyncRetryPolicy = SyncRetryPolicy.Default,
        retentionPolicy: RetentionPolicy = RetentionPolicy.Recommended,
        networkMonitor: NetworkMonitor? = AndroidNetworkMonitor(context)
    ): LocationSyncClient {
        val dbDir = File(context.filesDir, "rtls_kmp").apply { mkdirs() }
        val dbPath = File(dbDir, "rtlsync.db").absolutePath
        val store = SqliteLocationStore(dbPath)
        val tokenProvider = AuthTokenProvider { accessToken }
        val api = OkHttpLocationSyncAPI(baseUrl.trimEnd('/'), tokenProvider)
        val syncEngine = SyncEngine(
            store = store,
            api = api,
            batching = batchingPolicy,
            retryPolicy = retryPolicy,
            retentionPolicy = retentionPolicy,
            networkMonitor = networkMonitor,
            scope = scope
        )
        return LocationSyncClient(store, syncEngine, userId, deviceId, scope)
    }

    fun createLocationFlow(
        context: Context,
        userId: String,
        deviceId: String,
        params: LocationRequestParams = LocationRequestParams()
    ): Flow<LocationPoint> {
        val provider = AndroidLocationProvider(context)
        return provider.locationFlow(userId, deviceId, params)
    }
}
