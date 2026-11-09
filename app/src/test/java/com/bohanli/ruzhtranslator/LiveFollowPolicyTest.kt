package com.bohanli.ruzhtranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveFollowPolicyTest {
    @Test fun programmaticScrollMustNotPauseLiveFollowing() {
        assertNull(LiveFollowPolicy.nextState(
            userDragging = false, currentY = 20, previousY = 300,
            viewportHeight = 400, contentHeight = 1500, bottomSlack = 20))
    }

    @Test fun actualUserDragUpPausesFollowing() {
        assertEquals(false, LiveFollowPolicy.nextState(
            userDragging = true, currentY = 600, previousY = 1000,
            viewportHeight = 400, contentHeight = 1500, bottomSlack = 20))
    }

    @Test fun userDragToBottomResumesFollowing() {
        assertEquals(true, LiveFollowPolicy.nextState(
            userDragging = true, currentY = 1100, previousY = 800,
            viewportHeight = 400, contentHeight = 1500, bottomSlack = 20))
    }

    @Test fun idleFingerDoesNotModifyFollowState() {
        assertNull(LiveFollowPolicy.nextState(
            userDragging = true, currentY = 100, previousY = 100,
            viewportHeight = 400, contentHeight = 1500, bottomSlack = 20))
    }
}
