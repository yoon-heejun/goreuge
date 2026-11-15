package io.cellbalance.app

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.car.hardware.property.EvChargeState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val canvasColor = Color.parseColor("#F2F3F1")
private val surface = Color.WHITE
private val border = Color.parseColor("#DCE1E7")
private val ink = Color.parseColor("#1D2938")
private val muted = Color.parseColor("#526173")
private val blue = Color.parseColor("#245AC3")
private val tint = Color.parseColor("#EAF1FF")

private enum class Page { HOME, HISTORY, TOOLS, VEHICLE }
private enum class Step { PROPOSED, SCHEDULED, ARRIVED, CHARGING, COMPLETE, INTERRUPTED }

class MainActivity : Activity() {
    private val firstDemoDate = LocalDate.of(2026, 11, 13)
    private var demoDate = firstDemoDate
    private var dailyKm = 23
    private var weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
    private var target = OneTimeTarget(80)
    private val plan get() = planFor(demoDate)
    private fun planFor(date: LocalDate) = MonthlyPlan.propose(
        date, 35, dailyKm, 4.0..4.5, weekdays.map { ChargeWindow(it) }
    )
    private val dateFormat = DateTimeFormatter.ofPattern("M월 d일 (EEE)", Locale.KOREAN)
    private var step = Step.PROPOSED
    private var page = Page.HOME
    private var soc = 35
    private var interruption = ""
    private var history = emptyList<Completion>()
    private val lastCompleted get() = history.firstOrNull()?.date
    private var sourceMessage = "Pleos에서 현재 잔량을 읽을 수 있습니다."
    private var targetMessage = ""
    private var sdkSoc: Int? = null
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("monthly_routine_demo", MODE_PRIVATE) }
    private lateinit var body: LinearLayout
    private lateinit var font: Typeface
    private lateinit var sdk: PleosBattery
    private lateinit var linked: LinkedCharge
    private var routine: ChargeRoutine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sdk = PleosBattery(this)
        linked = LinkedCharge.get(this)
        font = resources.getFont(R.font.pretendard)
        demoDate = prefs.getString("date", null)?.let(LocalDate::parse) ?: firstDemoDate
        dailyKm = prefs.getInt("dailyKm", 23)
        target = OneTimeTarget(prefs.getInt("usual", 80))
        weekdays = prefs.getStringSet("weekdays", null)?.map(DayOfWeek::valueOf)?.toSet() ?: weekdays
        history = ChargeHistory.read(prefs.getString("chargeHistory", null), prefs.getString("lastCompleted", null))
        val saved = prefs.getString("step", null)
        step = when (saved) {
            Step.SCHEDULED.name, Step.ARRIVED.name -> Step.SCHEDULED
            Step.COMPLETE.name -> Step.COMPLETE
            Step.CHARGING.name, Step.INTERRUPTED.name -> Step.INTERRUPTED
            else -> Step.PROPOSED
        }
        when (step) {
            Step.COMPLETE -> soc = 100
            Step.INTERRUPTED -> {
                interruption = "시연이 중단됐습니다. 평소 목표를 복원했습니다."
                saveStep()
            }
            else -> Unit
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        linked.listener = {
            if (page == Page.VEHICLE || page == Page.HISTORY) render()
        }
        linked.attach()
    }

    override fun onStop() {
        super.onStop()
        linked.listener = null
        linked.detach()
        sdk.close()
        if (step == Step.CHARGING) endEarly("시연이 중단됐습니다. 평소 목표를 복원했습니다.")
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        sdk.close()
        super.onDestroy()
    }

    private fun saveStep() {
        prefs.edit().putString("step", step.name).putString("date", demoDate.toString())
            .putString("chargeHistory", ChargeHistory.serialize(history)).remove("lastCompleted").putInt("dailyKm", dailyKm).putInt("usual", target.usual)
            .putStringSet("weekdays", weekdays.map { it.name }.toSet()).apply()
    }

    private fun navigate(destination: Page) {
        if (page == Page.VEHICLE) sdk.close()
        page = destination
        render()
        if (destination == Page.VEHICLE && linked.supported) requestVehicleRead()
    }

    private fun startCharging() {
        if (step != Step.ARRIVED || soc > 20) return
        val engine = ChargeRoutine(checkNotNull(plan.date))
        routine = engine
        engine.accept(ChargeFrame(0, soc, Feed.IDLE))
        engine.accept(ChargeFrame(1, soc, Feed.AC, "monthly-demo"))
        target.update(engine.state.stage)
        step = Step.CHARGING
        saveStep()
        render()
        listOf(31, 48, 65, 82, 100).forEachIndexed { index, value ->
            main.postDelayed({
                if (step != Step.CHARGING) return@postDelayed
                val feed = if (value == 100) Feed.FINISHED else Feed.AC
                engine.accept(ChargeFrame((index + 1) * 60, value, feed, "monthly-demo"))
                soc = value
                target.update(engine.state.stage)
                val completion = engine.completion
                if (completion != null) {
                    history = ChargeHistory.add(history, completion)
                    step = Step.COMPLETE
                    saveStep()
                }
                if (page == Page.HOME || (page == Page.HISTORY && completion != null)) render()
            }, 1550L * (index + 1))
        }
    }

    private fun endEarly(message: String, missing: Boolean = false) {
        if (step != Step.CHARGING) return
        main.removeCallbacksAndMessages(null)
        val minute = (routine?.history?.lastOrNull()?.minute ?: 0) + 1
        routine?.accept(
            if (missing) ChargeFrame(minute, null, Feed.MISSING)
            else ChargeFrame(minute, soc, Feed.FINISHED, "monthly-demo")
        )
        target.restore()
        interruption = message
        step = Step.INTERRUPTED
        saveStep()
        render()
    }

    private fun reset(date: LocalDate = firstDemoDate) {
        main.removeCallbacksAndMessages(null)
        target.restore()
        routine = null
        demoDate = date
        step = Step.PROPOSED
        soc = 35
        saveStep()
        navigate(Page.HOME)
    }

    private fun render() {
        val outer = FrameLayout(this).apply { setBackgroundColor(canvasColor) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val width = resources.configuration.screenWidthDp
        val wide = width >= 690
        body = column().apply { pad(this, if (wide) 32 else 18, 26) }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        outer.addView(scroll, FrameLayout.LayoutParams(dp(minOf(width, 1120)), -1, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        setContentView(outer)
        when (page) {
            Page.HOME -> home(wide)
            Page.HISTORY -> historyPage(wide)
            Page.TOOLS -> tools()
            Page.VEHICLE -> vehicle()
        }
    }

    private fun home(wide: Boolean) {
        val header = if (wide) row() else column()
        val title = label("고르게", 32, bold = true)
        if (wide) header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        else add(header, title)
        val links = row()
        fun shortcut(title: String, destination: Page) {
            val layout = if (wide) LinearLayout.LayoutParams(-2, -2)
                else LinearLayout.LayoutParams(0, -2, 1f)
            if (links.childCount > 0) layout.marginStart = dp(8)
            val shortcut = button(title) { navigate(destination) }
            if (!wide) {
                shortcut.textSize = 16f
                pad(shortcut, 5, 8)
            }
            links.addView(shortcut, layout)
        }
        shortcut("완충 이력", Page.HISTORY)
        shortcut("차량 데이터", Page.VEHICLE)
        shortcut("시연 도구", Page.TOOLS)
        if (wide) header.addView(links) else add(header, links, 10)
        add(body, header)

        val context = row()
        context.addView(label("월간 완충 계획", 23, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        context.addView(label("모의 운행", 17, muted, true).apply {
            background = round(surface, 24)
            pad(this, 14, 7)
        })
        add(body, context, 23)

        val hero = card(26)
        val plan = plan
        val date = plan.date
        val dateLabel = date?.let { "${it.format(dateFormat)} ${plan.hour}:00" }
        val usual = target.usual
        val headline = when (step) {
            Step.PROPOSED, Step.SCHEDULED -> dateLabel ?: "추천 일정 없음"
            Step.ARRIVED -> "18% · 완속 충전 가능"
            Step.CHARGING -> "완속 충전 중"
            Step.COMPLETE -> "이번 달 완충 완료"
            Step.INTERRUPTED -> "완충 계획 다시 잡기"
        }
        val eyebrow = when (step) {
            Step.PROPOSED -> if (date == null) "이번 주 완충 계획" else "추천 완속 충전"
            Step.SCHEDULED -> "저장한 완속 충전 일정"
            Step.ARRIVED -> "충전 장소에 도착"
            Step.CHARGING -> "이번 회차 충전 목표 100%"
            Step.COMPLETE -> "18% → 100% · 평소 목표 ${usual}% 복원"
            Step.INTERRUPTED -> "평소 목표 ${usual}% 복원"
        }
        val description = when (step) {
            Step.PROPOSED, Step.SCHEDULED -> plan.expectedSoc?.let {
                "집 완속 충전 · 도착 예상 잔량 ${it.first}~${it.last}%"
            } ?: "등록한 완속 충전일과 예상 잔량이 맞는 날이 없어요. 평소대로 충전하세요."
            Step.ARRIVED -> "이 회차에만 100%로 올리고 충전이 끝나면 ${usual}%로 돌아갑니다."
            Step.CHARGING -> "현재 잔량 ${soc}% · 완료 또는 중단 후 평소 목표로 돌아갑니다."
            Step.COMPLETE -> "다음 완충 시점 ${ChargeRoutine.nextSuggested(checkNotNull(date)).format(dateFormat)} 전후"
            Step.INTERRUPTED -> interruption
        }
        add(hero, label(eyebrow, 20, blue, true))
        add(hero, label(headline, if (wide) 48 else 34, bold = true), 4)
        add(hero, label(description, 22, muted), 8)
        when (step) {
            Step.PROPOSED -> if (date != null) add(hero, button("이 일정으로 예약", true) {
                step = Step.SCHEDULED
                saveStep()
                render()
            }, 20)
            Step.SCHEDULED -> add(hero, label("충전기에 도착하면 실제 잔량과 완속 연결을 확인합니다.", 19, muted), 14)
            Step.ARRIVED -> add(hero, button("이번만 100% 충전", true, ::startCharging), 20)
            Step.INTERRUPTED -> add(hero, button("계획 다시 보기", true) { reset(demoDate) }, 20)
            else -> Unit
        }
        add(body, hero, 14)

        val charge = card()
        add(charge, label("배터리 잔량", 22, bold = true))
        add(charge, label("${soc}%", 46, ink, true), 4)
        add(charge, label(
            if (step in listOf(Step.PROPOSED, Step.SCHEDULED))
                "20%까지 약 ${plan.distanceToTwenty.first}~${plan.distanceToTwenty.last} km"
            else if (step == Step.COMPLETE) "18%에서 시작한 완속 충전"
            else "시연 차량의 현재 잔량", 20, muted), 5)

        val limit = card()
        add(limit, label("현재 충전 목표", 22, bold = true))
        add(limit, label("${target.current}%", 46, if (step == Step.CHARGING) blue else ink, true), 4)
        add(limit, label(
            when (step) {
                Step.CHARGING -> "이번 회차만 적용 · 평소 ${target.usual}%"
                Step.COMPLETE, Step.INTERRUPTED -> "평소 목표로 복원됨"
                else -> "평소 완속 충전 목표"
            }, 20, muted), 5)
        if (wide) {
            val cards = row()
            cards.addView(charge, LinearLayout.LayoutParams(0, -2, 1f))
            cards.addView(limit, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
            add(body, cards, 16)
        } else {
            add(body, charge, 14)
            add(body, limit, 12)
        }

        val note = if (step == Step.PROPOSED || step == Step.SCHEDULED)
            "하루 ${dailyKm} km 주행 가정 · 예상과 달라지면 평소대로 충전하세요."
        else "아이오닉 5 사용설명서 기준 · 20% 이하에서 완속으로 100%까지"
        add(body, label(note, 18, muted), 18)
        if (step != Step.COMPLETE) lastCompleted?.let {
            add(body, label("최근 완충 ${it.format(dateFormat)}", 18, muted), 8)
        }
    }

    private fun historyPage(wide: Boolean) {
        val dateWithYear = DateTimeFormatter.ofPattern("yyyy년 M월 d일 (EEE)", Locale.KOREAN)
        add(body, button("‹  완충 계획") { navigate(Page.HOME) })
        add(body, label("완충 이력", 34, bold = true), 22)
        if (linked.history.isNotEmpty()) {
            add(body, label("Pleos 목표 연동 시험 기록", 20, muted), 9)
            linked.history.forEach { entry ->
                add(body, card().apply {
                    add(this, label(entry.date.format(dateWithYear), 24, bold = true))
                    add(this, label("${entry.startSoc}% → 100%", 27, blue, true), 4)
                }, 10)
            }
        }
        add(body, label("모의 완속 충전 기록", 20, muted), 12)
        if (history.isEmpty()) {
            add(body, card().apply {
                add(this, label("아직 완료된 기록이 없어요", 26, bold = true))
                add(this, label("완충을 마치면 여기에 기록됩니다.", 20, muted), 6)
            }, 18)
            return
        }
        history.forEach { entry ->
            val details = if (wide) row() else column()
            val date = label(entry.date.format(dateWithYear), 24, bold = true)
            val charge = label("${entry.startSoc}% → 100%", 27, blue, true)
            if (wide) {
                details.addView(date, LinearLayout.LayoutParams(0, -2, 1f))
                details.addView(charge)
            } else {
                add(details, date)
                add(details, charge, 4)
            }
            add(body, card().apply { add(this, details) }, 12)
        }
    }

    private fun tools() {
        add(body, button("‹  완충 계획") { navigate(Page.HOME) })
        add(body, label("시연 도구", 34, bold = true), 22)
        add(body, label("가상 날짜와 차량 신호를 바꾸는 정차 시연 화면입니다.", 21, muted), 7)
        val actions = card()
        when (step) {
            Step.SCHEDULED -> {
                add(actions, button("충전일 도착 · 18%", true) {
                    step = Step.ARRIVED
                    soc = 18
                    saveStep()
                    navigate(Page.HOME)
                })
                add(actions, button("충전일 도착 · 24%") {
                    interruption = "잔량이 24%입니다. 평소대로 충전하고 다음 기회를 찾아보세요."
                    soc = 24
                    step = Step.INTERRUPTED
                    saveStep()
                    navigate(Page.HOME)
                }, 12)
            }
            Step.CHARGING -> {
                add(actions, button("완속 충전 중단", true) {
                    page = Page.HOME
                    endEarly("완속 충전이 중단됐습니다. 이번 달 기록은 갱신하지 않았습니다.")
                })
                add(actions, button("충전 신호 끊김") {
                    page = Page.HOME
                    endEarly("충전 신호가 끊겼습니다. 이번 달 기록은 갱신하지 않았습니다.", true)
                }, 12)
            }
            Step.COMPLETE -> add(actions, button("다음 달 계획 보기", true) {
                val due = ChargeRoutine.nextSuggested(checkNotNull(plan.date))
                val nextDemoDate = (-7L..0L).map(due::plusDays)
                    .firstOrNull { start -> planFor(start).date?.let { !it.isBefore(due) } == true }
                reset(nextDemoDate ?: due)
            })
            else -> add(actions, label("예약 후 이 화면에서 충전일 도착을 시연할 수 있습니다.", 21, muted))
        }
        if (step == Step.PROPOSED || step == Step.INTERRUPTED) {
            add(actions, button("운행·충전 조건 변경", action = ::configurePlan), 12)
        }
        add(actions, button("처음부터 다시 시연") {
            reset()
        }, 12)
        add(body, actions, 20)
    }

    private fun configurePlan() {
        val form = column().apply { pad(this, 22, 8) }
        fun number(title: String, initial: Int): EditText {
            add(form, label(title, 18, muted, true), 10)
            return EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(String.format(Locale.KOREAN, "%d", initial))
                add(form, this)
            }
        }
        val distance = number("하루 평균 주행 거리 (km)", dailyKm)
        val limit = number("평소 완속 목표 (%)", target.usual)
        val days = DayOfWeek.entries
        val korean = days.map { it.getDisplayName(java.time.format.TextStyle.SHORT, Locale.KOREAN) }
        val chosen = weekdays.toMutableSet()
        add(form, button("완속 충전 가능한 요일") {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert).setTitle("완속 충전 가능한 요일")
                .setMultiChoiceItems(korean.toTypedArray(), days.map { it in chosen }.toBooleanArray()) { _, index, checked ->
                    if (checked) chosen.add(days[index]) else chosen.remove(days[index])
                }
                .setPositiveButton("확인", null).show()
        }, 8)
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert).setTitle("운행·충전 조건")
            .setView(form).setNegativeButton("취소", null).setPositiveButton("저장", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val km = distance.text.toString().toIntOrNull()
                val percent = limit.text.toString().toIntOrNull()
                if (km == null || km !in 1..300) {
                    distance.error = "1~300 km를 입력하세요."
                    return@setOnClickListener
                }
                if (percent == null || percent !in 50..100) {
                    limit.error = "50~100%를 입력하세요."
                    return@setOnClickListener
                }
                dailyKm = km
                target = OneTimeTarget(percent)
                weekdays = chosen.toSet()
                dialog.dismiss()
                reset(demoDate)
            }
        }
        dialog.show()
    }

    private fun requestVehicleRead() {
        val needed = listOf("android.car.permission.CAR_ENERGY", "android.car.permission.CAR_POWERTRAIN")
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) linked.refresh() else requestPermissions(needed.toTypedArray(), 41)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 41 && page == Page.VEHICLE) linked.refresh()
    }

    private fun linkedVehicle() {
        val target = linked.snapshot?.target
        val input = linked.inputs
        val actual = linked.snapshot?.inputs
        val summary = card()
        add(summary, label("Pleos 에뮬레이터 · 차량 목표 연동", 20, blue, true))
        add(summary, label(target?.let { "차량 충전 목표 ${it}%" } ?: "차량 충전 목표 확인 불가", 34, bold = true), 5)
        add(summary, label(linked.status, 21, muted), 6)
        val connected = if (actual?.connected == true) "케이블 연결" else if (actual?.connected == false) "케이블 미연결" else "케이블 확인 불가"
        add(summary, label("차량 잔량 ${actual?.soc?.let { "$it%" } ?: "—"} · $connected", 20, muted), 8)
        add(summary, button("차량 상태 새로고침", action = ::requestVehicleRead).apply { isEnabled = !linked.busy }, 12)
        if (linked.pending) {
            add(summary, button("원래 목표로 복원", true) { linked.restore() }.apply { isEnabled = !linked.busy }, 10)
        } else if (input?.ready == true && target != null && target < 100 && linked.snapshot?.parked == true) {
            add(summary, button("이번만 차량 목표 100%", true) { linked.begin() }.apply { isEnabled = !linked.busy }, 10)
        }
        add(body, summary, 16)

        val test = card()
        add(test, label("충전 신호 시험", 23, bold = true))
        add(test, label("시험 신호로 충전 상태를 재현합니다. 목표값은 Pleos 차량 서비스에서 변경됩니다.", 19, muted), 7)
        linked.testInputs?.let {
            add(test, label("시험 잔량 ${it.soc}% · ${if (it.connected == true) "연결" else "분리"}", 20, blue, true), 8)
        }
        if (linked.active) {
            add(test, button("시험 · 100% 도달") {
                linked.test(ChargeInputs(100, true, EvChargeState.STATE_FULLY_CHARGED))
            }.apply { isEnabled = !linked.busy }, 10)
            add(test, button("시험 · 케이블 분리") {
                linked.test(ChargeInputs(linked.inputs?.soc ?: 18, false, EvChargeState.STATE_NOT_CHARGING))
            }.apply { isEnabled = !linked.busy }, 10)
        } else if (!linked.pending) {
            add(test, button("시험 · 18%에서 충전 시작") {
                linked.test(ChargeInputs(18, true, EvChargeState.STATE_CHARGING))
            }.apply { isEnabled = !linked.busy }, 10)
            if (linked.testInputs != null) add(test, button("차량 충전 신호 다시 읽기") { linked.test(null) }, 10)
        }
        add(body, test, 14)
    }

    private fun vehicle() {
        add(body, button("‹  완충 계획") { navigate(Page.HOME) })
        add(body, label("차량 데이터", 34, bold = true), 22)
        if (linked.supported) linkedVehicle()
        val card = card()
        add(card, label("공개 Pleos Vehicle SDK", 20, blue, true))
        add(card, label(sdkSoc?.let { "$it%" } ?: "—", 54, bold = true), 5)
        add(card, label(sourceMessage, 21, muted))
        add(card, button("현재 잔량 조회", true) {
            sdkSoc = null
            sourceMessage = "차량 잔량 확인 중"
            render()
            sdk.read { value, message ->
                sdkSoc = value
                sourceMessage = message
                if (page == Page.VEHICLE) render()
            }
        }, 14)
        add(card, button("충전 목표 지원 확인") {
            targetMessage = "지원 여부 확인 중"
            render()
            sdk.checkTarget { message ->
                targetMessage = message
                if (page == Page.VEHICLE) render()
            }
        }, 9)
        if (targetMessage.isNotEmpty()) add(card, label(targetMessage, 20, muted), 12)
        add(body, card, 18)
    }

    private fun dp(size: Int) = (size * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun round(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1).coerceAtLeast(1), it) }
    }
    private fun pad(view: View, horizontal: Int, vertical: Int) =
        view.setPadding(dp(horizontal), dp(vertical), dp(horizontal), dp(vertical))
    private fun card(padding: Int = 23) = column().apply {
        background = round(surface, 24, border)
        pad(this, padding, padding)
    }
    private fun label(value: String, size: Int, color: Int = ink, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            typeface = Typeface.create(font, if (bold) 650 else 400, false)
        }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 19f
        isAllCaps = false
        minimumHeight = dp(58)
        minHeight = dp(58)
        pad(this, 17, 9)
        typeface = Typeface.create(font, 650, false)
        setTextColor(if (primary) surface else ink)
        background = RippleDrawable(
            ColorStateList.valueOf(if (primary) 0x33FFFFFF else tint),
            round(if (primary) blue else surface, 17, if (primary) null else border),
            null
        )
        setOnClickListener { action() }
    }
    private fun add(parent: LinearLayout, view: View, top: Int = 0) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }
}
