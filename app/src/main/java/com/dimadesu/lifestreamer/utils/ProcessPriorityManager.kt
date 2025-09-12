package com.dimadesu.lifestreamer.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * Utility class for managing process and service priority to maintain foreground behavior
 */
class ProcessPriorityManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ProcessPriorityManager"
    }
    
    fun boostServicePriority() {
        try {
            // Set highest audio priority for the thread
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            Log.i(TAG, "Thread priority set to URGENT_AUDIO")
            
            // Try to boost process priority
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val runningAppProcesses = activityManager.runningAppProcesses
                val myProcess = runningAppProcesses?.find { it.pid == Process.myPid() }
                
                myProcess?.let { process ->
                    Log.i(TAG, "Current process importance: ${process.importance} (${getImportanceDescription(process.importance)})")
                    if (process.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
                        Log.w(TAG, "Process is not at foreground service importance level - may affect background audio")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to boost service priority", e)
        }
    }
    
    private fun getImportanceDescription(importance: Int): String {
        return when (importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE" 
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "TOP_SLEEPING"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> "CANT_SAVE_STATE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
            else -> "UNKNOWN($importance)"
        }
    }
    
    fun logProcessStatus() {
        try {
            val pid = Process.myPid()
            val uid = Process.myUid()
            
            Log.i(TAG, "Process Status - PID: $pid, UID: $uid")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val runningAppProcesses = activityManager.runningAppProcesses
                val myProcess = runningAppProcesses?.find { it.pid == pid }
                
                myProcess?.let { process ->
                    Log.i(TAG, "Process importance: ${process.importance} (${getImportanceDescription(process.importance)})")
                    Log.i(TAG, "Process name: ${process.processName}")
                    
                    // Check if we're running as foreground service
                    val isForegroundService = process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
                    Log.i(TAG, "Running as foreground service: $isForegroundService")
                    
                    if (!isForegroundService) {
                        Log.w(TAG, "⚠️  NOT running as foreground service - this may cause background audio issues")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log process status", e)
        }
    }
}
