package com.mojaloss.app.feature.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScreenOnPolicyTest {
    @Test fun activePlaybackAndActiveBufferingKeepTheActualVideoViewAwake() {
        assertTrue(shouldKeepVideoAwake(true, Player.STATE_READY, false))
        assertTrue(shouldKeepVideoAwake(true, Player.STATE_BUFFERING, false))
    }

    @Test fun pauseIncludingPausedBufferingAndEveryTerminalStateReleaseTheScreen() {
        assertFalse(shouldKeepVideoAwake(false, Player.STATE_READY, false))
        assertFalse(shouldKeepVideoAwake(false, Player.STATE_BUFFERING, false))
        assertFalse(shouldKeepVideoAwake(true, Player.STATE_IDLE, false))
        assertFalse(shouldKeepVideoAwake(true, Player.STATE_ENDED, false))
        assertFalse(shouldKeepVideoAwake(true, Player.STATE_READY, true))
        assertFalse(shouldKeepVideoAwake(true, Player.STATE_BUFFERING, true))
    }
}
