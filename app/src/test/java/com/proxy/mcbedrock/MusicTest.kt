package com.proxy.mcbedrock

import android.media.session.PlaybackState
import com.proxy.mcbedrock.music.TransportActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertEquals as assertEq
import org.junit.Test

/**
 * Checks the widget's mirrored action bits against the platform's own constants.
 *
 * [TransportActions] exists so the button logic can be unit tested without an
 * Android runtime; this test is what keeps the mirror honest. A wrong value here
 * would mean a control that looks enabled but does nothing (or the reverse), which
 * is exactly the kind of bug a device would hide.
 */
class MusicTest {

    @Test
    fun mirroredActionBitsMatchThePlatform() {
        assertEq("STOP", PlaybackState.ACTION_STOP.toLong(), TransportActions.STOP)
        assertEq("PAUSE", PlaybackState.ACTION_PAUSE.toLong(), TransportActions.PAUSE)
        assertEq("PLAY", PlaybackState.ACTION_PLAY.toLong(), TransportActions.PLAY)
        assertEq("REWIND", PlaybackState.ACTION_REWIND.toLong(), TransportActions.REWIND)
        assertEq("SKIP_TO_PREVIOUS", PlaybackState.ACTION_SKIP_TO_PREVIOUS.toLong(), TransportActions.SKIP_TO_PREVIOUS)
        assertEq("SKIP_TO_NEXT", PlaybackState.ACTION_SKIP_TO_NEXT.toLong(), TransportActions.SKIP_TO_NEXT)
        assertEq("FAST_FORWARD", PlaybackState.ACTION_FAST_FORWARD.toLong(), TransportActions.FAST_FORWARD)
        assertEq("SET_RATING", PlaybackState.ACTION_SET_RATING.toLong(), TransportActions.SET_RATING)
        assertEq("SEEK_TO", PlaybackState.ACTION_SEEK_TO.toLong(), TransportActions.SEEK_TO)
        assertEq("PLAY_PAUSE", PlaybackState.ACTION_PLAY_PAUSE.toLong(), TransportActions.PLAY_PAUSE)
    }

    @Test
    fun relaySuiteStillPasses() {
        assertEquals("InspectionChecks failed", 0, InspectionChecks.runAll(verbose = true))
    }
}
