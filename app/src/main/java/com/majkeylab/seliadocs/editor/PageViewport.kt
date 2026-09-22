package com.majkeylab.seliadocs.editor

internal data class PageViewport(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
)

internal fun canUpdatePageViewport(
    hasStylus: Boolean,
    overlayOwned: Boolean,
    touchCount: Int,
    fingerDrawing: Boolean,
): Boolean =
    !hasStylus &&
        !overlayOwned &&
        (touchCount >= 2 || (!fingerDrawing && touchCount > 0))

internal fun updatePageViewport(
    current: PageViewport,
    zoomChange: Float,
    gesturePanX: Float,
    gesturePanY: Float,
    focusFromCenterX: Float,
    focusFromCenterY: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    pageWidth: Float,
    pageHeight: Float,
): PageViewport {
    require(
        current.zoom in MIN_ZOOM..MAX_ZOOM &&
            current.panX.isFinite() &&
            current.panY.isFinite() &&
            zoomChange.isFinite() &&
            gesturePanX.isFinite() &&
            gesturePanY.isFinite() &&
            focusFromCenterX.isFinite() &&
            focusFromCenterY.isFinite() &&
            viewportWidth > 0f &&
            viewportHeight > 0f &&
            pageWidth > 0f &&
            pageHeight > 0f,
    )
    val zoom = (current.zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
    val ratio = zoom / current.zoom
    val panX = focusFromCenterX - (focusFromCenterX - current.panX) * ratio + gesturePanX
    val panY = focusFromCenterY - (focusFromCenterY - current.panY) * ratio + gesturePanY
    return PageViewport(
        zoom = zoom,
        panX = panX.coerceIn(-maxPan(pageWidth, zoom, viewportWidth), maxPan(pageWidth, zoom, viewportWidth)),
        panY = panY.coerceIn(-maxPan(pageHeight, zoom, viewportHeight), maxPan(pageHeight, zoom, viewportHeight)),
    )
}

internal fun fitPageWidth(viewportWidth: Float, pageWidth: Float): PageViewport {
    require(viewportWidth > 0f && pageWidth > 0f)
    return PageViewport(zoom = (viewportWidth / pageWidth).coerceIn(MIN_ZOOM, MAX_ZOOM))
}

internal fun revealPagePoint(
    current: PageViewport, x: Float, y: Float,
    viewportWidth: Float, viewportHeight: Float, pageWidth: Float, pageHeight: Float,
): PageViewport {
    require(x in 0f..1f && y in 0f..1f)
    return updatePageViewport(
        current.copy(panX = 0f, panY = 0f), 1f,
        (0.5f - x) * pageWidth * current.zoom, (0.5f - y) * pageHeight * current.zoom,
        0f, 0f, viewportWidth, viewportHeight, pageWidth, pageHeight,
    )
}

internal fun viewportCoordinateToPage(
    coordinate: Float,
    viewSize: Float,
    pageSize: Float,
): Float = coordinate * pageSize / viewSize

private fun maxPan(pageSize: Float, zoom: Float, viewportSize: Float): Float =
    ((pageSize * zoom - viewportSize) / 2f).coerceAtLeast(0f)

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
