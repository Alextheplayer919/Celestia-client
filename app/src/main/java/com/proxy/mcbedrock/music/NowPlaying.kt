package com.proxy.mcbedrock.music

/**
 * What the widget shows, in a form that can be tested without a device.
 *
 * The relay has nothing to do with any of this: this is the phone's media session,
 * read so the player can be controlled without leaving the game. Nothing here talks
 * to a network or touches the game.
 */
data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Which transport buttons the player says it will honour, from its action mask. */
    val actions: Long = 0,
    /** Identifies the track, so album art is decoded once per track instead of per frame. */
    val trackKey: String = "",
    val appLabel: String = ""
) {

    val hasAnything: Boolean get() = title.isNotBlank()

    val canPlayPause: Boolean
        get() = (actions and (TransportActions.PLAY_PAUSE or TransportActions.PLAY or
            TransportActions.PAUSE)) != 0L

    val canSkipNext: Boolean
        get() = (actions and TransportActions.SKIP_TO_NEXT) != 0L

    val canSkipPrevious: Boolean
        get() = (actions and TransportActions.SKIP_TO_PREVIOUS) != 0L

    /** 0f..1f, or null when the player reports no duration (live radio, for example). */
    val progress: Float?
        get() = if (durationMs <= 0) null else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    companion object {
        val EMPTY = NowPlaying()
    }
}

/**
 * The strings the widget draws.
 *
 * Split out so the awkward cases — a live stream with no duration, a track with no
 * artist, a title longer than the panel — are decided by something with tests rather
 * than by whatever the layout happens to do.
 */
/**
 * The transport-action bits a player advertises.
 *
 * These are the platform's `PlaybackState.ACTION_*` values, mirrored here so this
 * file stays plain Kotlin and the widget's button logic can be tested without an
 * Android runtime. [MusicTest] asserts every one of them against the real
 * `PlaybackState` constants in CI, so a wrong value here fails the build instead of
 * silently greying out a button.
 */
object TransportActions {
    const val STOP = 1L
    const val PAUSE = 2L
    const val PLAY = 4L
    const val REWIND = 8L
    const val SKIP_TO_PREVIOUS = 16L
    const val SKIP_TO_NEXT = 32L
    const val FAST_FORWARD = 64L
    const val SET_RATING = 128L
    const val SEEK_TO = 256L
    const val PLAY_PAUSE = 512L
}

object NowPlayingText {

    /** `Artist`, `Artist · Album`, or a placeholder — never blank. */
    fun subtitle(state: NowPlaying): String {
        val parts = listOf(state.artist, state.album)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "<unknown>" }
        return parts.joinToString(" · ").ifEmpty { "unknown artist" }
    }

    /** `1:03 / 3:45`, or `LIVE` when the player reports no duration. */
    fun progress(state: NowPlaying): String {
        val duration = state.durationMs
        if (duration <= 0) return "LIVE"
        return "${clock(state.positionMs)} / ${clock(duration)}"
    }

    /** `3:45`, or `--:--` for an unknown or absurd length. */
    fun clock(millis: Long): String {
        if (millis <= 0) return "--:--"
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /**
     * Shortens a title to fit the panel without cutting a word in half, and marks the
     * cut. Titles from streaming services can be very long (`Song (Remastered 2011)
     * (feat. Someone) [Official Audio]`), and eliding in the middle is worse than
     * cutting the tail.
     */
    fun fit(title: String, maxChars: Int = 34): String {
        val trimmed = title.trim()
        if (trimmed.length <= maxChars) return trimmed
        val cut = trimmed.take(maxChars)
        val lastSpace = cut.lastIndexOf(' ')
        val body = if (lastSpace > maxChars / 2) cut.take(lastSpace) else cut
        return body.trimEnd().trimEnd(',', '-', '·') + "…"
    }

    /** The whole line, for a report or a log: `Artist — Title (2:31/3:45, playing)`. */
    fun describe(state: NowPlaying): String {
        if (!state.hasAnything) return "nothing playing"
        val app = state.appLabel.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""
        val transport = if (state.isPlaying) "playing" else "paused"
        return "${subtitle(state)} — ${state.title} (${progress(state)}, $transport)$app"
    }
}
