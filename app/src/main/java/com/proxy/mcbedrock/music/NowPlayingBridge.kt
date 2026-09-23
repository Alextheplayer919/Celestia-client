package com.proxy.mcbedrock.music

import android.content.ComponentName
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import android.util.LruCache

/**
 * Reads the phone's media sessions so the widget can show and control whatever is
 * playing — the user's music app, left running in the background, exactly as it
 * would be with the screen off.
 *
 * Why this needs a notification listener at all: Android only hands out media
 * sessions to apps holding a system-only permission, or to an *enabled notification
 * listener service* (see MediaSessionManager.getActiveSessions). There is no
 * narrower grant, which is why the app has to explain itself before sending anyone
 * to that settings page — the system prompt says "read all notifications", even
 * though this bridge only ever touches media sessions.
 *
 * What it does not do: it never sends, stores or transmits anything about what is
 * playing, it does not read notification content at all, and it stops the moment the
 * user revokes access.
 */
object NowPlayingBridge {

    private const val TAG = "NowPlaying"

    /** Artwork is decoded once per track and kept small; 2 entries is plenty. */
    private val artworkCache = object : LruCache<String, Bitmap>(2) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    @Volatile
    private var manager: MediaSessionManager? = null

    @Volatile
    private var controller: MediaController? = null

    @Volatile
    var current: NowPlaying = NowPlaying.EMPTY
        private set

    /** Called on the main thread whenever the state changes, so the HUD can redraw. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private val sessionCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onSessionDestroyed() {
            controller = null
            current = NowPlaying.EMPTY
            notifyChanged()
        }
    }

    private val sessionsCallback =
        MediaSessionManager.OnActiveSessionsChangedListener { list ->
            attach(if (list.isNullOrEmpty()) null else list.first())
        }

    /** True when the user has enabled our listener in system settings. */
    fun hasAccess(context: Context): Boolean {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabled.contains(context.packageName)
    }

    fun start(context: Context) {
        if (!hasAccess(context)) return
        val sessionManager =
            context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        manager = sessionManager
        val component = ComponentName(context, NowPlayingListenerService::class.java)
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsCallback, component)
            attach(sessionManager.getActiveSessions(component).firstOrNull())
        } catch (e: SecurityException) {
            // Access was revoked between the check and this call.
            Log.w(TAG, "media session access revoked: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "could not read media sessions: ${e.message}")
        }
    }

    fun stop(context: Context) {
        val sessionManager = manager
        if (sessionManager != null) {
            runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionsCallback) }
        }
        manager = null
        attach(null)
        artworkCache.evictAll()
    }

    fun playPause() {
        val active = controller ?: return
        when {
            current.isPlaying -> active.transportControls.pause()
            else -> active.transportControls.play()
        }
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    /**
     * Album art for the current track, decoded and scaled once per track.
     *
     * Called from the HUD's render pass, so it must never decode on every frame:
     * the cache key is the track, not the position, and the bitmap is scaled down to
     * what the panel actually shows. Returns null when the player publishes none.
     */
    fun artwork(maxSizePx: Int): Bitmap? {
        val state = current
        if (!state.hasAnything) return null
        val key = "${state.trackKey}@$maxSizePx"
        artworkCache.get(key)?.let { return it }
        val active = controller ?: return null
        val metadata = runCatching { active.metadata }.getOrNull() ?: return null

        val raw = runCatching {
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }.getOrNull() ?: return null

        val scaled = if (raw.width > maxSizePx || raw.height > maxSizePx) {
            val ratio = raw.width.toFloat() / raw.height.toFloat()
            val width = if (ratio >= 1f) maxSizePx else (maxSizePx * ratio).toInt().coerceAtLeast(1)
            val height = if (ratio >= 1f) (maxSizePx / ratio).toInt().coerceAtLeast(1) else maxSizePx
            runCatching { Bitmap.createScaledBitmap(raw, width, height, true) }.getOrElse { raw }
        } else {
            raw
        }
        artworkCache.put(key, scaled)
        return scaled
    }

    // ------------------------------------------------------------------ internals

    private fun attach(newController: MediaController?) {
        val previous = controller
        if (previous?.sessionToken == newController?.sessionToken) {
            refresh()
            return
        }
        runCatching { previous?.unregisterCallback(sessionCallback) }
        controller = newController
        runCatching { newController?.registerCallback(sessionCallback) }
        refresh()
    }

    private fun refresh() {
        val active = controller
        if (active == null) {
            current = NowPlaying.EMPTY
            notifyChanged()
            return
        }
        val metadata = runCatching { active.metadata }.getOrNull()
        val playback = runCatching { active.playbackState }.getOrNull()

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

        current = NowPlaying(
            title = title,
            artist = artist,
            album = album,
            isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
            positionMs = playback?.position ?: 0L,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            actions = playback?.actions ?: 0L,
            trackKey = listOf(active.packageName, title, artist, album).joinToString("|"),
            appLabel = appLabelFor(active.packageName)
        )
        notifyChanged()
    }

    private var appLabels = mutableMapOf<String, String>()

    private fun appLabelFor(packageName: String): String {
        appLabels[packageName]?.let { return it }
        val label = runCatching {
            val pm = appContext?.packageManager ?: return packageName
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        appLabels[packageName] = label
        return label
    }

    @Volatile
    private var appContext: Context? = null

    /** Called once by the listener service so labels can be resolved. */
    fun attachContext(context: Context) {
        appContext = context.applicationContext
    }

    private fun notifyChanged() {
        runCatching { onChanged?.invoke() }
    }
}
