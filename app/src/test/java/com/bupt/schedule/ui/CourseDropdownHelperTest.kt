package com.bupt.schedule.ui

import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseDropdownHelperTest {

    private val itemHeight = 132 // 44dp * 3.0 density
    private val popupPadding = 24 // 8dp * 3.0 density
    private val idealMaxHeight = (4.5f * itemHeight + popupPadding).toInt() // 618px

    @Test
    fun zeroOrNegativeCount_returnsWrapContent() {
        assertEquals(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            CourseDropdownHelper.computeDropDownHeight(0, itemHeight, popupPadding, 800)
        )
        assertEquals(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            CourseDropdownHelper.computeDropDownHeight(-1, itemHeight, popupPadding, 800)
        )
    }

    @Test
    fun fewItems_withAmpleSpace_returnsWrapContent() {
        // 1 item, ample space -> wraps naturally without empty area
        assertEquals(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            CourseDropdownHelper.computeDropDownHeight(1, itemHeight, popupPadding, 800)
        )
        // 3 items (natural 420px), ample space 800px -> wraps naturally
        assertEquals(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            CourseDropdownHelper.computeDropDownHeight(3, itemHeight, popupPadding, 800)
        )
    }

    @Test
    fun fewItems_withConstrainedSpace_cappedToPreventKeyboardOverflow() {
        // 3 items = 420px, but only 300px available before keyboard
        val height = CourseDropdownHelper.computeDropDownHeight(3, itemHeight, popupPadding, 300)
        assertEquals(300, height)
        assertTrue(height <= 300)
    }

    @Test
    fun manyItems_withAmpleSpace_cappedAtIdealMaxHeightForScrolling() {
        // 10 items (natural 1344px), space 900px -> capped at 4.5 items (618px)
        val height = CourseDropdownHelper.computeDropDownHeight(10, itemHeight, popupPadding, 900)
        assertEquals(idealMaxHeight, height)
        assertTrue(height < 900)
    }

    @Test
    fun manyItems_withConstrainedSpace_strictlyRespectsSpaceBelow() {
        // 10 items, but only 450px available before keyboard (less than 4.5 items of 618px)
        val height = CourseDropdownHelper.computeDropDownHeight(10, itemHeight, popupPadding, 450)
        assertEquals(450, height)
        assertTrue(height <= 450)
    }

    @Test
    fun unknownSpaceBelow_defaultsToIdealOrWrapContent() {
        // If spaceBelow is -1 (unmeasured)
        assertEquals(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            CourseDropdownHelper.computeDropDownHeight(2, itemHeight, popupPadding, -1)
        )
        assertEquals(
            idealMaxHeight,
            CourseDropdownHelper.computeDropDownHeight(8, itemHeight, popupPadding, -1)
        )
    }
}
