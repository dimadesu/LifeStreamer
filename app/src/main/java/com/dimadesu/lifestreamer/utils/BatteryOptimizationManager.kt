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

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.result.ActivityResultLauncher

class BatteryOptimizationManager(private val context: Context) {
    
    companion object {
        private const val TAG = "BatteryOptimizationManager"
        private const val PREFS_NAME = "background_audio_prefs"
        private const val KEY_BATTERY_OPTIMIZATION_PROMPTED = "battery_optimization_prompted"
    }
    
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Check if the app is whitelisted from battery optimization
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true // No battery optimization on pre-M devices
        }
    }
    
    /**
     * Check if we should prompt the user about battery optimization
     */
    fun shouldPromptForBatteryOptimization(): Boolean {
        return !isIgnoringBatteryOptimizations() && 
               !prefs.getBoolean(KEY_BATTERY_OPTIMIZATION_PROMPTED, false)
    }
    
    /**
     * Mark that we've already prompted the user
     */
    fun markBatteryOptimizationPrompted() {
        prefs.edit()
            .putBoolean(KEY_BATTERY_OPTIMIZATION_PROMPTED, true)
            .apply()
    }
    
    /**
     * Main method to prompt for battery optimization if needed
     * Always calls either onAccept or onDecline callback
     */
    fun promptForBatteryOptimization(onAccept: () -> Unit, onDecline: () -> Unit) {
        if (shouldPromptForBatteryOptimization()) {
            // Show dialog and mark as prompted
            markBatteryOptimizationPrompted()
            
            val deviceBrand = Build.MANUFACTURER.lowercase()
            val deviceInstructions = getDeviceSpecificInstructions(deviceBrand)
            
            AlertDialog.Builder(context)
                .setTitle("Enable Background Streaming")
                .setMessage("""
                    To ensure reliable background audio streaming, please disable battery optimization for this app.
                    
                    ${deviceInstructions}
                    
                    Without this setting, Android may stop audio recording when you switch apps or turn off the screen, even though video will continue streaming normally.
                    
                    Would you like to open the battery optimization settings?
                """.trimIndent())
                .setPositiveButton("Open Settings") { _, _ ->
                    openBatteryOptimizationSettings()
                    onAccept()
                }
                .setNegativeButton("Later") { dialog, _ ->
                    dialog.dismiss()
                    Log.i(TAG, "User chose to configure battery optimization later")
                    onDecline()
                }
                .setNeutralButton("More Help") { _, _ ->
                    showDetailedInstructions(deviceBrand)
                    onDecline() // Continue with streaming after showing help
                }
                .setCancelable(false)
                .show()
        } else {
            // No need to prompt, continue with streaming
            onDecline()
        }
    }
    
    /**
     * Open the battery optimization settings for this app
     */
    fun openBatteryOptimizationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:${context.packageName}")
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open battery optimization settings", e)
            // Fallback to general battery optimization settings
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.w(TAG, "Failed to open general battery optimization settings", e2)
            }
        }
    }
    
    /**
     * Reset the prompted flag (for testing or user request)
     */
    fun resetBatteryOptimizationPrompt() {
        prefs.edit()
            .putBoolean(KEY_BATTERY_OPTIMIZATION_PROMPTED, false)
            .apply()
    }
    
    private fun getDeviceSpecificInstructions(manufacturer: String): String {
        return when {
            manufacturer.contains("samsung") -> "Samsung: Settings > Apps > Special Access > Optimize battery usage > Turn off for this app"
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> "Huawei/Honor: Settings > Apps > Advanced > Ignore battery optimizations > Turn on for this app"
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> "Xiaomi/MIUI: Settings > Apps > Manage apps > [App name] > Battery saver > No restrictions"
            manufacturer.contains("oppo") || manufacturer.contains("oneplus") -> "OPPO/OnePlus: Settings > Apps & notifications > Special app access > Battery optimization > Turn off"
            manufacturer.contains("vivo") -> "Vivo: Settings > Apps & permissions > App battery consumption > Turn off for this app"
            manufacturer.contains("realme") -> "Realme: Settings > Apps & notifications > Special access > Battery optimization > Not optimized"
            manufacturer.contains("sony") -> "Sony: Settings > Apps & notifications > Special app access > Battery optimization > Turn off"
            manufacturer.contains("lg") -> "LG: Settings > Apps & notifications > Special access > Battery optimization > Turn off"
            manufacturer.contains("motorola") -> "Motorola: Settings > Apps & notifications > Special app access > Battery optimization > Turn off"
            else -> "Steps: Settings > Apps > Special Access > Battery Optimization > Turn off for this app"
        }
    }
    
    private fun showDetailedInstructions(manufacturer: String) {
        val detailedSteps = getDetailedInstructions(manufacturer)
        
        AlertDialog.Builder(context)
            .setTitle("Detailed Setup Instructions")
            .setMessage(detailedSteps)
            .setPositiveButton("Got It") { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton("Open Settings") { _, _ ->
                openBatteryOptimizationSettings()
            }
            .show()
    }
    
    private fun getDetailedInstructions(manufacturer: String): String {
        return when {
            manufacturer.contains("samsung") -> """
                Samsung Galaxy Instructions:
                1. Open Settings app
                2. Go to "Apps" or "Application Manager"
                3. Tap "Special Access" or menu (⋮) > "Special access"
                4. Select "Optimize battery usage"
                5. Change filter from "Apps not optimized" to "All apps"
                6. Find "StreamPack Camera" and turn OFF optimization
                7. Confirm the change
                
                Additional: Also check "Auto-start manager" and enable auto-start for this app.
            """.trimIndent()
            
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> """
                Huawei/Honor Instructions:
                1. Open Settings app
                2. Go to "Apps & services" > "Apps"
                3. Find and tap "StreamPack Camera"
                4. Tap "Battery" section
                5. Enable "Manual management"
                6. Turn ON all toggles (Auto-launch, Secondary launch, Run in background)
                7. Go back and select "App launch"
                8. Set app to "Manage manually" and enable all three options
                
                Also check: Protected apps list and add this app to it.
            """.trimIndent()
            
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> """
                Xiaomi/MIUI Instructions:
                1. Open Settings app
                2. Go to "Apps" > "Manage apps"
                3. Find and tap "StreamPack Camera"
                4. Select "Battery saver"
                5. Choose "No restrictions"
                6. Go to "Permissions" and ensure Microphone is always allowed
                7. In Security app > "Permissions" > "Autostart" > Enable for this app
                
                Important: Also disable MIUI optimization in Developer options if available.
            """.trimIndent()
            
            else -> """
                General Android Instructions:
                1. Open Settings app
                2. Go to "Apps" or "Apps & notifications"
                3. Find "Special access" or "Special app access"
                4. Select "Battery optimization" or "Battery optimization"
                5. Change view to "All apps" if needed
                6. Find "StreamPack Camera"
                7. Select "Don't optimize" or turn OFF optimization
                8. Confirm the change
                
                Note: Menu names may vary by Android version and manufacturer.
            """.trimIndent()
        }
    }
}
