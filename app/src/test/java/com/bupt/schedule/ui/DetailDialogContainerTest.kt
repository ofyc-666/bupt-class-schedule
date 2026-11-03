package com.bupt.schedule.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailDialogContainerTest {

    @Test
    fun shortContent_shrinksDialogNaturally() {
        val maxAllowedHeight = 1800
        val nonScrollHeight = 350
        val scrollMargins = 0
        val shortContentHeight = 200

        val scrollAvailable = DetailDialogContainer.computeScrollAvailableHeight(
            maxAllowedHeight = maxAllowedHeight,
            nonScrollHeight = nonScrollHeight,
            scrollMargins = scrollMargins
        )
        assertTrue(shortContentHeight <= scrollAvailable)

        val totalContentHeight = nonScrollHeight + shortContentHeight + scrollMargins
        val finalHeight = DetailDialogContainer.computeFinalHeight(
            totalContentHeight = totalContentHeight,
            maxAllowedHeight = maxAllowedHeight
        )

        assertEquals(550, finalHeight)
        assertTrue(finalHeight < maxAllowedHeight)
    }

    @Test
    fun longContent_cappedAtMaxAllowedHeight() {
        val maxAllowedHeight = 1600
        val nonScrollHeight = 360
        val scrollMargins = 0
        val longContentHeight = 3000

        val scrollAvailable = DetailDialogContainer.computeScrollAvailableHeight(
            maxAllowedHeight = maxAllowedHeight,
            nonScrollHeight = nonScrollHeight,
            scrollMargins = scrollMargins
        )
        assertEquals(1240, scrollAvailable)

        val measuredScrollHeight = minOf(longContentHeight, scrollAvailable)
        assertEquals(1240, measuredScrollHeight)

        val totalContentHeight = nonScrollHeight + measuredScrollHeight + scrollMargins
        val finalHeight = DetailDialogContainer.computeFinalHeight(
            totalContentHeight = totalContentHeight,
            maxAllowedHeight = maxAllowedHeight
        )

        assertEquals(maxAllowedHeight, finalHeight)
    }

    @Test
    fun usableHeight_accountsForStatusAndNavigationBars() {
        val screenHeight = 2400
        val statusBar = 100
        val navBar = 132
        val safetyMargin = 66

        val usable = DetailDialogContainer.computeUsableHeight(
            screenHeight = screenHeight,
            topInset = statusBar,
            bottomInset = navBar,
            topSafetyMargin = safetyMargin
        )
        assertEquals(2102, usable)

        val maxDialogHeight = DetailDialogContainer.computeMaxHeight(usable)
        assertEquals(1933, maxDialogHeight)
        assertTrue(maxDialogHeight < screenHeight - navBar)
    }

    @Test
    fun actionButtonsAlwaysHaveReservedSpace() {
        val maxAllowed = 1200
        val headerHeight = 120
        val actionButtonsHeight = 150
        val padding = 70
        val nonScrollHeight = headerHeight + actionButtonsHeight + padding

        val scrollAvailable = DetailDialogContainer.computeScrollAvailableHeight(
            maxAllowedHeight = maxAllowed,
            nonScrollHeight = nonScrollHeight,
            scrollMargins = 0
        )

        assertEquals(860, scrollAvailable)
        val finalHeight = DetailDialogContainer.computeFinalHeight(
            totalContentHeight = nonScrollHeight + scrollAvailable,
            maxAllowedHeight = maxAllowed
        )
        assertEquals(maxAllowed, finalHeight)
    }

    @Test
    fun extremeSmallScreen_neverProducesNegativeScrollHeight() {
        val maxAllowed = 300
        val nonScrollHeight = 350

        val scrollAvailable = DetailDialogContainer.computeScrollAvailableHeight(
            maxAllowedHeight = maxAllowed,
            nonScrollHeight = nonScrollHeight,
            scrollMargins = 0
        )

        assertEquals(0, scrollAvailable)
        val finalHeight = DetailDialogContainer.computeFinalHeight(
            totalContentHeight = nonScrollHeight + scrollAvailable,
            maxAllowedHeight = maxAllowed
        )
        assertEquals(maxAllowed, finalHeight)
    }
}
