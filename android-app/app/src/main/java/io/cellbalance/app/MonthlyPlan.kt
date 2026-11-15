package io.cellbalance.app

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor

data class ChargeWindow(val weekday: DayOfWeek, val hour: Int = 21)
data class ChargingPlan(
    val distanceToTwenty: IntRange,
    val date: LocalDate?,
    val hour: Int?,
    val expectedSoc: IntRange?
)

object MonthlyPlan {
    fun propose(
        today: LocalDate,
        soc: Int,
        dailyKm: Int,
        kmPerPercent: ClosedFloatingPointRange<Double>,
        windows: List<ChargeWindow>
    ): ChargingPlan {
        require(soc in 0..100 && dailyKm > 0)
        require(kmPerPercent.start > 0 && kmPerPercent.endInclusive.isFinite())
        val remaining = (soc - 20).coerceAtLeast(0)
        val low = floor(remaining * kmPerPercent.start).toInt()
        val high = ceil(remaining * kmPerPercent.endInclusive).toInt()
        val distance = low..high
        for (days in 0..7) {
            val date = today.plusDays(days.toLong())
            val window = windows.firstOrNull { it.weekday == date.dayOfWeek } ?: continue
            val plannedKm = days * dailyKm
            val min = floor(soc - plannedKm / kmPerPercent.start).toInt()
            val max = ceil(soc - plannedKm / kmPerPercent.endInclusive).toInt()
            // The estimate is for planning. The charge session still checks the actual start SOC.
            if (min >= 15 && max <= 20) return ChargingPlan(distance, date, window.hour, min..max)
        }
        return ChargingPlan(distance, null, null, null)
    }
}

/** Local stand-in for the charging target while proving the one-session flow. */
class OneTimeTarget(val usual: Int) {
    init { require(usual in 1..100) }
    var current = usual
        private set

    fun update(state: Stage) {
        current = if (state == Stage.TRACKING) 100 else usual
    }

    fun restore() { current = usual }
}
