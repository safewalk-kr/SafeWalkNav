package com.example.safewalknav.nearby

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.example.safewalknav.navigation.geo.normalizeDeg
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 사용자가 향한 방향(진북 기준 방위각)을 추적한다. 시계 방향 안내의 "12시" 기준.
 *
 * ── 어느 방향을 "정면"으로 보나 ──
 * 휴대폰을 세로로 쥐고 화면이 나를 향한 자세라면, 기울기와 상관없이
 *   · 평평하게 들면  → 휴대폰 위쪽(+Y)이 앞을 가리키고
 *   · 세워 들면      → 후면 카메라(−Z)가 앞을 가리킨다.
 * 그 사이 어느 각도든 두 벡터의 **수평 성분은 같은 방향**이다. 그래서 (+Y) + (−Z) 를 수평면에
 * 투영한 방향을 정면으로 쓴다. 신호등 카메라를 들 때(가슴에 세워 듦)와 화면을 볼 때(눕혀 듦)
 * 모두 같은 "정면"이 나온다 — 자세를 바꿔도 시계 방향이 튀지 않는다.
 *
 * ── 신뢰도 ──
 * 자기장 센서 정확도가 UNRELIABLE 이거나, 회전 벡터가 보고한 방위 오차가 45° 를 넘으면
 * "믿을 수 없음"으로 본다(시계 한 칸 30° 보다 크게 틀릴 수 있다). 그때는 방향을 말하지 않고
 * 8자 보정을 안내한다. 사용자 요청대로 "엄청 나쁠 때"만 — LOW 정도는 그대로 쓴다.
 *
 * ── 자기 간섭(주차된 차·철골 구조물 옆) ──
 * 이런 곳에서는 나침반이 20~60° 틀리는데도 센서 정확도는 HIGH 로 나온다(보정 문제가 아니라 주변 자기장이
 * 휘었기 때문). 그래서 자기장 **세기** |B| 를 그 지역의 지구 자기장 세기([GeomagneticField])와 비교한다.
 * 30% 넘게 다른 상태가 1초 이어지면 믿을 수 없음, 정상 범위로 1초 돌아와야 풀린다(경계에서 깜박이지 않게).
 *
 * ── 자북 → 진북 ──
 * 회전 벡터는 자북 기준이다. 서울은 편각이 약 −9° 라 보정하지 않으면 시계로 1/3 칸 밀린다.
 * 위치를 알면 [setLocation] 으로 편각을 넣는다.
 */
class HeadingTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magneticSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val rotationMatrix = FloatArray(9)

    /** 지수 평활한 방위의 sin/cos (각도를 직접 평균하면 359°↔1° 경계에서 깨진다). */
    private var smoothSin = 0.0
    private var smoothCos = 0.0
    private var hasHeading = false

    /** 자기장 센서 정확도. 아직 보고 전이면 -1. */
    private var magneticAccuracy = -1

    /** 회전 벡터가 보고한 방위 오차(라디안). 지원 안 하면 -1. */
    private var headingAccuracyRad = -1f

    private var declinationDeg = 0f

    /** 이 자리에서 기대되는 지구 자기장 세기(µT). 위치를 알기 전에는 중위도 평균값. */
    private var expectedFieldUt = DEFAULT_FIELD_UT

    /** 자기장 세기가 기대값에서 벗어나기 시작한 시각 / 정상으로 돌아오기 시작한 시각 (0 = 해당 없음). */
    private var fieldOffSince = 0L
    private var fieldOkSince = 0L

    /** 자기장 세기로 본 간섭 상태 — 켜지고 꺼질 때 모두 [FIELD_HOLD_MS] 를 기다린다. */
    private var magneticDisturbed = false

    /** 이 기기에 방향을 낼 센서가 있는가. */
    val hasSensor: Boolean get() = rotationSensor != null

    /**
     * 주변 쇠붙이 때문에 자기장 세기가 이상한가. 원인이 보정 부족이 아니라 장소라서,
     * 안내 문구가 달라야 한다(8자 보정 대신 "몇 걸음 옮기세요").
     */
    val isMagneticallyDisturbed: Boolean get() = magneticDisturbed

    /** 보정이 필요한 상태인가(센서가 스스로 "믿을 수 없음"이라 하거나 방향 오차가 큼). 이때 조언은 8자 보정. */
    val needsCalibration: Boolean
        get() = magneticAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
            (headingAccuracyRad >= 0f && headingAccuracyRad > UNRELIABLE_HEADING_ACCURACY_RAD)

    /** 방향을 믿을 수 없는 상태인가 (8자 보정 필요, 또는 주변 자기 간섭). */
    val isUnreliable: Boolean
        get() = needsCalibration || magneticDisturbed

    fun start() {
        // GAME(약 50Hz): 몸을 초당 45° 쯤 돌릴 때 UI(약 15Hz)+강한 평활은 10° 넘게 뒤처져,
        // 방향 찾기 진동이 이미 지나간 방향에서 울렸다.
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        // 자기장 센서: 정확도 보고 + 세기(|B|)로 주변 간섭을 본다. 1초 판정에는 NORMAL(약 5Hz)이면 충분하다.
        magneticSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        // 멈춰 있던 동안 사용자는 몸을 돌렸을 수 있다(신호등 카메라에 다녀오는 등). 옛 방향을 남겨 두면
        // 돌아온 직후 1초 남짓은 평활값이 따라오는 중이라 박스를 누르면 최대 180° 틀린 방향을 말한다.
        // 새 값이 올 때까지 "모름"으로 둔다.
        hasHeading = false
        headingAccuracyRad = -1f
        // 간섭 판정의 시간 재기는 새로 시작한다. 판정 자체(magneticDisturbed)는 남긴다 —
        // 간섭 속에서 떠났다면 돌아와 1초 정상을 확인할 때까지는 믿지 않는 편이 안전하다.
        fieldOffSince = 0L
        fieldOkSince = 0L
    }

    /** 현재 위치로 편각(자북↔진북 차이)과 기대 자기장 세기를 갱신. */
    fun setLocation(lat: Double, lon: Double) {
        val field = GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, System.currentTimeMillis())
        declinationDeg = field.declination
        // fieldStrength 는 nT — µT 로. 터무니없는 값이면(모델 오류) 기본값을 유지한다.
        val ut = field.fieldStrength / 1000f
        if (ut.isFinite() && ut > 10f) expectedFieldUt = ut
    }

    /** 진북 기준 정면 방위각(0~360). 아직 값이 없으면 null. */
    fun currentTrueHeadingDeg(): Double? {
        if (!hasHeading) return null
        val magnetic = Math.toDegrees(atan2(smoothSin, smoothCos))
        return normalizeDeg(magnetic + declinationDeg)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> onMagnetic(event)
            Sensor.TYPE_ROTATION_VECTOR -> onRotation(event)
        }
    }

    /**
     * 자기장 세기로 주변 간섭을 판정한다(클래스 주석 "자기 간섭").
     * 한 번 튄 값으로 켜고 끄지 않는다 — 벗어난/돌아온 상태가 [FIELD_HOLD_MS] 이어져야 바꾼다.
     */
    private fun onMagnetic(event: SensorEvent) {
        magneticAccuracy = event.accuracy
        if (event.values.size < 3) return
        // ⚠️ 보정이 덜 된 자력계(정확도 LOW 이하)는 휴대폰 자체 자기 편향이 그대로 남아 세기가 늘 ±30% 를
        //    벗어난다. 그걸 "주변 간섭"으로 판정하면 "몇 걸음 옮기세요"만 반복되고, 정작 필요한 8자 보정
        //    안내는 안 나와 이 화면에서 방향이 영영 막혔다. 세기 판정은 보정된 값(MEDIUM 이상)에서만 한다.
        if (event.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
            fieldOffSince = 0L
            fieldOkSince = 0L
            magneticDisturbed = false
            return
        }
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        val now = SystemClock.elapsedRealtime()
        val off = abs(magnitude - expectedFieldUt) > expectedFieldUt * FIELD_DEVIATION_RATIO
        if (off) {
            fieldOkSince = 0L
            if (fieldOffSince == 0L) fieldOffSince = now
            if (!magneticDisturbed && now - fieldOffSince >= FIELD_HOLD_MS) magneticDisturbed = true
        } else {
            fieldOffSince = 0L
            if (fieldOkSince == 0L) fieldOkSince = now
            if (magneticDisturbed && now - fieldOkSince >= FIELD_HOLD_MS) magneticDisturbed = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) magneticAccuracy = accuracy
    }

    private fun onRotation(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        // R(행 우선): world = R · device,  world 축 = (동, 북, 위)
        // 정면 벡터 device (0, +1, −1) 의 수평 성분
        val east = (rotationMatrix[1] - rotationMatrix[2]).toDouble()
        val north = (rotationMatrix[4] - rotationMatrix[5]).toDouble()
        if (hypot(east, north) < MIN_HORIZONTAL) return   // 거꾸로 든 경우 등 — 정면을 정할 수 없음

        if (event.values.size > 4) headingAccuracyRad = event.values[4]

        val azimuthRad = atan2(east, north)
        val s = sin(azimuthRad)
        val c = cos(azimuthRad)
        if (!hasHeading) {
            smoothSin = s
            smoothCos = c
            hasHeading = true
        } else {
            smoothSin += SMOOTHING * (s - smoothSin)
            smoothCos += SMOOTHING * (c - smoothCos)
        }
    }

    companion object {
        /**
         * 지수 평활 계수. 작을수록 부드럽지만 몸을 돌린 뒤 따라오는 데 오래 걸린다.
         * 0.35: GAME 속도(약 50Hz)와 함께 몸을 초당 45° 돌려도 수 도 안쪽으로 따라온다(0.2 는 눈에 띄게 늦었다).
         */
        private const val SMOOTHING = 0.35

        /** 위치를 알기 전 기대 자기장 세기(µT) — 지구 자기장은 대략 25~65µT, 한국은 약 50µT. */
        private const val DEFAULT_FIELD_UT = 50f

        /** 기대 세기에서 이 비율보다 더 벗어나면 주변 자기 간섭으로 본다. */
        private const val FIELD_DEVIATION_RATIO = 0.3f

        /** 간섭 판정을 켜고 끄기 전에 그 상태가 이어져야 하는 시간. */
        private const val FIELD_HOLD_MS = 1_000L

        /** 45° — 시계 한 칸(30°)보다 확실히 크게 틀릴 수 있는 수준. */
        private const val UNRELIABLE_HEADING_ACCURACY_RAD = 0.785f

        private const val MIN_HORIZONTAL = 0.2
    }
}
