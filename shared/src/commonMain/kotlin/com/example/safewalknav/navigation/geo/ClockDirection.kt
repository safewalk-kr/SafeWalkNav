package com.example.safewalknav.navigation.geo

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 방위각·시계 방향 계산 (KMM commonMain — Android/iOS 공통).
 *
 * 시각장애인 보행 교육(O&M)에서 쓰는 "몇 시 방향" 표현으로 바꾼다.
 * 12시 = 정면(사용자가 향한 방향), 3시 = 오른쪽, 6시 = 뒤, 9시 = 왼쪽.
 *
 * 시계 한 칸은 30° 다. 나침반 오차(도심 15~30°)가 대략 한 칸이라 그보다 잘게 나누지 않는다.
 */

/**
 * 출발점에서 도착점을 바라보는 초기 방위각 (진북 기준, 시계방향, 0~360°).
 * 보행 거리(수십 m)에서는 대권/등각 항로 차이가 무의미하다.
 */
fun initialBearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = lat1 * PI / 180.0
    val phi2 = lat2 * PI / 180.0
    val dLambda = (lon2 - lon1) * PI / 180.0
    val y = sin(dLambda) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
    return normalizeDeg(atan2(y, x) * 180.0 / PI)
}

/** 각도를 [0, 360) 으로 정규화. */
fun normalizeDeg(deg: Double): Double {
    val r = deg % 360.0
    return if (r < 0) r + 360.0 else r
}

/**
 * 사용자가 향한 방향(heading) 기준으로 대상이 몇 도 오른쪽에 있는가. [0, 360).
 * 0 = 정면, 90 = 오른쪽, 180 = 뒤, 270 = 왼쪽.
 */
fun relativeBearingDeg(targetBearingDeg: Double, headingDeg: Double): Double =
    normalizeDeg(targetBearingDeg - headingDeg)

/**
 * 상대 방위각 → 시계 방향 (1~12). 0° 근처(±15°)는 12시.
 * 경계(예: 15°)는 반올림 규칙에 따라 다음 칸(1시)으로 간다.
 */
fun clockHour(relativeDeg: Double): Int {
    val h = (normalizeDeg(relativeDeg) / 30.0).roundToInt() % 12
    return if (h == 0) 12 else h
}
