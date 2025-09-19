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

    fun notify(notification: Notification) {
        notificationManager.notify(notificationId, notification)
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
        val openIntent = Intent(service, MainActivity::class.java).apply {
            action = "com.dimadesu.lifestreamer.ACTION_OPEN_FROM_NOTIFICATION"
            // Ensure the activity is brought to front if already running
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(service, 0, openIntent, pendingIntentFlags)
        
        val builder = NotificationCompat.Builder(service, channelId).apply {
            setSmallIcon(iconResourceId)
            // Avoid showing the app label twice when expanded: if the provided
            // title matches the application label, don't set content title so
            // the system (which already shows the app name in the header)
            // won't render it twice.
            val appLabel = try {
                service.applicationInfo.loadLabel(service.packageManager).toString()
            } catch (_: Throwable) {
                null
            }
            if (appLabel == null || title != appLabel) {
                setContentTitle(title)
            }
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

    /**
     * Create a small transient notification that always sets the content title
     * to ensure it replaces previous notification header content.
     */
    fun createTransientNotification(
        title: String,
        content: String? = null,
        @DrawableRes iconResourceId: Int
    ): Notification {
        // Diagnostic: log transient notification creation
        try { android.util.Log.d("CameraStreamerService", "NotificationUtils.createTransientNotification: title='${title}', content='${content}'") } catch (_: Throwable) {}
        val builder = NotificationCompat.Builder(service, channelId).apply {
            setSmallIcon(iconResourceId)
            // Use the provided content as the primary notification title so the
            // system doesn't reuse an older header (which previously caused the
            // 'Live' label to briefly appear). If content is null, fall back to
            // the supplied title.
            val primary = content ?: title
            primary?.let { setContentTitle(it) }
            // Only set content text when it's different from the primary title
            if (content != null && content != primary) setContentText(content)
            setOnlyAlertOnce(true)
            setSound(null)
            setVibrate(null)
            setLights(0, 0, 0)
            setDefaults(0)
            setAutoCancel(true)
            setOngoing(false)
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

    /**
     * Create a service-style notification that includes common actions used by
     * the streaming service (Start/Stop, Mute/Unmute, Exit) and consistent
     * foreground attributes. The PendingIntents are provided by the caller so
     * this utility remains stateless.
     */
    fun createServiceNotification(
        title: String?,
        content: String?,
        @DrawableRes iconResourceId: Int,
        isForeground: Boolean,
        showStart: Boolean,
        showStop: Boolean,
        startPending: PendingIntent?,
        stopPending: PendingIntent?,
        muteLabel: String,
        mutePending: PendingIntent?,
        exitPending: PendingIntent?,
        openPending: PendingIntent?
    ): Notification {
            val builder = NotificationCompat.Builder(service, channelId).apply {
            setSmallIcon(iconResourceId)
            // Ensure the notification's primary title reflects the current
            // content/status so the system doesn't reuse a stale header.
            val primary = content ?: title
            primary?.let { setContentTitle(it) }
            setContentIntent(openPending)
            // Only set a secondary line if it differs from the primary title
            if (content != null && content != primary) setContentText(content)

            if (isForeground) {
                priority = NotificationCompat.PRIORITY_HIGH
                setOngoing(true)
                setAutoCancel(false)
                setShowWhen(true)
                setUsesChronometer(true)
                setCategory(NotificationCompat.CATEGORY_SERVICE)
                setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                setLocalOnly(true)
            } else {
                setOngoing(false)
            }

            if (showStop && stopPending != null) addAction(iconResourceId, "Stop", stopPending)
            if (showStart && startPending != null) addAction(iconResourceId, "Start", startPending)
            // Always include mute and exit actions where provided
            if (mutePending != null) addAction(iconResourceId, muteLabel, mutePending)
            if (exitPending != null) addAction(iconResourceId, service.getString(com.dimadesu.lifestreamer.R.string.service_notification_action_exit), exitPending)

            setOnlyAlertOnce(true)
            setSound(null)
            setVibrate(null)
            setLights(0, 0, 0)
            setDefaults(0)
        }

        return builder.build()
    }
}