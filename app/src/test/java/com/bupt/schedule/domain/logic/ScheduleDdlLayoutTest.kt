package com.bupt.schedule.domain.logic

import com.bupt.schedule.domain.model.Assignment
import com.bupt.schedule.domain.model.AssignmentStatus
import com.bupt.schedule.ui.model.CourseTone
import com.bupt.schedule.ui.model.ScheduleAssignmentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleDdlLayoutTest {

    private val defaultParams = ScheduleDdlLayout.LayoutParams(
        bodyTop = 100f,
        bodyBottom = 800f,
        rowHeight = 50f,
        ribbonHeight = 15f,
        minGap = 4f,
        margin = 2f,
        touchPadding = 4f,
    )

    private fun makeItem(
        id: String,
        time: String,
        day: Int = 1,
        courseName: String = "Course",
    ): ScheduleAssignmentItem {
        val assignment = Assignment(
            id = id,
            courseName = courseName,
            title = "Title-$id",
            deadlineText = "$time",
            deadlineTimeShort = time,
            status = AssignmentStatus.PENDING,
            week = 1,
            weekday = day,
            deadlineSlot = 0,
        )
        return ScheduleAssignmentItem(
            id = id,
            assignmentId = id,
            courseName = courseName,
            title = "Title-$id",
            deadlineTimeShort = time,
            day = day,
            deadlineSlot = 0,
            status = AssignmentStatus.PENDING,
            tone = CourseTone.SKY,
            original = assignment,
        )
    }

    // Scenario 1: Multiple items before 08:00 (top downward arrangement)
    @Test
    fun testMultipleItemsBeforeEightAm_stackDownwardsFromTop() {
        val items = listOf(
            makeItem("a1", "07:00"),
            makeItem("a2", "07:30"),
            makeItem("a3", "07:59"),
        )
        val s = defaultParams.ribbonHeight + defaultParams.minGap // 19f
        val result = ScheduleDdlLayout.layoutDayAssignments(items, defaultParams, 0f, 100f)

        assertEquals(3, result.size)
        // First item should be at L
        assertEquals(defaultParams.L, result[0].y, 0.001f)
        // Subsequent items stack downwards with at least s spacing
        for (i in 0 until result.size - 1) {
            assertTrue(
                "Spacing between ${result[i + 1].y} and ${result[i].y} must be >= $s",
                result[i + 1].y - result[i].y >= s - 0.001f
            )
            assertTrue("Items must not be above L", result[i].y >= defaultParams.L - 0.001f)
            assertTrue("Items must not be below U", result[i].y <= defaultParams.U + 0.001f)
        }
    }

    // Scenario 2: Multiple items after 20:55 (bottom upward arrangement, latest at bottom)
    @Test
    fun testMultipleItemsAfterNinePm_stackUpwardsFromBottom() {
        val items = listOf(
            makeItem("b1", "21:00"),
            makeItem("b2", "22:00"),
            makeItem("b3", "23:59"),
        )
        val s = defaultParams.ribbonHeight + defaultParams.minGap // 19f
        val result = ScheduleDdlLayout.layoutDayAssignments(items, defaultParams, 0f, 100f)

        assertEquals(3, result.size)
        // Latest item must be at U
        assertEquals(defaultParams.U, result[2].y, 0.001f)
        // Previous items stack upwards
        for (i in 0 until result.size - 1) {
            assertTrue(
                "Spacing must be >= $s",
                result[i + 1].y - result[i].y >= s - 0.001f
            )
            assertTrue("Must be within bounds", result[i].y >= defaultParams.L - 0.001f)
            assertTrue("Must be within bounds", result[i].y <= defaultParams.U + 0.001f)
        }
    }

    // Scenario 3: Middle crowding (14:00, 14:02, 14:05) shares displacement symmetrically
    @Test
    fun testMiddleCrowding_sharesDisplacementSymmetrically() {
        val items = listOf(
            makeItem("c1", "14:00"),
            makeItem("c2", "14:02"),
            makeItem("c3", "14:05"),
        )
        val s = defaultParams.ribbonHeight + defaultParams.minGap
        val result = ScheduleDdlLayout.layoutDayAssignments(items, defaultParams, 0f, 100f)

        assertEquals(3, result.size)
        val ideal0 = result[0].idealY
        val ideal1 = result[1].idealY
        val ideal2 = result[2].idealY

        // c1 should move up (y < ideal), c3 should move down (y > ideal)
        assertTrue("c1 moves up", result[0].y < ideal0)
        assertTrue("c3 moves down", result[2].y > ideal2)
        // Middle should stay very close to the mean of ideal positions
        val meanIdeal = (ideal0 + ideal1 + ideal2) / 3f
        assertEquals(meanIdeal, result[1].y, 0.05f)
        // Spacing must be exactly s
        assertEquals(s, result[1].y - result[0].y, 0.001f)
        assertEquals(s, result[2].y - result[1].y, 0.001f)
    }

    // Scenario 4: Non-overlapping items (09:00, 14:00, 19:00) keep ideal positions
    @Test
    fun testNonOverlappingItems_keepIdealPositions() {
        val items = listOf(
            makeItem("d1", "09:00"),
            makeItem("d2", "14:00"),
            makeItem("d3", "19:00"),
        )
        val result = ScheduleDdlLayout.layoutDayAssignments(items, defaultParams, 0f, 100f)

        assertEquals(3, result.size)
        for (item in result) {
            assertEquals("No movement when separated", item.idealY, item.y, 0.001f)
        }
    }

    // Scenario 5: Breaks and lunch boundaries (12:15, 12:30, 13:00) map to period 5 boundary
    @Test
    fun testBreaksAndLunchMapping() {
        val slot1215 = ScheduleDdlLayout.timeToContinuousSlot(
            ScheduleDdlLayout.parseMinutes("12:15")!!,
            ScheduleLogic.DEFAULT_SLOT_TIMES
        )
        val slot1230 = ScheduleDdlLayout.timeToContinuousSlot(
            ScheduleDdlLayout.parseMinutes("12:30")!!,
            ScheduleLogic.DEFAULT_SLOT_TIMES
        )
        val slot1300 = ScheduleDdlLayout.timeToContinuousSlot(
            ScheduleDdlLayout.parseMinutes("13:00")!!,
            ScheduleLogic.DEFAULT_SLOT_TIMES
        )

        // All 3 map to boundary between period 4 (0-based) and period 5, which is slot 5.0
        assertEquals(5.0, slot1215, 0.0001)
        assertEquals(5.0, slot1230, 0.0001)
        assertEquals(5.0, slot1300, 0.0001)

        val items = listOf(
            makeItem("e1", "12:15"),
            makeItem("e2", "12:30"),
            makeItem("e3", "13:00"),
        )
        val result = ScheduleDdlLayout.layoutDayAssignments(items, defaultParams, 0f, 100f)
        assertEquals(3, result.size)
        val s = defaultParams.ribbonHeight + defaultParams.minGap
        assertEquals(s, result[1].y - result[0].y, 0.001f)
        assertEquals(s, result[2].y - result[1].y, 0.001f)
    }

    // Scenario 6: Multiple items at 23:59 do not overlap and do not exceed boundary
    @Test
    fun testMultipleItemsAtMidnight_doNotOverlapOrExceed() {
        val items = listOf(
            makeItem("m1", "23:59"),
            makeItem("m2", "23:59"),
            makeItem("m3", "23:59"),
        )
        val s = defaultParams.ribbonHeight + defaultParams.minGap
        val result = ScheduleDdlLayout.layoutDayAssignments(items, defaultParams, 0f, 100f)

        assertEquals(3, result.size)
        // Last item at U
        assertEquals(defaultParams.U, result[2].y, 0.001f)
        assertEquals(defaultParams.U - s, result[1].y, 0.001f)
        assertEquals(defaultParams.U - 2 * s, result[0].y, 0.001f)
        assertTrue(result[0].y >= defaultParams.L)
    }

    // Scenario 7: Same minute different items have stable, deterministic sorting
    @Test
    fun testSameMinuteDifferentItems_stableOrdering() {
        val itemA = makeItem("id_A", "10:00")
        val itemB = makeItem("id_B", "10:00")
        val itemC = makeItem("id_C", "10:00")

        // Passing in reverse order
        val result1 = ScheduleDdlLayout.layoutDayAssignments(listOf(itemC, itemA, itemB), defaultParams, 0f, 100f)
        val result2 = ScheduleDdlLayout.layoutDayAssignments(listOf(itemB, itemC, itemA), defaultParams, 0f, 100f)

        assertEquals(3, result1.size)
        assertEquals(3, result2.size)
        // Order must be sorted by ID: id_A, id_B, id_C
        assertEquals("id_A", (result1[0] as ScheduleDdlLayout.PlacedItem.Single).assignment.id)
        assertEquals("id_B", (result1[1] as ScheduleDdlLayout.PlacedItem.Single).assignment.id)
        assertEquals("id_C", (result1[2] as ScheduleDdlLayout.PlacedItem.Single).assignment.id)

        for (i in 0..2) {
            assertEquals(result1[i].y, result2[i].y, 0.0001f)
        }
    }

    // Scenario 8: Insufficient space near boundaries maintains boundary and temporal order
    @Test
    fun testInsufficientBoundarySpace_maintainsBoundsAndOrder() {
        // Many items near top
        val items = (1..6).map { makeItem("top_$it", "08:00") }
        val result = ScheduleDdlLayout.layoutDayAssignments(items, defaultParams, 0f, 100f)
        assertEquals(6, result.size)
        assertEquals(defaultParams.L, result.first().y, 0.001f)
        for (i in 0 until result.size - 1) {
            assertTrue(result[i + 1].y > result[i].y)
            assertTrue(result[i].y >= defaultParams.L)
            assertTrue(result[i].y <= defaultParams.U)
        }
    }

    // Scenario 9: Extremely small height, invalid time data, empty list handled safely
    @Test
    fun testEdgeCases_tinyHeightInvalidTimeEmptyList() {
        // Empty list
        val emptyResult = ScheduleDdlLayout.layoutDayAssignments(emptyList(), defaultParams, 0f, 100f)
        assertTrue(emptyResult.isEmpty())

        // Tiny height (U <= L)
        val tinyParams = defaultParams.copy(bodyTop = 100f, bodyBottom = 110f)
        val tinyResult = ScheduleDdlLayout.layoutDayAssignments(
            listOf(makeItem("x1", "08:00")),
            tinyParams,
            0f,
            100f
        )
        assertTrue(tinyResult.isEmpty())

        // Invalid time strings
        val badItems = listOf(
            makeItem("bad1", ""),
            makeItem("bad2", "invalid"),
            makeItem("bad3", "99:99"),
        )
        val badResult = ScheduleDdlLayout.layoutDayAssignments(badItems, defaultParams, 0f, 100f)
        assertEquals(3, badResult.size)
        for (item in badResult) {
            assertTrue(item.y >= defaultParams.L)
            assertTrue(item.y <= defaultParams.U)
        }
    }

    // Scenario 10: Touch hit rect matches display position and prevents touch overlap
    @Test
    fun testTouchHitRect_matchesDisplayAndPreventsTouchTheft() {
        val items = listOf(
            makeItem("t1", "14:00"),
            makeItem("t2", "14:01"),
            makeItem("t3", "14:02"),
        )
        val result = ScheduleDdlLayout.layoutDayAssignments(items, defaultParams, 10f, 90f)
        assertEquals(3, result.size)

        // Verify hit rects do not overlap vertically
        for (i in 0 until result.size - 1) {
            val currRect = result[i].hitRect
            val nextRect = result[i + 1].hitRect
            assertTrue(
                "Hit rects must not overlap: curr.bottom (${currRect.bottom}) <= next.top (${nextRect.top})",
                currRect.bottom <= nextRect.top + 0.001f
            )
        }

        // Verify tapping on the exact center of item 1 hits item 1 and not item 2
        val item1 = result[1]
        assertTrue(item1.hitRect.contains(50f, item1.y))
        assertFalse(result[0].hitRect.contains(50f, item1.y))
        assertFalse(result[2].hitRect.contains(50f, item1.y))

        // Verify left and right match column width
        assertEquals(10f, item1.hitRect.left, 0.001f)
        assertEquals(90f, item1.hitRect.right, 0.001f)
    }

    // Overflow test when items exceed available canvas height
    @Test
    fun testExtremeCrowding_triggersAggregationGracefully() {
        // Create 20 items in a tight 200px space (where at most ~10 can fit with h=15)
        val tightParams = defaultParams.copy(bodyTop = 100f, bodyBottom = 300f)
        val items = (1..20).map { makeItem("item_$it", "10:${String.format("%02d", it)}") }
        val result = ScheduleDdlLayout.layoutDayAssignments(items, tightParams, 0f, 100f)

        assertTrue("Should return placed items", result.isNotEmpty())
        assertTrue("Should not exceed max capacity", result.size <= 20)
        // Last item should be Overflow containing remaining items
        val lastItem = result.last()
        assertTrue("Last item should be overflow", lastItem is ScheduleDdlLayout.PlacedItem.Overflow)
        val overflow = lastItem as ScheduleDdlLayout.PlacedItem.Overflow
        assertTrue("Overflow contains items", overflow.assignments.isNotEmpty())
        // Verify all 20 items are accounted for
        val singleCount = result.count { it is ScheduleDdlLayout.PlacedItem.Single }
        val overflowCount = overflow.assignments.size
        assertEquals(20, singleCount + overflowCount)
    }

    // Regression Test 1: Week with assignments -> Week without assignments clears hit rects
    @Test
    fun testTransitionFromAssignmentsToEmpty_clearsHitRects() {
        val cache = ScheduleDdlLayout.AssignmentHitCache()
        val items = listOf(
            makeItem("w1", "10:00"),
            makeItem("w2", "15:00"),
        )
        val placed = ScheduleDdlLayout.layoutDayAssignments(items, defaultParams, 0f, 100f)
        cache.update(placed)

        // Initially hits are valid
        assertNotNull(cache.findAssignmentAt(50f, placed[0].y, showAssignments = true, modeProgress = 1.0f, hasRecords = true))

        // Transition to empty week: clear cache
        cache.clear()

        // Old positions must return null
        val hitAfterClear = cache.findAssignmentAt(50f, placed[0].y, showAssignments = true, modeProgress = 1.0f, hasRecords = false)
        assertEquals(null, hitAfterClear)
        assertTrue(cache.isEmpty)
        assertEquals(0, cache.singleCount)
        assertEquals(0, cache.overflowCount)
    }

    // Regression Test 2: Deleting the last custom assignment leaves no residual click area
    @Test
    fun testDeleteLastCustomAssignment_leavesNoResidualClickArea() {
        val cache = ScheduleDdlLayout.AssignmentHitCache()
        val singleCustom = listOf(makeItem("custom_1", "16:00"))
        val placed = ScheduleDdlLayout.layoutDayAssignments(singleCustom, defaultParams, 0f, 100f)
        cache.update(placed)

        val targetY = placed[0].y
        assertEquals("custom_1", cache.findAssignmentAt(50f, targetY, true, 1.0f, true)?.id)

        // Delete/complete the assignment -> empty list
        cache.clear()

        assertEquals(null, cache.findAssignmentAt(50f, targetY, true, 1.0f, false))
        // Even if hasRecords flag is accidentally true, empty cache must still return null
        assertEquals(null, cache.findAssignmentAt(50f, targetY, true, 1.0f, true))
    }

    // Regression Test 3: Single items -> Overflow items -> Empty list: both caches update and clear
    @Test
    fun testTransitionSingleToOverflowToEmpty_bothCachesUpdatedAndCleared() {
        val cache = ScheduleDdlLayout.AssignmentHitCache()

        // 1. Single items
        val singles = listOf(makeItem("s1", "09:00"), makeItem("s2", "14:00"))
        val placedSingles = ScheduleDdlLayout.layoutDayAssignments(singles, defaultParams, 0f, 100f)
        cache.update(placedSingles)
        assertEquals(2, cache.singleCount)
        assertEquals(0, cache.overflowCount)
        assertEquals(null, cache.findOverflowAt(50f, placedSingles[0].y, true, 1.0f, true))

        // 2. Crowded items triggering overflow
        val tightParams = defaultParams.copy(bodyTop = 100f, bodyBottom = 250f)
        val crowded = (1..15).map { makeItem("c_$it", "10:${String.format("%02d", it)}") }
        val placedCrowded = ScheduleDdlLayout.layoutDayAssignments(crowded, tightParams, 0f, 100f)
        cache.update(placedCrowded)

        assertTrue(cache.singleCount > 0)
        assertTrue(cache.overflowCount > 0)
        val overflowItem = placedCrowded.last()
        val overflowHits = cache.findOverflowAt(50f, overflowItem.y, true, 1.0f, true)
        assertNotNull(overflowHits)
        assertTrue(overflowHits!!.isNotEmpty())

        // 3. Clear to empty
        cache.clear()
        assertEquals(0, cache.singleCount)
        assertEquals(0, cache.overflowCount)
        assertEquals(null, cache.findAssignmentAt(50f, overflowItem.y, true, 1.0f, false))
        assertEquals(null, cache.findOverflowAt(50f, overflowItem.y, true, 1.0f, false))
    }

    // Regression Test 4: List A -> List B ensures tasks from A that are absent in B cannot be hit
    @Test
    fun testTransitionListAToListB_clearsDeletedItemsImmediately() {
        val cache = ScheduleDdlLayout.AssignmentHitCache()
        val listA = listOf(makeItem("item_A1", "10:00"), makeItem("item_A2", "12:00"))
        val placedA = ScheduleDdlLayout.layoutDayAssignments(listA, defaultParams, 0f, 100f)
        cache.update(placedA)

        val yA1 = placedA[0].y
        val yA2 = placedA[1].y
        assertEquals("item_A1", cache.findAssignmentAt(50f, yA1, true, 1.0f, true)?.id)

        // Synchronously clear when records change (before List B is drawn)
        cache.clear()
        assertEquals(null, cache.findAssignmentAt(50f, yA1, true, 1.0f, true))

        // When List B is drawn
        val listB = listOf(makeItem("item_B1", "18:00"))
        val placedB = ScheduleDdlLayout.layoutDayAssignments(listB, defaultParams, 0f, 100f)
        cache.update(placedB)

        // Only B1 can be hit; old coordinates of A1 and A2 return null
        assertEquals("item_B1", cache.findAssignmentAt(50f, placedB[0].y, true, 1.0f, true)?.id)
        assertEquals(null, cache.findAssignmentAt(50f, yA1, true, 1.0f, true))
        assertEquals(null, cache.findAssignmentAt(50f, yA2, true, 1.0f, true))
    }

    // Regression Test 5: Normal assignments and overflow clicks function correctly
    @Test
    fun testNormalAssignmentsAndOverflowClick_functionCorrectly() {
        val cache = ScheduleDdlLayout.AssignmentHitCache()
        val tightParams = defaultParams.copy(bodyTop = 100f, bodyBottom = 250f)
        val items = (1..10).map { makeItem("task_$it", "11:${String.format("%02d", it)}") }
        val placed = ScheduleDdlLayout.layoutDayAssignments(items, tightParams, 0f, 100f)
        cache.update(placed)

        // First item is single
        val firstSingle = placed.first() as ScheduleDdlLayout.PlacedItem.Single
        val hitSingle = cache.findAssignmentAt(50f, firstSingle.y, true, 1.0f, true)
        assertEquals(firstSingle.assignment.id, hitSingle?.id)

        // Last item is overflow
        val lastOverflow = placed.last() as ScheduleDdlLayout.PlacedItem.Overflow
        val hitOverflow = cache.findOverflowAt(50f, lastOverflow.y, true, 1.0f, true)
        assertNotNull(hitOverflow)
        assertEquals(lastOverflow.assignments.size, hitOverflow!!.size)
    }

    // Regression Test 6: Toggle switch and horizontal scroll offset handling
    @Test
    fun testToggleSwitchAndHorizontalScroll_hitLogicCorrect() {
        val cache = ScheduleDdlLayout.AssignmentHitCache()
        // Item in Column 2 (left = 100, right = 200)
        val items = listOf(makeItem("col2_task", "14:00", day = 2))
        val placed = ScheduleDdlLayout.layoutDayAssignments(items, defaultParams, 100f, 200f)
        cache.update(placed)

        val targetY = placed[0].y

        // When showAssignments is false, hit must return null
        assertEquals(null, cache.findAssignmentAt(150f, targetY, showAssignments = false, modeProgress = 1.0f, hasRecords = true))

        // When animation progress < 0.3f, hit must return null
        assertEquals(null, cache.findAssignmentAt(150f, targetY, showAssignments = true, modeProgress = 0.2f, hasRecords = true))

        // When horizontal scroll offset is 50f, screen x = 100f -> contentX = 150f (inside [100, 200])
        val screenX = 100f
        val scrollOffset = 50f
        val contentX = screenX + scrollOffset
        val hitScrolled = cache.findAssignmentAt(contentX, targetY, showAssignments = true, modeProgress = 1.0f, hasRecords = true)
        assertEquals("col2_task", hitScrolled?.id)

        // Outside column bounds (contentX = 250f > 200f)
        val outsideContentX = 200f + scrollOffset
        val hitOutside = cache.findAssignmentAt(outsideContentX, targetY, showAssignments = true, modeProgress = 1.0f, hasRecords = true)
        assertEquals(null, hitOutside)
    }
}
