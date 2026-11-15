package io.cellbalance.app

import java.time.LocalDate

object ChargeHistory {
    fun read(saved: String?, previousDate: String?): List<Completion> {
        if (saved == null) {
            // Version 0.2 only recorded sessions starting from its fixed 18% demo arrival.
            return parseDate(previousDate)?.let { listOf(Completion(it, 18)) } ?: emptyList()
        }
        return saved.lineSequence().mapNotNull { line ->
            val date = parseDate(line.substringBefore('|'))
            val start = line.substringAfter('|', "").toIntOrNull()
            if (date != null && start != null && start in 0..20) Completion(date, start) else null
        }.distinctBy { it.date }.sortedByDescending { it.date }.toList()
    }

    fun add(history: List<Completion>, completion: Completion): List<Completion> =
        (listOf(completion) + history).distinctBy { it.date }.sortedByDescending { it.date }

    fun serialize(history: List<Completion>): String =
        history.joinToString("\n") { "${it.date}|${it.startSoc}" }

    private fun parseDate(value: String?): LocalDate? =
        value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
