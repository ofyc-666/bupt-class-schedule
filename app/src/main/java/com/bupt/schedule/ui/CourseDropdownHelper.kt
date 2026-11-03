package com.bupt.schedule.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

/**
 * Helper to compute dropdown dimensions and bounds for course auto-completion,
 * preventing popup overflow under the soft keyboard or off the screen while
 * ensuring long option lists are smoothly scrollable.
 */
object CourseDropdownHelper {

    fun calculateSpaceBelow(anchorView: View, safetyMarginPx: Int = 0): Int {
        if (!anchorView.isAttachedToWindow || anchorView.height <= 0) return -1
        val displayFrame = Rect()
        anchorView.getWindowVisibleDisplayFrame(displayFrame)
        val anchorLoc = IntArray(2)
        anchorView.getLocationOnScreen(anchorLoc)
        val anchorBottom = anchorLoc[1] + anchorView.height
        return displayFrame.bottom - anchorBottom - safetyMarginPx
    }

    fun computeDropDownHeight(
        count: Int,
        itemHeight: Int,
        popupPadding: Int,
        spaceBelow: Int,
        maxVisibleItems: Float = 4.5f
    ): Int {
        if (count <= 0) return ViewGroup.LayoutParams.WRAP_CONTENT
        val naturalHeight = count * itemHeight + popupPadding
        val idealMaxHeight = (maxVisibleItems * itemHeight + popupPadding).toInt()

        return if (count <= 4) {
            if (spaceBelow in 1 until naturalHeight) {
                maxOf(spaceBelow, itemHeight)
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
        } else {
            if (spaceBelow > (itemHeight * 2)) {
                minOf(idealMaxHeight, spaceBelow)
            } else {
                idealMaxHeight
            }
        }
    }
}
