package com.majkeylab.seliadocs.editor

import kotlin.math.abs

internal enum class PageTurn { NONE, PREVIOUS, NEXT }

internal class PageGestureArbiter {
    private var blocked = false
    private var eligible = false
    private var consumed = false

    fun onGesture(
        pageWidth: Float,
        horizontal: Float,
        vertical: Float,
        pointerCount: Int,
        fingerDrawing: Boolean,
        zoomed: Boolean,
        scaleChanged: Boolean,
        stylusOwned: Boolean,
        gestureOwned: Boolean,
        newPointerDownAfterOwnership: Boolean,
        canceled: Boolean,
        finished: Boolean,
        currentPage: Int,
        pageCount: Int,
    ): PageTurn {
        require(pageWidth.isFinite() && pageWidth > 0f)
        require(horizontal.isFinite() && vertical.isFinite())
        require(pointerCount >= 0)
        require(pageCount > 0 && currentPage in 0 until pageCount)

        val requiredPointers = if (fingerDrawing) 2 else 1
        blocked =
            blocked ||
                zoomed ||
                scaleChanged ||
                stylusOwned ||
                gestureOwned ||
                newPointerDownAfterOwnership ||
                canceled ||
                pointerCount > requiredPointers
        eligible = eligible || pointerCount == requiredPointers
        if (!finished || consumed) return PageTurn.NONE
        consumed = true
        if (blocked || !eligible) return PageTurn.NONE
        if (abs(horizontal) <= pageWidth * 0.25f || abs(horizontal) < abs(vertical) * 1.4f) {
            return PageTurn.NONE
        }
        return when {
            horizontal > 0f && currentPage > 0 -> PageTurn.PREVIOUS
            horizontal < 0f && currentPage < pageCount - 1 -> PageTurn.NEXT
            else -> PageTurn.NONE
        }
    }
}
