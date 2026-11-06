package com.example.safewalknav.nearby

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.abs

/**
 * 방향 찾기 진동 — 고른 장소 쪽을 **향하고 있는 동안** 진동으로 알려 준다.
 *
 * 왜: 시계 방향은 30° 단위라 "2시 방향"을 들어도 몸을 얼마나 돌려야 할지 애매하다.
 * 몸을 천천히 돌리다가 진동이 오면 그쪽이다 — 화면도 소리도 필요 없다.
 * (경로 안내가 아니다. "어느 쪽인가" 만 알려 준다.)
 *
 * 진동 어휘 (이 화면 전용, 신호등 카메라의 진동과 겹치지 않게 짧고 가볍게)
 *  - 정면에 들어온 순간: 60ms·쉼 70ms·60ms 두 번 ("찾았다")
 *    — 한 번짜리 짧은 진동은 TalkBack 의 포커스 이동 진동과 구별이 안 됐다. 두 번 톡톡은 겹치지 않는다.
 *  - 정면에 머무는 동안: 45ms 톡 — 0.5초마다
 *    — 25ms 는 장갑·두꺼운 케이스 너머나 값싼 진동 모터(ERM, 회전 시작이 느림)에서 거의 안 느껴졌다.
 *  - 벗어나면: 조용
 *
 * 들어올 때 ±15°, 나갈 때 ±22° — 경계에서 손이 조금 떨려도 켜졌다 꺼졌다 하지 않게(히스테리시스).
 * 나침반이 불량이면 진동하지 않는다(틀린 방향으로 이끄느니 침묵).
 * 30초 뒤 저절로 끝난다 — 주머니에 넣은 폰이 계속 떨면 안 된다.
 */
class DirectionBeacon(context: Context, private val heading: HeadingTracker) {

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    private val handler = Handler(Looper.getMainLooper())
    private var targetBearingDeg: Double? = null
    private var startedAt = 0L
    private var inside = false
    private var lastTickAt = 0L

    /** 나침반이 불량해진 시각 (0 = 정상). 잠깐 튀는 것과 계속 불량인 것을 구분한다. */
    private var unreliableSince = 0L

    /** 시간이 다 돼 스스로 멈췄을 때. */
    var onTimeout: (() -> Unit)? = null

    /**
     * 도중에 나침반을 믿을 수 없게 돼 스스로 멈췄을 때.
     * 예전에는 진동만 조용히 멈춰서, 사용자는 30초 내내 "아직 그쪽이 아닌가" 하며 몸을 돌렸다.
     */
    var onCompassLost: (() -> Unit)? = null

    val isRunning: Boolean get() = targetBearingDeg != null

    /** 진동 모터가 있는가. 없으면 "진동이 울립니다" 라고 약속하지 않는다. */
    val canVibrate: Boolean get() = vibrator.hasVibrator()

    /** @param bearingDeg 내 위치에서 대상까지의 진북 기준 방위각 */
    fun start(bearingDeg: Double) {
        handler.removeCallbacks(tick)
        targetBearingDeg = bearingDeg
        startedAt = SystemClock.elapsedRealtime()
        inside = false
        lastTickAt = 0L
        unreliableSince = 0L
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        targetBearingDeg = null
        inside = false
        vibrator.cancel()
    }

    private val tick = object : Runnable {
        override fun run() {
            val target = targetBearingDeg ?: return
            val now = SystemClock.elapsedRealtime()
            if (now - startedAt >= MAX_DURATION_MS) {
                stop()
                onTimeout?.invoke()
                return
            }
            val h = heading.currentTrueHeadingDeg()
            if (h == null || heading.isUnreliable) {
                inside = false
                if (unreliableSince == 0L) unreliableSince = now
                if (now - unreliableSince >= COMPASS_LOST_MS) {
                    stop()
                    onCompassLost?.invoke()
                    return
                }
            } else {
                unreliableSince = 0L
                val off = abs(signedDiff(target, h))
                val limit = if (inside) EXIT_DEG else ENTER_DEG
                val nowInside = off <= limit
                if (nowInside && !inside) {
                    vibrate(FOUND_EFFECT)
                    // 다음 톡은 두 번 진동이 끝난 뒤 0.5초부터 — 바로 이어지면 세 번 진동처럼 느껴진다.
                    lastTickAt = now + FOUND_TOTAL_MS
                } else if (nowInside && now - lastTickAt >= TICK_INTERVAL_MS) {
                    vibrate(TICK_EFFECT)
                    lastTickAt = now
                }
                inside = nowInside
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    /** a - b 를 -180~180 으로. */
    private fun signedDiff(a: Double, b: Double): Double = ((a - b + 540.0) % 360.0) - 180.0

    /**
     * 접근성 용도로 진동한다 — 아니면 절전 모드·시스템 진동 설정에서 억제된다
     * (신호등 카메라와 같은 이유).
     */
    private fun vibrate(effect: VibrationEffect) {
        if (!vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, A11Y_AUDIO_ATTRIBUTES)
            }
        } catch (_: Exception) {
            // 진동 실패는 치명적이지 않다 — 음성 안내는 이미 나갔다
        }
    }

    companion object {
        private const val ENTER_DEG = 15.0
        private const val EXIT_DEG = 22.0
        private const val POLL_MS = 100L
        private const val TICK_INTERVAL_MS = 500L

        /** "찾았다" = 두 번 톡톡 (60ms, 쉼 70ms, 60ms). 클래스 주석의 진동 어휘 참고. */
        private const val FOUND_PULSE_MS = 60L
        private const val FOUND_GAP_MS = 70L
        private const val FOUND_TOTAL_MS = FOUND_PULSE_MS + FOUND_GAP_MS + FOUND_PULSE_MS
        private const val TICK_MS = 45L
        const val MAX_DURATION_MS = 30_000L

        private val FOUND_EFFECT: VibrationEffect = VibrationEffect.createWaveform(
            longArrayOf(0L, FOUND_PULSE_MS, FOUND_GAP_MS, FOUND_PULSE_MS), -1
        )
        private val TICK_EFFECT: VibrationEffect =
            VibrationEffect.createOneShot(TICK_MS, VibrationEffect.DEFAULT_AMPLITUDE)

        /** 나침반 불량이 이만큼 이어지면 멈추고 알린다 (잠깐 튀는 건 견딘다). */
        private const val COMPASS_LOST_MS = 2_000L

        private val A11Y_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
