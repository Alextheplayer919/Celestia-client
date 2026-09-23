package com.proxy.mcbedrock.hud

/** Which corner the HUD hangs from. */
enum class HudCorner {
    TOP_START,
    TOP_END,
    BOTTOM_START,
    BOTTOM_END
}

/**
 * Where the HUD sits on screen.
 *
 * Keeping this as arithmetic with no Android types means the placement rules —
 * anchoring, clamping on screen, snapping to the nearest corner, and remembering a
 * drag offset — can all be tested. The overlay service only turns the resulting
 * numbers into window coordinates.
 */
object HudLayout {

    const val MARGIN_X = 16
    const val MARGIN_Y = 16

    /** Top-left position for [corner] with the view's measured size. */
    fun anchorPosition(
        corner: HudCorner,
        screenWidth: Int,
        screenHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        marginX: Int = MARGIN_X,
        marginY: Int = MARGIN_Y
    ): Pair<Int, Int> {
        val x = if (corner == HudCorner.TOP_START || corner == HudCorner.BOTTOM_START) {
            marginX
        } else {
            screenWidth - viewWidth - marginX
        }
        val y = if (corner == HudCorner.TOP_START || corner == HudCorner.TOP_END) {
            marginY
        } else {
            screenHeight - viewHeight - marginY
        }
        return clamp(x, y, screenWidth, screenHeight, viewWidth, viewHeight)
    }

    /** Keeps a dragged view fully on screen (a view bigger than the screen pins to 0). */
    fun clamp(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        viewWidth: Int,
        viewHeight: Int
    ): Pair<Int, Int> {
        val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
        val maxY = (screenHeight - viewHeight).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    /**
     * The corner a view is closest to, used to snap after a drag so the HUD stays
     * tidy (and stays put across rotations, since corners are re-anchored).
     */
    fun nearestCorner(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        viewWidth: Int,
        viewHeight: Int
    ): HudCorner {
        val centreX = x + viewWidth / 2
        val centreY = y + viewHeight / 2
        val leftHalf = centreX < screenWidth / 2
        val topHalf = centreY < screenHeight / 2
        return when {
            topHalf && leftHalf -> HudCorner.TOP_START
            topHalf -> HudCorner.TOP_END
            leftHalf -> HudCorner.BOTTOM_START
            else -> HudCorner.BOTTOM_END
        }
    }

    /**
     * Where a view should end up after being dropped: clamped on screen, and — when
     * [snapToCorner] — pinned back to its nearest corner.
     */
    fun settle(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        snapToCorner: Boolean
    ): Pair<Pair<Int, Int>, HudCorner> {
        val corner = nearestCorner(x, y, screenWidth, screenHeight, viewWidth, viewHeight)
        if (!snapToCorner) {
            return clamp(x, y, screenWidth, screenHeight, viewWidth, viewHeight) to corner
        }
        return anchorPosition(corner, screenWidth, screenHeight, viewWidth, viewHeight) to corner
    }
}
