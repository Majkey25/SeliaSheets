package com.majkeylab.seliadocs.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class PageGestureArbiterTest {
    @Test
    fun oneFingerHorizontalSwipeTurnsOnePage() {
        assertEquals(PageTurn.NEXT, turn(horizontal = -101f))
        assertEquals(PageTurn.PREVIOUS, turn(horizontal = 101f))
    }

    @Test
    fun thresholdAndHorizontalDominanceAreRequired() {
        assertEquals(PageTurn.NONE, turn(horizontal = -100f))
        assertEquals(PageTurn.NONE, turn(horizontal = -101f, vertical = 80f))
        assertEquals(PageTurn.NEXT, turn(horizontal = -101f, vertical = 70f))
    }

    @Test
    fun zoomAndPinchOwnTheGesture() {
        assertEquals(PageTurn.NONE, turn(horizontal = -200f, zoomed = true))
        val arbiter = PageGestureArbiter()
        turn(
            arbiter = arbiter,
            pointerCount = 2,
            scaleChanged = true,
            finished = false,
        )

        assertEquals(
            PageTurn.NONE,
            turn(
                arbiter = arbiter,
                horizontal = -200f,
                pointerCount = 2,
            ),
        )
    }

    @Test
    fun fingerDrawingRequiresTwoFingers() {
        assertEquals(PageTurn.NONE, turn(horizontal = -200f, fingerDrawing = true))
        assertEquals(
            PageTurn.NEXT,
            turn(horizontal = -200f, pointerCount = 2, fingerDrawing = true),
        )
    }

    @Test
    fun excessPointersStickyBlockNavigationOwnership() {
        val oneFinger = PageGestureArbiter()
        turn(arbiter = oneFinger, pointerCount = 2, finished = false)
        assertEquals(PageTurn.NONE, turn(arbiter = oneFinger, horizontal = -200f))

        val twoFinger = PageGestureArbiter()
        turn(arbiter = twoFinger, pointerCount = 3, fingerDrawing = true, finished = false)
        assertEquals(
            PageTurn.NONE,
            turn(arbiter = twoFinger, horizontal = -200f, pointerCount = 2, fingerDrawing = true),
        )
    }

    @Test
    fun newPointerDownAfterOwnershipStickyBlocksNavigation() {
        val arbiter = PageGestureArbiter()
        turn(arbiter = arbiter, pointerCount = 2, fingerDrawing = true, finished = false)
        turn(arbiter = arbiter, pointerCount = 1, fingerDrawing = true, finished = false)
        turn(
            arbiter = arbiter,
            pointerCount = 2,
            fingerDrawing = true,
            newPointerDownAfterOwnership = true,
            finished = false,
        )

        assertEquals(
            PageTurn.NONE,
            turn(arbiter = arbiter, horizontal = -200f, pointerCount = 2, fingerDrawing = true),
        )
    }

    @Test
    fun completedGestureEmitsAtMostOneTurn() {
        val arbiter = PageGestureArbiter()

        assertEquals(
            PageTurn.NEXT,
            turn(
                arbiter = arbiter,
                horizontal = -200f,
            ),
        )
        assertEquals(
            PageTurn.NONE,
            turn(
                arbiter = arbiter,
                horizontal = 200f,
            ),
        )
    }

    @Test
    fun pageBoundsConsumeSwipeWithoutTurning() {
        assertEquals(PageTurn.NONE, turn(horizontal = 200f, currentPage = 0))
        assertEquals(PageTurn.NONE, turn(horizontal = -200f, currentPage = 2))
        assertEquals(PageTurn.NONE, turn(horizontal = -200f, currentPage = 0, pageCount = 1))
    }

    @Test
    fun cancellationAndStylusOwnershipBlockTurning() {
        assertEquals(PageTurn.NONE, turn(horizontal = -200f, canceled = true))
        val arbiter = PageGestureArbiter()
        turn(
            arbiter = arbiter,
            pointerCount = 0,
            stylusOwned = true,
            finished = false,
        )

        assertEquals(
            PageTurn.NONE,
            turn(
                arbiter = arbiter,
                horizontal = -200f,
            ),
        )
    }

    private fun turn(
        arbiter: PageGestureArbiter = PageGestureArbiter(),
        horizontal: Float = 0f,
        vertical: Float = 0f,
        pointerCount: Int = 1,
        fingerDrawing: Boolean = false,
        zoomed: Boolean = false,
        scaleChanged: Boolean = false,
        canceled: Boolean = false,
        stylusOwned: Boolean = false,
        gestureOwned: Boolean = false,
        newPointerDownAfterOwnership: Boolean = false,
        finished: Boolean = true,
        currentPage: Int = 1,
        pageCount: Int = 3,
    ): PageTurn =
        arbiter.onGesture(
            pageWidth = 400f,
            horizontal = horizontal,
            vertical = vertical,
            pointerCount = pointerCount,
            fingerDrawing = fingerDrawing,
            zoomed = zoomed,
            scaleChanged = scaleChanged,
            stylusOwned = stylusOwned,
            gestureOwned = gestureOwned,
            newPointerDownAfterOwnership = newPointerDownAfterOwnership,
            canceled = canceled,
            finished = finished,
            currentPage = currentPage,
            pageCount = pageCount,
        )
}
