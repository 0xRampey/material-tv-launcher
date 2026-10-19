package com.rampey.materialtv;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Android lets the home screen see which app is playing media (for the Now Playing card).
 * It does not read, store or send notifications.
 */
public class MediaListener extends NotificationListenerService {
}
