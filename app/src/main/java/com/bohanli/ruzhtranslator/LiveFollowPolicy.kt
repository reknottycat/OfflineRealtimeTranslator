package com.bohanli.ruzhtranslator

/**
 * Distinguishes user navigation from subtitle layout changes.
 * A view reflow or streaming token must never turn live-follow off.
 */
internal object LiveFollowPolicy {
    fun nextState(
        userDragging: Boolean,
        currentY: Int,
        previousY: Int,
        viewportHeight: Int,
        contentHeight: Int,
        bottomSlack: Int
    ): Boolean? {
        if (!userDragging || currentY == previousY) return null
        return currentY + viewportHeight >= contentHeight - bottomSlack
    }
}
