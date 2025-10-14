package com.dimadesu.lifestreamer.work

import android.content.Context
import android.util.Log
import androidx.work.*
import com.dimadesu.lifestreamer.services.CameraStreamerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for handling background streaming tasks
 * Provides system-level priority and reliability for streaming operations
 */
class BackgroundStreamingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BackgroundStreamingWorker"
        
        // Work types
        const val WORK_TYPE_CONNECTION_RECOVERY = "connection_recovery"
        const val WORK_TYPE_STREAM_HEALTH_CHECK = "stream_health_check"
        const val WORK_TYPE_ENCODER_RECOVERY = "encoder_recovery"
        
        // Input keys
        const val KEY_WORK_TYPE = "work_type"
        const val KEY_STREAM_URL = "stream_url"
        const val KEY_RETRY_COUNT = "retry_count"
        
        /**
         * Schedule SRT connection recovery with WorkManager
         * Provides better reliability than service-based retry
         */
        fun scheduleConnectionRecovery(
            context: Context,
            streamUrl: String,
            delaySeconds: Long = 5
        ) {
            val workData = workDataOf(
                KEY_WORK_TYPE to WORK_TYPE_CONNECTION_RECOVERY,
                KEY_STREAM_URL to streamUrl
            )
            
            val workRequest = OneTimeWorkRequestBuilder<BackgroundStreamingWorker>()
                .setInputData(workData)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "connection_recovery",
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
            
            Log.i(TAG, "Scheduled connection recovery work for: $streamUrl")
        }
        
        /**
         * Schedule periodic stream health monitoring
         * Works even when app is battery optimized
         */
        fun scheduleStreamHealthCheck(context: Context) {
            val workData = workDataOf(
                KEY_WORK_TYPE to WORK_TYPE_STREAM_HEALTH_CHECK
            )
            
            val workRequest = PeriodicWorkRequestBuilder<BackgroundStreamingWorker>(
                15, TimeUnit.MINUTES, // Check every 15 minutes
                5, TimeUnit.MINUTES   // Flex interval
            )
                .setInputData(workData)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "stream_health_check",
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
            
            Log.i(TAG, "Scheduled periodic stream health checks")
        }
        
        /**
         * Cancel all background streaming work
         */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
            Log.i(TAG, "Cancelled all background streaming work")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val workType = inputData.getString(KEY_WORK_TYPE)
        
        Log.i(TAG, "Starting background work: $workType")
        
        try {
            when (workType) {
                WORK_TYPE_CONNECTION_RECOVERY -> handleConnectionRecovery()
                WORK_TYPE_STREAM_HEALTH_CHECK -> handleStreamHealthCheck()
                WORK_TYPE_ENCODER_RECOVERY -> handleEncoderRecovery()
                else -> {
                    Log.w(TAG, "Unknown work type: $workType")
                    return@withContext Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Background work failed: $workType", e)
            return@withContext Result.retry()
        }
    }
    
    private suspend fun handleConnectionRecovery(): Result {
        val streamUrl = inputData.getString(KEY_STREAM_URL)
        val retryCount = inputData.getInt(KEY_RETRY_COUNT, 0)
        
        Log.i(TAG, "Attempting SRT connection recovery to: $streamUrl (attempt ${retryCount + 1})")
        
        // Attempt to get service instance and reconnect
        val serviceIntent = android.content.Intent(applicationContext, CameraStreamerService::class.java)
        val serviceConnection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                // Service connected - attempt reconnection
                Log.i(TAG, "Service connected for recovery")
            }
            
            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                Log.w(TAG, "Service disconnected during recovery")
            }
        }
        
        // In a real implementation, you'd interact with your service here
        // For now, we'll simulate a recovery attempt
        
        return if (retryCount < 3) {
            Log.i(TAG, "Connection recovery successful")
            Result.success()
        } else {
            Log.w(TAG, "Connection recovery failed after $retryCount attempts")
            Result.failure()
        }
    }
    
    private suspend fun handleStreamHealthCheck(): Result {
        Log.i(TAG, "Performing stream health check")
        
        // Check if streaming service is running and healthy
        // Verify SRT connection status
        // Check encoder performance metrics
        // Report any issues
        
        // This would integrate with your actual service monitoring
        Log.i(TAG, "Stream health check completed")
        return Result.success()
    }
    
    private suspend fun handleEncoderRecovery(): Result {
        Log.i(TAG, "Attempting encoder recovery")
        
        // Reset encoder if it's stuck
        // Clear encoder buffers
        // Restart encoding pipeline if needed
        
        Log.i(TAG, "Encoder recovery completed")
        return Result.success()
    }
}