package io.cellbalance.app

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ChargeHistoryTest {
    @Test fun importsVersionTwoRecordAndKeepsMultipleMonthsOnReload() {
        val november = Completion(LocalDate.of(2026, 11, 16), 18)
        val december = Completion(LocalDate.of(2026, 12, 17), 20)
        val migrated = ChargeHistory.read(null, "2026-11-16")
        assertEquals(listOf(november), migrated)
        val history = ChargeHistory.add(ChargeHistory.add(migrated, december), december)
        assertEquals(listOf(december, november), ChargeHistory.read(ChargeHistory.serialize(history), null))
    }

    @Test fun skipsMalformedAndIneligibleRecords() {
        assertEquals(
            listOf(Completion(LocalDate.of(2026, 11, 16), 18)),
            ChargeHistory.read("bad|18\n2026-12-17|24\n2026-11-16|18", null)
        )
    }
}
