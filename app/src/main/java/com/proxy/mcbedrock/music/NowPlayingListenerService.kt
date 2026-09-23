package com.proxy.mcbedrock.music

import android.service.notification.NotificationListenerService

/**
 * Exists to hold the permission, not to read notifications.
 *
 * Android will only hand out media sessions to a system app or to an enabled
 * notification listener (MediaSessionManager.getActiveSessions), and there is no
 * narrower grant. So this service is the key to the door: it registers with the
 * system, and then hands off to [NowPlayingBridge], which reads media sessions and
 * nothing else.
 *
 * It deliberately does not override `onNotificationPosted` or `onNotificationRemoved`.
 * A test asserts that, because that is the difference between a music widget and an
 * app that can read the user's messages — and the system prompt will say the
 * scariest version either way.
 */
class NowPlayingListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NowPlayingBridge.attachContext(this)
        NowPlayingBridge.start(this)
    }

    override fun onListenerDisconnected() {
        NowPlayingBridge.stop(this)
        super.onListenerDisconnected()
    }
}
