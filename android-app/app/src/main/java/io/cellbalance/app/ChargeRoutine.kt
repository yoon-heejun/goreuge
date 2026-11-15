package io.cellbalance.app

import java.time.LocalDate

enum class Feed { IDLE, AC, DC, FINISHED, MISSING }
enum class Stage { READY, TRACKING, COMPLETE, UNVERIFIED, ENDED }
data class ChargeFrame(val minute: Int, val soc: Int?, val feed: Feed, val session: String? = null)
data class ChargeEvent(val minute: Int, val message: String)
data class Completion(val date: LocalDate, val startSoc: Int)
data class ChargeState(val stage: Stage, val reason: String)

class ChargeRoutine(private val today: LocalDate) {
    companion object {
        fun nextSuggested(last: LocalDate): LocalDate = last.plusMonths(1)
    }

    private var last: ChargeFrame? = null
    private var activeId: String? = null
    private var startSoc: Int? = null
    private val log = mutableListOf<ChargeEvent>()
    private val points = mutableListOf<ChargeFrame>()
    val events: List<ChargeEvent> get() = log.toList()
    val history: List<ChargeFrame> get() = points.toList()
    var state = ChargeState(Stage.READY, "완속 충전을 연결하면 시작 잔량을 확인합니다.")
        private set
    var completion: Completion? = null
        private set

    fun accept(frame: ChargeFrame) {
        val previous = last
        if (completion != null || (previous != null && frame.minute <= previous.minute)) return
        last = frame
        points += frame
        val soc = frame.soc
        when {
            soc == null || soc !in 0..100 || frame.feed == Feed.MISSING -> {
                invalidate()
                update(frame, Stage.UNVERIFIED, "신호가 끊겨 이 충전을 확인할 수 없습니다.", "충전 신호 확인 불가")
            }
            frame.feed == Feed.IDLE -> {
                val hadSession = activeId != null
                invalidate()
                if (hadSession) update(frame, Stage.ENDED, "100% 도달 전에 충전 연결이 종료됐습니다.", "충전 연결 종료")
                else update(frame, Stage.READY,
                    if (soc <= 20) "이미 20% 이하입니다. 충전 여건이 되면 완속으로 연결하세요."
                    else "다음에 잔량이 자연스럽게 20% 이하가 되면 완속 충전을 고려하세요.")
            }
            frame.feed == Feed.DC -> {
                invalidate()
                update(frame, Stage.UNVERIFIED, "급속 충전입니다. 이번 권장 완충에는 기록하지 않습니다.", "급속 충전 · 기록 제외")
            }
            frame.feed == Feed.AC && previous?.feed == Feed.IDLE && !frame.session.isNullOrBlank() -> {
                if (soc <= 20) {
                    activeId = frame.session
                    startSoc = soc
                    update(frame, Stage.TRACKING, "${soc}%에서 시작한 완속 충전을 추적합니다.", "${soc}% · 완속 충전 시작")
                } else {
                    invalidate()
                    update(frame, Stage.UNVERIFIED, "시작 잔량이 20%를 초과해 이번 권장 완충에는 기록하지 않습니다.", "${soc}% 시작 · 기록 제외")
                }
            }
            activeId != null && frame.session == activeId -> {
                if (soc == 100) {
                    val start = checkNotNull(startSoc)
                    completion = Completion(today, start)
                    update(frame, Stage.COMPLETE, "${start}%에서 시작해 100%에 도달했습니다.", "100% 도달 · 자동 기록")
                    invalidate()
                } else if (frame.feed == Feed.FINISHED) {
                    invalidate()
                    update(frame, Stage.ENDED, "${soc}%에서 충전이 끝나 완료로 기록하지 않습니다.", "${soc}% 종료 · 기록하지 않음")
                }
            }
            else -> {
                invalidate()
                if (state.stage != Stage.UNVERIFIED) {
                    update(frame, Stage.UNVERIFIED, "완속 시작부터 같은 충전을 확인하지 못했습니다.", "충전 시작 또는 연결 확인 불가")
                }
            }
        }
    }

    private fun invalidate() { activeId = null; startSoc = null }
    private fun update(frame: ChargeFrame, stage: Stage, reason: String, event: String? = null) {
        if (event != null && (stage != state.stage || reason != state.reason)) log += ChargeEvent(frame.minute, event)
        state = ChargeState(stage, reason)
    }
}

enum class Scenario(val title: String, val detail: String, val button: String) {
    SUCCESS("권장 완충", "18%에서 완속 충전 시작", "완속 연결 시연"),
    EARLY("80% 종료", "100%에 도달하지 못한 충전", "80% 종료 시연"),
    GAP("신호 끊김", "추적 도중 데이터를 받지 못함", "신호 끊김 시연"),
    DC("급속 충전", "충전 방식이 권고와 다름", "급속 연결 시연"),
    HIGH("24% 시작", "시작 잔량이 권고 범위를 초과", "24% 시작 시연");

    fun frames(): List<ChargeFrame> {
        val start = if (this == HIGH) 24 else 18
        fun ac(min: Int, soc: Int) = ChargeFrame(min, soc, Feed.AC, "demo")
        fun dc(min: Int, soc: Int) = ChargeFrame(min, soc, Feed.DC, "demo")
        fun end(min: Int, soc: Int) = ChargeFrame(min, soc, Feed.FINISHED, "demo")
        val charging = when (this) {
            SUCCESS -> listOf(ac(1,start), ac(45,29), ac(100,44), ac(160,59), ac(220,74), ac(280,89), end(340,100))
            EARLY -> listOf(ac(1,start), ac(60,33), ac(145,54), ac(210,72), end(245,80))
            GAP -> listOf(ac(1,start), ac(60,32), ac(135,51), ChargeFrame(160,null,Feed.MISSING), ac(225,77), end(340,100))
            DC -> listOf(dc(1,start), dc(15,45), dc(30,72), dc(50,94), dc(55,100))
            HIGH -> listOf(ac(1,start), ac(65,39), ac(145,57), ac(240,79), end(330,100))
        }
        return listOf(ChargeFrame(0,start,Feed.IDLE)) + charging
    }
}
