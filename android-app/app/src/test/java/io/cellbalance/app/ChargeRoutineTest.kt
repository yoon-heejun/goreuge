package io.cellbalance.app

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ChargeRoutineTest {
    private val date = LocalDate.of(2026, 11, 13)
    private fun run(s: Scenario) = ChargeRoutine(date).apply { s.frames().forEach(::accept) }

    @Test fun acFrom18RecordsOnceAt100() {
        val engine = run(Scenario.SUCCESS)
        assertEquals(Stage.COMPLETE, engine.state.stage)
        assertEquals(Completion(date, 18), engine.completion)
        engine.accept(ChargeFrame(350, 100, Feed.FINISHED, "demo"))
        assertEquals(1, engine.events.count { "자동 기록" in it.message })
    }

    @Test fun exactly20QualifiesBut21DoesNot() {
        for (soc in 20..21) {
            val engine = ChargeRoutine(date)
            engine.accept(ChargeFrame(0, soc, Feed.IDLE))
            engine.accept(ChargeFrame(1, soc, Feed.AC, "a"))
            engine.accept(ChargeFrame(360, 100, Feed.FINISHED, "a"))
            assertEquals(soc == 20, engine.completion != null)
        }
    }

    @Test fun at80DoesNotComplete() {
        val engine = run(Scenario.EARLY)
        assertEquals(Stage.ENDED, engine.state.stage)
        assertNull(engine.completion)
    }

    @Test fun missingDataCannotBeReconstructedFrom100() {
        val engine = run(Scenario.GAP)
        assertEquals(Stage.UNVERIFIED, engine.state.stage)
        assertNull(engine.completion)
        assertNull(engine.history.single { it.feed == Feed.MISSING }.soc)
    }

    @Test fun rejectsDcAndStartsAbove20() {
        for (scenario in listOf(Scenario.DC, Scenario.HIGH)) {
            assertEquals(Stage.UNVERIFIED, run(scenario).state.stage)
            assertNull(run(scenario).completion)
        }
    }

    @Test fun sessionChangeOrOpeningMidChargeDoesNotQualify() {
        val a = ChargeRoutine(date)
        a.accept(ChargeFrame(0, 19, Feed.IDLE))
        a.accept(ChargeFrame(1, 19, Feed.AC, "first"))
        a.accept(ChargeFrame(360, 100, Feed.FINISHED, "different"))
        assertNull(a.completion)
        val b = ChargeRoutine(date)
        b.accept(ChargeFrame(0, 19, Feed.AC, "first"))
        b.accept(ChargeFrame(360, 100, Feed.FINISHED, "first"))
        assertNull(b.completion)
    }

    @Test fun ignoresReplayAndInvalidatesOutOfRangeValues() {
        val engine = ChargeRoutine(date)
        engine.accept(ChargeFrame(0, 18, Feed.IDLE))
        engine.accept(ChargeFrame(1, 18, Feed.AC, "a"))
        engine.accept(ChargeFrame(1, 100, Feed.FINISHED, "a"))
        assertEquals(Stage.TRACKING, engine.state.stage)
        engine.accept(ChargeFrame(10, 101, Feed.AC, "a"))
        engine.accept(ChargeFrame(360, 100, Feed.FINISHED, "a"))
        assertNull(engine.completion)
    }

    @Test fun unplugRequiresANewEligibleStart() {
        val engine = ChargeRoutine(date)
        engine.accept(ChargeFrame(0, 18, Feed.IDLE))
        engine.accept(ChargeFrame(1, 18, Feed.AC, "a"))
        engine.accept(ChargeFrame(10, 30, Feed.IDLE))
        assertEquals(Stage.ENDED, engine.state.stage)
        engine.accept(ChargeFrame(11, 30, Feed.AC, "b"))
        engine.accept(ChargeFrame(360, 100, Feed.FINISHED, "b"))
        assertNull(engine.completion)
    }

    @Test fun nextMonthClampsToLastDay() {
        assertEquals(LocalDate.of(2026, 2, 28), ChargeRoutine.nextSuggested(LocalDate.of(2026, 1, 31)))
        assertEquals(LocalDate.of(2026, 12, 13), ChargeRoutine.nextSuggested(date))
    }
}
