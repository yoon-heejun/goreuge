package io.cellbalance.app

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.hardware.property.EvChargeState
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.time.LocalDate

data class ChargeInputs(val soc: Int?, val connected: Boolean?, val state: Int?) {
    val ready get() = soc != null && soc in 0..20 && connected == true && state == EvChargeState.STATE_CHARGING

    fun end(): Boolean? {
        if (connected == true && soc == 100 && state in listOf(EvChargeState.STATE_CHARGING, EvChargeState.STATE_FULLY_CHARGED)) return true
        if (connected == true && soc != null && state == EvChargeState.STATE_CHARGING) return null
        return false
    }
}

data class ChargeSnapshot(val target: Int?, val parked: Boolean, val inputs: ChargeInputs)

private class VehicleChargeReader(private val context: Context) {
    private var car: Car? = null

    fun snapshot(soc: Int?): ChargeSnapshot {
        return try {
            val connection = car ?: Car.createCar(context).also { car = it }
            val properties = connection.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            val limit = value<Float>(properties, VehiclePropertyIds.EV_CHARGE_PERCENT_LIMIT)
            val target = limit?.toInt()?.takeIf { it in 1..100 && it.toFloat() == limit }
            ChargeSnapshot(
                target,
                value<Int>(properties, VehiclePropertyIds.GEAR_SELECTION) == VehicleGear.GEAR_PARK,
                ChargeInputs(
                    soc,
                    value(properties, VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED),
                    value(properties, VehiclePropertyIds.EV_CHARGE_STATE)
                )
            )
        } catch (_: RuntimeException) {
            ChargeSnapshot(null, false, ChargeInputs(soc, null, null))
        } catch (_: LinkageError) {
            ChargeSnapshot(null, false, ChargeInputs(soc, null, null))
        }
    }

    @Suppress("DEPRECATION") // Android 14 reports availability with getStatus().
    private inline fun <reified T> value(properties: CarPropertyManager, id: Int): T? =
        runCatching { properties.getProperty<Any>(id, 0) }.getOrNull()
            ?.takeIf { it.status == CarPropertyValue.STATUS_AVAILABLE }?.value as? T

    fun close() {
        car?.disconnect()
        car = null
    }
}

/** Owns a single temporary target across activity recreation. */
class LinkedCharge private constructor(context: Context) {
    companion object {
        private var instance: LinkedCharge? = null
        fun get(context: Context): LinkedCharge =
            instance ?: LinkedCharge(context.applicationContext).also { instance = it }
    }

    val supported = Build.FINGERPRINT.startsWith("Pleos/crp_avd_")
    private val preferences = context.getSharedPreferences("linked_charge", Context.MODE_PRIVATE)
    private val sdk = PleosBattery(context)
    private val reader by lazy { VehicleChargeReader(context) }
    private val main = Handler(Looper.getMainLooper())
    private var visible = false
    private var closing = false
    private var owned = false
    private var startSoc: Int? = null
    private var original: Int? = preferences.getInt("restore_target", 0).takeIf { it in 1..99 }
    private var injected: ChargeInputs? = null
    var listener: (() -> Unit)? = null
    var status = "차량 상태를 확인하세요."
        private set
    var snapshot: ChargeSnapshot? = null
        private set
    var busy = false
        private set
    val active get() = owned
    val pending get() = original != null
    val testInputs get() = injected
    val inputs get() = injected ?: snapshot?.inputs
    val history get() = ChargeHistory.read(preferences.getString("history", null), null)

    fun attach() {
        if (!supported) return
        visible = true
        closing = false
        if (pending && !busy) restore(false)
    }

    fun detach() {
        visible = false
        closing = true
        main.removeCallbacks(tick)
        if (active && !busy) restore(false)
        if (!busy && !active) disconnect()
    }

    fun refresh() {
        if (!supported || busy) return
        when {
            active -> observe()
            pending -> restore(false)
            else -> scan {
                if (it.target == null) status = "차량 충전 목표를 읽을 수 없습니다."
                else if (!status.endsWith("복원 확인")) status = "차량 상태를 확인했습니다."
                update()
            }
        }
    }

    fun test(value: ChargeInputs?) {
        if (!supported || busy || (active && value == null)) return
        injected = value
        if (active) observe() else refresh()
    }

    fun begin() {
        if (!supported || busy || pending || inputs?.ready != true) return
        scan { current ->
            val signal = inputs
            val previous = current.target
            if (!visible || closing || !current.parked || previous == null || previous == 100 || signal?.ready != true) {
                status = "P단, 시작 잔량 20% 이하, 충전 중 연결 상태와 현재 목표를 확인하세요."
                update()
                return@scan
            }
            if (!preferences.edit().putInt("restore_target", previous).commit()) {
                status = "복원할 목표를 저장할 수 없어 설정을 유지했습니다."
                update()
                return@scan
            }
            original = previous
            startSoc = signal.soc
            busy = true
            status = "차량 목표를 100%로 설정하는 중"
            update()
            sdk.writeTarget(100) { result ->
                busy = false
                val readback = readTarget()
                if (result && readback == 100) {
                    owned = true
                    status = "이번 회차 차량 목표 100% · 종료 시 ${previous}% 복원"
                    if (closing) restore(false) else {
                        update()
                        schedule()
                    }
                } else {
                    // A timed-out write may have reached the service. Keep the restore marker.
                    restore(false, "100% 적용을 확인하지 못했습니다.")
                }
            }
        }
    }

    fun restore(completed: Boolean = false, reason: String? = null) {
        if (!supported || busy) return
        val previous = original ?: return
        main.removeCallbacks(tick)
        val actual = readTarget()
        if (actual == null || snapshot?.parked != true) {
            status = "복원 대기 · P단에서 차량 상태를 다시 확인하세요."
            owned = false
            update()
            return
        }
        if (actual != 100) {
            finish(false, if (actual == previous) "차량 목표 ${previous}% 확인"
                else "차량에서 설정한 ${actual}% 목표를 유지합니다.")
            return
        }
        busy = true
        status = "차량 목표를 ${previous}%로 복원하는 중"
        update()
        sdk.writeTarget(previous) { result ->
            busy = false
            if (readTarget() == previous) {
                finish(completed, "${reason?.let { "$it " } ?: ""}차량 목표 ${previous}% 복원 확인")
            } else {
                status = if (result) "복원 요청 전송 · 차량 값 재확인 필요"
                    else "차량 목표 복원 필요 · 다시 시도하세요."
                owned = false
                update()
            }
        }
    }

    private fun observe() = scan { current ->
        when {
            current.target == null || !current.parked || current.target != 100 -> restore(false)
            else -> when (inputs?.end()) {
                true -> restore(true)
                false -> restore(false)
                null -> schedule()
            }
        }
        update()
    }

    private val tick = Runnable { if (active && visible && !busy) observe() }
    private fun schedule() {
        main.removeCallbacks(tick)
        if (visible) main.postDelayed(tick, 2500)
    }

    private fun scan(block: (ChargeSnapshot) -> Unit) {
        busy = true
        update()
        sdk.read { soc, _ ->
            val result = reader.snapshot(soc)
            snapshot = result
            busy = false
            if (closing) {
                if (active) restore(false) else disconnect()
            } else block(result)
        }
    }

    private fun readTarget(): Int? {
        snapshot = reader.snapshot(snapshot?.inputs?.soc)
        return snapshot?.target
    }

    private fun finish(completed: Boolean, message: String) {
        val editor = preferences.edit().remove("restore_target")
        val start = startSoc
        if (completed && start != null) {
            val entries = ChargeHistory.add(history, Completion(LocalDate.now(), start))
            editor.putString("history", ChargeHistory.serialize(entries))
        }
        if (!editor.commit()) {
            status = "차량 목표가 복원됐습니다. 저장 기록을 다시 확인하세요."
        } else {
            original = null
            status = if (completed) "완충 완료 · $message" else message
        }
        owned = false
        startSoc = null
        update()
    }

    private fun disconnect() {
        sdk.close()
        reader.close()
    }

    private fun update() {
        listener?.invoke()
        if (!visible && !busy && !active) disconnect()
    }
}
