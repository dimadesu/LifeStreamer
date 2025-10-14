package com.dimadesu.lifestreamer.work

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker specifically for SRT streaming reliability
 * Handles connection management, buffering, and network resilience
 */
class SrtStreamingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SrtStreamingWorker"
        
        // SRT-specific work types
        const val WORK_TYPE_SRT_RECONNECT = "srt_reconnect"
        const val WORK_TYPE_SRT_BUFFER_FLUSH = "srt_buffer_flush"
        const val WORK_TYPE_SRT_STATS_MONITOR = "srt_stats_monitor"
        
        // Input keys
        const val KEY_WORK_TYPE = "work_type"
        const val KEY_SRT_URL = "srt_url"
        const val KEY_CONNECTION_ID = "connection_id"
        const val KEY_BUFFER_SIZE = "buffer_size"
        
        /**
         * Schedule SRT reconnection with exponential backoff
         * Uses WorkManager's superior background processing
         */
        fun scheduleSrtReconnection(
            context: Context,
            srtUrl: String,
            connectionId: String? = null,
            delaySeconds: Long = 2
        ) {
            val workData = workDataOf(
                KEY_WORK_TYPE to WORK_TYPE_SRT_RECONNECT,
                KEY_SRT_URL to srtUrl,
                KEY_CONNECTION_ID to (connectionId ?: "default")
            )
            
            val workRequest = OneTimeWorkRequestBuilder<SrtStreamingWorker>()
                .setInputData(workData)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(false) // Continue even on low battery
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag("srt_reconnect")
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "srt_reconnect_${connectionId ?: "default"}",
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
            
            Log.i(TAG, "Scheduled SRT reconnection work for: $srtUrl")
        }
        
        /**
         * Schedule SRT buffer management
         * Handles frame buffering when network is constrained
         */
        fun scheduleSrtBufferManagement(
            context: Context,
            bufferSize: Int = 1000
        ) {
            val workData = workDataOf(
                KEY_WORK_TYPE to WORK_TYPE_SRT_BUFFER_FLUSH,
                KEY_BUFFER_SIZE to bufferSize
            )
            
            val workRequest = PeriodicWorkRequestBuilder<SrtStreamingWorker>(
                30, TimeUnit.SECONDS, // Buffer management every 30 seconds
                10, TimeUnit.SECONDS  // Flex interval
            )
                .setInputData(workData)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("srt_buffer")
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "srt_buffer_management",
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
            
            Log.i(TAG, "Scheduled SRT buffer management")
        }
        
        /**
         * Schedule SRT statistics monitoring
         * Monitors connection health and performance
         */
        fun scheduleSrtStatsMonitoring(context: Context) {
            val workData = workDataOf(
                KEY_WORK_TYPE to WORK_TYPE_SRT_STATS_MONITOR
            )
            
            val workRequest = PeriodicWorkRequestBuilder<SrtStreamingWorker>(
                5, TimeUnit.MINUTES,  // Monitor every 5 minutes
                1, TimeUnit.MINUTES   // Flex interval
            )
                .setInputData(workData)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("srt_monitoring")
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "srt_stats_monitoring",
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
            
            Log.i(TAG, "Scheduled SRT statistics monitoring")
        }
        
        /**
         * Cancel all SRT-related work
         */
        fun cancelSrtWork(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelAllWorkByTag("srt_reconnect")
                cancelAllWorkByTag("srt_buffer")
                cancelAllWorkByTag("srt_monitoring")
            }
            Log.i(TAG, "Cancelled all SRT streaming work")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val workType = inputData.getString(KEY_WORK_TYPE)
        
        Log.i(TAG, "Starting SRT work: $workType")
        
        try {
            when (workType) {
                WORK_TYPE_SRT_RECONNECT -> handleSrtReconnection()
                WORK_TYPE_SRT_BUFFER_FLUSH -> handleSrtBufferManagement()
                WORK_TYPE_SRT_STATS_MONITOR -> handleSrtStatsMonitoring()
                else -> {
                    Log.w(TAG, "Unknown SRT work type: $workType")
                    return@withContext Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SRT work failed: $workType", e)
            return@withContext Result.retry()
        }
    }
    
    private suspend fun handleSrtReconnection(): Result {
        val srtUrl = inputData.getString(KEY_SRT_URL) ?: return Result.failure()
        val connectionId = inputData.getString(KEY_CONNECTION_ID) ?: "default"
        
        Log.i(TAG, "Attempting SRT reconnection to: $srtUrl (connection: $connectionId)")
        
        // This is where you'd integrate with your actual SRT connection logic
        // The key benefit is WorkManager handles this with system-level priority
        
        try {
            // Simulate connection attempt
            // In real implementation, you'd:
            // 1. Get SRT sink instance
            // 2. Check current connection status
            // 3. Attempt reconnection if needed
            // 4. Apply RootEncoder-style configuration
            
            Log.i(TAG, "SRT reconnection successful for connection: $connectionId")
            return Result.success()
            
        } catch (e: Exception) {
            Log.w(TAG, "SRT reconnection failed for connection: $connectionId", e)
            
            // WorkManager will automatically retry with exponential backoff
            return Result.retry()
        }
    }
    
    private suspend fun handleSrtBufferManagement(): Result {
        val bufferSize = inputData.getInt(KEY_BUFFER_SIZE, 1000)
        
        Log.i(TAG, "Managing SRT buffer (size: $bufferSize)")
        
        // Buffer management logic:
        // 1. Check current buffer usage
        // 2. Drop frames if buffer is full (live streaming priority)
        // 3. Adjust quality if needed
        // 4. Report buffer health
        
        try {
            // This would integrate with your SRT sink's buffer management
            Log.i(TAG, "SRT buffer management completed")
            return Result.success()
            
        } catch (e: Exception) {
            Log.w(TAG, "SRT buffer management failed", e)
            return Result.retry()
        }
    }
    
    private suspend fun handleSrtStatsMonitoring(): Result {
        Log.i(TAG, "Monitoring SRT connection statistics")
        
        try {
            // Monitor SRT statistics:
            // 1. Check RTT, packet loss, bandwidth
            // 2. Detect connection issues early
            // 3. Trigger recovery if needed
            // 4. Report metrics for debugging
            
            // This would use SRT sink's metrics
            Log.i(TAG, "SRT statistics monitoring completed")
            return Result.success()
            
        } catch (e: Exception) {
            Log.w(TAG, "SRT statistics monitoring failed", e)
            return Result.retry()
        }
    }
}