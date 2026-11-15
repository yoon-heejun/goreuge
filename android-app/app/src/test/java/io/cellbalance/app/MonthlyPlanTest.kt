package io.cellbalance.app

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonthlyPlanTest {
    private val today = LocalDate.of(2026, 11, 13)

    @Test fun choosesReachableAcDateAndShowsRange() {
        val plan = MonthlyPlan.propose(today, 35, 23, 4.0..4.5,
            listOf(ChargeWindow(DayOfWeek.MONDAY), ChargeWindow(DayOfWeek.THURSDAY)))
        assertEquals(60..68, plan.distanceToTwenty)
        assertEquals(LocalDate.of(2026, 11, 16), plan.date)
        assertEquals(17..20, plan.expectedSoc)
    }

    @Test fun skipsDaysAboveThresholdOrBelowPlanningBuffer() {
        val windows = listOf(ChargeWindow(DayOfWeek.SATURDAY), ChargeWindow(DayOfWeek.THURSDAY))
        assertNull(MonthlyPlan.propose(today, 35, 23, 4.0..4.5, windows).date)
    }

    @Test fun restoresActualUsualTargetOnEveryExit() {
        val target = OneTimeTarget(85)
        for (terminal in listOf(Stage.COMPLETE, Stage.ENDED, Stage.UNVERIFIED)) {
            target.update(Stage.TRACKING)
            assertEquals(100, target.current)
            target.update(terminal)
            assertEquals(85, target.current)
        }
    }
}
