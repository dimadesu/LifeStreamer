/*
 * Copyright (C) 2022 Thibault B.
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
package com.dimadesu.lifestreamer.services.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dimadesu.lifestreamer.ui.main.MainActivity

/**
 * Helper class to create and manage notifications.
 * Only use for screen recording service that is why the permission error is suppressed.
 */
@Suppress("NotificationPermission")
class NotificationUtils(
    private val service: Service,
    private val channelId: String,
    private val notificationId: Int
) {
    private val notificationManager: NotificationManager by lazy {
        service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    fun cancel() {
        notificationManager.cancel(notificationId)
    }

    fun notify(notification: Notification) {
        notificationManager.notify(notificationId, notification)
    }

    fun createNotification(
        @StringRes titleResourceId: Int,
        @StringRes contentResourceId: Int,
        @DrawableRes iconResourceId: Int
    ): Notification {
        return createNotification(
            service.getString(titleResourceId),
            if (contentResourceId != 0) {
                service.getString(contentResourceId)
            } else {
                null
            },
            iconResourceId
        )
    }

    fun createNotification(
        title: String,
        content: String? = null,
        @DrawableRes iconResourceId: Int,
        isForgroundService: Boolean = false
    ): Notification {
        // Create a broadcast intent so the service receives the tap and can
        // decide whether to open the activity (keeps handling consistent with
        // other notification actions that are delivered to the service).
        val intent = Intent("com.dimadesu.lifestreamer.ACTION_OPEN_FROM_NOTIFICATION").apply {
            setPackage(service.packageName)
        }

        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(service, 0, intent, pendingIntentFlags)
        
        val builder = NotificationCompat.Builder(service, channelId).apply {
            setSmallIcon(iconResourceId)
            setContentTitle(title)
            setContentIntent(pendingIntent) // This makes the notification tappable
            
            content?.let {
                setContentText(it)
            }
            
            // Enhanced attributes for foreground service priority
            if (isForgroundService) {
                priority = NotificationCompat.PRIORITY_HIGH
                setOngoing(true) // Prevents user dismissal
                setAutoCancel(false) // Notification stays until explicitly removed
                setShowWhen(true)
                setUsesChronometer(true) // Shows elapsed time
                setCategory(NotificationCompat.CATEGORY_SERVICE)
                
                // For Android 8.0+, ensure foreground service behavior
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setChannelId(channelId)
                    // Add foreground service badge
                    setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
                }

                // Foreground service attributes (visual/priority). Sound/vibration
                // are cleared below globally to ensure silence across platforms.
                
                // Prevent system from killing the service
                setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                setLocalOnly(true) // Don't sync to wearables to save resources
            }
            // Make notification silent by clearing any sound or defaults
            setOnlyAlertOnce(true)
            setSound(null)
            setVibrate(null)
            setLights(0, 0, 0)
            setDefaults(0)
        }

        return builder.build()
    }

    fun createNotificationChannel(
        @StringRes nameResourceId: Int,
        @StringRes descriptionResourceId: Int,
        importance: Int = NotificationManager.IMPORTANCE_HIGH // Changed from DEFAULT to HIGH
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val name = service.getString(nameResourceId)

            val existing = notificationManager.getNotificationChannel(channelId)

            // If the channel exists and is already silent/low-importance, keep it.
            // Otherwise (it exists with a sound or high importance), delete and
            // recreate it as silent. This avoids clearing user prefs unnecessarily.
            var shouldRecreate = true
            if (existing != null) {
                val hasSound = existing.sound != null
                val hasVibration = existing.vibrationPattern != null
                val isLoud = existing.importance > NotificationManager.IMPORTANCE_LOW
                if (!hasSound && !hasVibration && !isLoud) {
                    shouldRecreate = false
                }
            }

            if (shouldRecreate && existing != null) {
                notificationManager.deleteNotificationChannel(channelId)
            }

            // If channel didn't exist or we decided to recreate, create silent channel.
            if (existing == null || shouldRecreate) {
                val silentImportance = NotificationManager.IMPORTANCE_MIN
                val channel = NotificationChannel(
                    channelId,
                    name,
                    silentImportance
                ).apply {
                    setShowBadge(true)
                    enableVibration(false)
                    enableLights(false)
                    setSound(null, null)
                    vibrationPattern = null
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    if (descriptionResourceId != 0) {
                        description = service.getString(descriptionResourceId)
                    }
                }

                notificationManager.createNotificationChannel(channel)
            }
        }
    }
}