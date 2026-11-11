package com.aly.sahltv

import android.service.notification.NotificationListenerService

/**
 * Empty on purpose. Android only lets an app see what other apps are playing (media sessions)
 * if the user allows it as a "notification listener". Sahl TV reads no notifications; it only uses
 * this to show the Now playing bar. Enable once with ADB:
 *   adb shell cmd notification allow_listener com.aly.sahltv/com.aly.sahltv.MediaListener
 */
class MediaListener : NotificationListenerService()
