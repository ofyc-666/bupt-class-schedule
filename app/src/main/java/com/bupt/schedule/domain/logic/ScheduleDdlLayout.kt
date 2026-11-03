package com.bupt.schedule.domain.logic

import com.bupt.schedule.ui.model.ScheduleAssignmentItem
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object ScheduleDdlLayout {

    data class LayoutRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
    }

    data class LayoutParams(
        val bodyTop: Float,
        val bodyBottom: Float,
        val rowHeight: Float,
        val ribbonHeight: Float = 15f,
        val minGap: Float = 4f,
        val margin: Float = 2f,
        val touchPadding: Float = 4f,
        val slotTimes: List<Pair<String, String>> = ScheduleLogic.DEFAULT_SLOT_TIMES,
    ) {
        val L: Float get() = bodyTop + ribbonHeight / 2f + margin
        val U: Float get() = bodyBottom - ribbonHeight / 2f - margin
    }

    sealed class PlacedItem {
        abstract val y: Float
        abstract val idealY: Float
        abstract val hitRect: LayoutRect

        data class Single(
            val assignment: ScheduleAssignmentItem,
            override val idealY: Float,
            override val y: Float,
            override val hitRect: LayoutRect,
        ) : PlacedItem()

        data class Overflow(
            val assignments: List<ScheduleAssignmentItem>,
            override val idealY: Float,
            override val y: Float,
            override val hitRect: LayoutRect,
        ) : PlacedItem()
    }

    class AssignmentHitCache {
        private val singles = mutableListOf<PlacedItem.Single>()
        private val overflows = mutableListOf<PlacedItem.Overflow>()

        fun clear() {
            singles.clear()
            overflows.clear()
        }

        fun add(placed: PlacedItem) {
            when (placed) {
                is PlacedItem.Single -> singles.add(placed)
                is PlacedItem.Overflow -> overflows.add(placed)
            }
        }

        fun update(placedItems: List<PlacedItem>) {
            clear()
            for (p in placedItems) {
                add(p)
            }
        }

        fun findAssignmentAt(
            contentX: Float,
            contentY: Float,
            showAssignments: Boolean,
            modeProgress: Float,
            hasRecords: Boolean,
        ): ScheduleAssignmentItem? {
            if (!showAssignments || modeProgress < 0.3f || !hasRecords || singles.isEmpty()) {
                return null
            }
            for (i in singles.indices.reversed()) {
                val item = singles[i]
                if (item.hitRect.contains(contentX, contentY)) {
                    return item.assignment
                }
            }
            return null
        }

        fun findOverflowAt(
            contentX: Float,
            contentY: Float,
            showAssignments: Boolean,
            modeProgress: Float,
            hasRecords: Boolean,
        ): List<ScheduleAssignmentItem>? {
            if (!showAssignments || modeProgress < 0.3f || !hasRecords || overflows.isEmpty()) {
                return null
            }
            for (i in overflows.indices.reversed()) {
                val item = overflows[i]
                if (item.hitRect.contains(contentX, contentY)) {
                    return item.assignments
                }
            }
            return null
        }

        val singleCount: Int get() = singles.size
        val overflowCount: Int get() = overflows.size
        val isEmpty: Boolean get() = singles.isEmpty() && overflows.isEmpty()
    }

    fun parseMinutes(timeStr: String?): Int? {
        if (timeStr.isNullOrBlank()) return null
        val parts = timeStr.trim().split(":")
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    fun timeToContinuousSlot(
        timeMinutes: Int,
        slotTimes: List<Pair<String, String>> = ScheduleLogic.DEFAULT_SLOT_TIMES,
    ): Double {
        if (slotTimes.isEmpty()) return 0.0
        val n = slotTimes.size
        val firstStart = parseMinutes(slotTimes[0].first) ?: 480
        val lastEnd = parseMinutes(slotTimes[n - 1].second) ?: 1255

        if (timeMinutes <= firstStart) return 0.0
        if (timeMinutes >= lastEnd) return n.toDouble()

        for (i in 0 until n) {
            val s = parseMinutes(slotTimes[i].first) ?: continue
            val e = parseMinutes(slotTimes[i].second) ?: continue

            if (timeMinutes in s..e) {
                val duration = (e - s).coerceAtLeast(1)
                val frac = (timeMinutes - s).toDouble() / duration
                return i + frac
            }

            if (i < n - 1) {
                val nextS = parseMinutes(slotTimes[i + 1].first) ?: continue
                if (timeMinutes in (e + 1) until nextS) {
                    return (i + 1).toDouble()
                }
            }
        }
        return n.toDouble()
    }

    fun calculateIdealCenterY(
        continuousSlot: Double,
        bodyTop: Float,
        rowHeight: Float,
        L: Float,
        U: Float,
    ): Float {
        val rawY = bodyTop + (continuousSlot * rowHeight).toFloat()
        return if (U >= L) rawY.coerceIn(L, U) else rawY
    }

    fun solvePava(
        p: FloatArray,
        s: Float,
        L: Float,
        U: Float,
    ): FloatArray {
        val n = p.size
        if (n == 0) return FloatArray(0)
        if (n == 1) return floatArrayOf(if (U >= L) p[0].coerceIn(L, U) else p[0])

        val q = DoubleArray(n) { i -> p[i].toDouble() - i * s }

        class Block(var sum: Double, var weight: Int, val start: Int, var end: Int) {
            val value: Double get() = sum / weight
        }

        val stack = ArrayList<Block>(n)
        for (i in 0 until n) {
            var curr = Block(q[i], 1, i, i)
            while (stack.isNotEmpty() && stack.last().value > curr.value) {
                val prev = stack.removeAt(stack.size - 1)
                curr = Block(prev.sum + curr.sum, prev.weight + curr.weight, prev.start, curr.end)
            }
            stack.add(curr)
        }

        val z = DoubleArray(n)
        for (block in stack) {
            val v = block.value
            for (i in block.start..block.end) {
                z[i] = v
            }
        }

        val zMin = L.toDouble()
        val zMax = (U - (n - 1) * s).toDouble()

        val y = FloatArray(n)
        for (i in 0 until n) {
            val clampedZ = if (zMax >= zMin) z[i].coerceIn(zMin, zMax) else z[i]
            y[i] = (clampedZ + i * s).toFloat()
        }
        return y
    }

    fun layoutDayAssignments(
        items: List<ScheduleAssignmentItem>,
        params: LayoutParams,
        slotLeft: Float,
        slotRight: Float,
    ): List<PlacedItem> {
        if (items.isEmpty()) return emptyList()

        val L = params.L
        val U = params.U
        if (U <= L) return emptyList()

        val sorted = items.sortedWith(
            compareBy<ScheduleAssignmentItem>(
                { parseMinutes(it.deadlineTimeShort) ?: Int.MAX_VALUE },
                { it.id }
            )
        )

        val n = sorted.size
        val h = params.ribbonHeight
        val g = params.minGap
        val availableHeight = U - L

        val groups: List<PlacedGroup>
        val s: Float

        if (L + (n - 1) * (h + g) <= U) {
            s = h + g
            groups = sorted.map { PlacedGroup.Single(it) }
        } else if (L + (n - 1) * h <= U) {
            s = if (n > 1) availableHeight / (n - 1) else h
            groups = sorted.map { PlacedGroup.Single(it) }
        } else {
            val maxFit = (availableHeight / h).toInt() + 1
            if (maxFit >= 2) {
                val singleCount = maxFit - 1
                val singles = sorted.take(singleCount).map { PlacedGroup.Single(it) }
                val overflow = PlacedGroup.Overflow(sorted.drop(singleCount))
                groups = singles + overflow
                s = availableHeight / (maxFit - 1)
            } else if (maxFit == 1) {
                groups = listOf(PlacedGroup.Overflow(sorted))
                s = 0f
            } else {
                return emptyList()
            }
        }

        val p = FloatArray(groups.size)
        for (i in groups.indices) {
            p[i] = when (val grp = groups[i]) {
                is PlacedGroup.Single -> {
                    val minutes = parseMinutes(grp.item.deadlineTimeShort) ?: 0
                    val continuousSlot = timeToContinuousSlot(minutes, params.slotTimes)
                    calculateIdealCenterY(continuousSlot, params.bodyTop, params.rowHeight, L, U)
                }
                is PlacedGroup.Overflow -> {
                    val firstMinutes = parseMinutes(grp.items.first().deadlineTimeShort) ?: 0
                    val continuousSlot = timeToContinuousSlot(firstMinutes, params.slotTimes)
                    calculateIdealCenterY(continuousSlot, params.bodyTop, params.rowHeight, L, U)
                }
            }
        }

        val solvedY = solvePava(p, s, L, U)

        val result = ArrayList<PlacedItem>(groups.size)
        val m = groups.size
        for (i in 0 until m) {
            val y = solvedY[i]
            val tagTop = y - h / 2f
            val tagBottom = y + h / 2f

            val desiredTop = tagTop - params.touchPadding
            val desiredBottom = tagBottom + params.touchPadding

            val minTop = if (i == 0) params.bodyTop else (solvedY[i - 1] + y) / 2f
            val maxBottom = if (i == m - 1) params.bodyBottom else (y + solvedY[i + 1]) / 2f

            val hitTop = maxOf(desiredTop, minTop)
            val hitBottom = minOf(desiredBottom, maxBottom)
            val hitRect = LayoutRect(slotLeft, hitTop, slotRight, hitBottom)

            val placed = when (val grp = groups[i]) {
                is PlacedGroup.Single -> PlacedItem.Single(
                    assignment = grp.item,
                    idealY = p[i],
                    y = y,
                    hitRect = hitRect,
                )
                is PlacedGroup.Overflow -> PlacedItem.Overflow(
                    assignments = grp.items,
                    idealY = p[i],
                    y = y,
                    hitRect = hitRect,
                )
            }
            result.add(placed)
        }
        return result
    }

    private sealed class PlacedGroup {
        data class Single(val item: ScheduleAssignmentItem) : PlacedGroup()
        data class Overflow(val items: List<ScheduleAssignmentItem>) : PlacedGroup()
    }
}
