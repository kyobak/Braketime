package com.conditiondrive.vehicle.platform

import android.car.Car
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * android.car 연결 하나를 나눠 쓴다. uses-library가 선택이라 클래스가 없는 기기에서도 죽지 않아야 한다.
 * 차량 서비스가 재시작되면 수명 콜백이 다시 오므로 구독자는 [car]를 보고 다시 등록한다.
 */
class CarConnection(private val context: Context) {
    private val _car = MutableStateFlow<Car?>(null)
    val car: StateFlow<Car?> = _car.asStateFlow()

    private var handle: Car? = null
    private var users = 0

    @Synchronized
    fun acquire() {
        users++
        if (users > 1) return
        guarded("car connect", Unit) {
            // createCar는 연결돼 있으면 반환 전에 콜백을 부른다(실측). 그래서 콜백 인자를 쓴다
            handle = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                _car.value = if (ready) connected else null
            }
        }
    }

    @Synchronized
    fun release() {
        if (users == 0) return
        users--
        if (users > 0) return
        guarded("car disconnect", Unit) { handle?.disconnect() }
        handle = null
        _car.value = null
    }
}
