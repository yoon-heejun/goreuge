package io.cellbalance.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import android.content.Context
import android.os.Handler
import android.os.Looper

/** Public Pleos Vehicle SDK calls used by the foreground vehicle screen. */
class PleosBattery(context: Context) {
    private val vehicle = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var initialized = false

    fun read(result: (Int?, String) -> Unit) {
        val request = ++generation
        var finished = false
        fun done(soc: Int?, label: String) {
            main.post {
                if (generation != request || finished) return@post
                finished = true
                result(soc, label)
            }
        }
        main.postDelayed({ done(null, "차량 SDK 응답 시간이 초과됐습니다.") }, 6500)
        try {
            if (!initialized) {
                vehicle.initialize()
                initialized = true
            }
            val ev = vehicle.getEvBattery()
            ev.checkEvBatteryStateOfChargeCapabilities(
                { capability ->
                    main.post {
                        if (generation != request || finished) return@post
                        if (capability.accessType !in setOf(AccessType.READ, AccessType.READ_WRITE)) {
                            done(null, "이 차량에서 배터리 잔량 조회를 지원하지 않습니다.")
                            return@post
                        }
                        ev.getEvBatteryStateOfCharge(
                            { soc ->
                                if (soc in 0..100) done(soc, "Pleos Vehicle SDK에서 읽은 차량 잔량")
                                else done(null, "차량 잔량 값이 유효 범위를 벗어났습니다.")
                            },
                            { error -> done(null, "차량 잔량 조회 실패 · ${error.javaClass.simpleName}") }
                        )
                    }
                },
                { error -> done(null, "차량 잔량 지원 확인 실패 · ${error.javaClass.simpleName}") }
            )
        } catch (e: RuntimeException) {
            done(null, "차량 서비스에 연결할 수 없습니다.")
        }
    }

    fun checkTarget(result: (String) -> Unit) {
        val request = ++generation
        var finished = false
        fun done(message: String) {
            main.post {
                if (generation != request || finished) return@post
                finished = true
                result(message)
            }
        }
        main.postDelayed({ done("충전 목표 지원 확인 시간이 초과됐습니다.") }, 6500)
        try {
            if (!initialized) {
                vehicle.initialize()
                initialized = true
            }
            vehicle.getEvBattery().checkEvChargePercentLimitCapabilities(
                { capability, values ->
                    val access = when (capability.accessType) {
                        AccessType.READ_WRITE -> "읽기·쓰기"
                        AccessType.WRITE -> "쓰기"
                        AccessType.READ -> "읽기"
                        else -> "지원하지 않음"
                    }
                    val levels = values.joinToString(", ") { "${it.toInt()}%" }
                    done("충전 목표 권한: $access" + if (levels.isEmpty()) "" else " · $levels")
                },
                { error -> done("충전 목표 지원 확인 실패 · ${error.javaClass.simpleName}") }
            )
        } catch (e: RuntimeException) {
            done("차량 서비스에 연결할 수 없습니다.")
        }
    }

    fun writeTarget(percent: Int, result: (Boolean) -> Unit) {
        val request = ++generation
        var finished = false
        fun done(success: Boolean) {
            main.post {
                if (generation == request && !finished) {
                    finished = true
                    result(success)
                }
            }
        }
        main.postDelayed({ done(false) }, 6500)
        try {
            if (!initialized) {
                vehicle.initialize()
                initialized = true
            }
            vehicle.getEvBattery().setEvChargePercentLimit(
                percent.toFloat(), { value -> done(value == percent.toFloat()) }, { done(false) }
            )
        } catch (_: RuntimeException) {
            done(false)
        }
    }

    fun close() {
        generation++
        main.removeCallbacksAndMessages(null)
        if (initialized) runCatching { vehicle.release() }
        initialized = false
    }
}
