/*
 * Copyright (C) 2021 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dimadesu.lifestreamer.utils

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.app.ActivityCompat

class AudioPermissionChecker(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioPermissionChecker"
    }
    
    /**
     * Check if RECORD_AUDIO permission is granted at the manifest level
     */
    fun hasRecordAudioPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context, 
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if RECORD_AUDIO is allowed at the AppOps level (runtime)
     */
    fun hasRecordAudioAppOp(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val mode = appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_RECORD_AUDIO,
                    Process.myUid(),
                    context.packageName
                )
                mode == AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check RECORD_AUDIO AppOp", e)
                false
            }
        } else {
            true // Pre-M devices don't have AppOps restrictions
        }
    }
    
    /**
     * Get detailed status of audio permissions for debugging
     */
    fun getDetailedAudioPermissionStatus(): String {
        val manifestPermission = hasRecordAudioPermission()
        val appOpPermission = hasRecordAudioAppOp()
        val uid = Process.myUid()
        val packageName = context.packageName
        
        return buildString {
            appendLine("Audio Permission Status:")
            appendLine("  Manifest RECORD_AUDIO: $manifestPermission")
            appendLine("  AppOp RECORD_AUDIO: $appOpPermission")
            appendLine("  UID: $uid")
            appendLine("  Package: $packageName")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                    val mode = appOpsManager.checkOpNoThrow(
                        AppOpsManager.OPSTR_RECORD_AUDIO,
                        uid,
                        packageName
                    )
                    appendLine("  AppOp Mode: ${appOpModeToString(mode)}")
                } catch (e: Exception) {
                    appendLine("  AppOp Mode: Failed to check - ${e.message}")
                }
            }
        }
    }
    
    private fun appOpModeToString(mode: Int): String {
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> "MODE_ALLOWED"
            AppOpsManager.MODE_IGNORED -> "MODE_IGNORED"
            AppOpsManager.MODE_ERRORED -> "MODE_ERRORED"
            AppOpsManager.MODE_DEFAULT -> "MODE_DEFAULT"
            else -> "UNKNOWN($mode)"
        }
    }
    
    /**
     * Log comprehensive audio permission status
     */
    fun logAudioPermissionStatus() {
        Log.i(TAG, getDetailedAudioPermissionStatus())
    }
}