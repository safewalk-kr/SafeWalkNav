package com.example.safewalknav

import android.os.SystemClock
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.abs

/**
 * 조준 진동 — "소나". 대상이 화면 중앙에 가까울수록 진동이 **빨라진다**.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 왜 소리가 아니라 진동인가 (2026-09 현장 피드백)
 * ─────────────────────────────────────────────────────────────────────────────
 * 사용자가 "앱에서 소리가 너무 많이 난다"고 했다. 게다가 시각장애인의 보행에서
 * **청각은 이미 다른 일에 쓰이고 있다** — 차 소리, 사람 소리, 음향신호기. 조준 안내로
 * 그 채널을 더 채우면 안전을 깎아 먹는다. 촉각은 비어 있다.
 *
 * 그래서 "어느 쪽인가"(좌/우)만 아주 가끔 음성으로 말하고,
 * "얼마나 맞았나"(정렬 정도)는 전부 진동이 맡는다.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 왜 펄스 '간격'인가 — 세기가 아니라
 * ─────────────────────────────────────────────────────────────────────────────
 * 진동 세기는 손·장갑·주머니·기기별 모터 차이로 절대값을 못 믿는다. 반면 **박자**는
 * 그런 것에 흔들리지 않고, 사람이 "빨라진다/느려진다"를 즉시 알아챈다. 주차 센서와 같은 원리다.
 *
 *   |x − 0.5| < 0.10  →  150 ms 간격  (거의 맞음 — 다다다다)
 *   |x − 0.5| < 0.25  →  400 ms 간격  (근처)
 *   그 밖                →  800 ms 간격  (멀다)
 *   대상 없음            →  진동 없음   (조용 — 아무 정보도 없을 땐 침묵이 정답)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 판정 진동과의 충돌 — 취소만이 아니라 '덮어쓰기'도 막아야 한다
 * ─────────────────────────────────────────────────────────────────────────────
 * 기기에는 진동 모터가 하나뿐이라 두 종류의 위험이 있다.
 *   (1) [Vibrator.cancel] 은 **모든** 진동을 끊는다.
 *   (2) [Vibrator.vibrate] 는 진행 중인 진동을 **덮어쓴다**.
 * (2)가 특히 위험하다. 점멸 경고 진동은 650 ms 짜리 패턴인데 다음 추론은 333 ms 뒤에
 * 오므로, 그 프레임이 조준 진동을 새로 걸면 경고가 중간에 잘린다. 안전 신호가
 * 조준 보조에게 잘리는 건 있을 수 없는 일이다.
 *
 * 그래서 호출자는 안전 진동 직전에 [holdOff] 를 부른다. 그 시간 동안 [update] 는
 * 아무것도 걸지 않는다. [stop] 만으로는 (2)를 못 막는다.
 *
 * 또한 파형은 **티어가 바뀔 때만** 다시 건다. 프레임마다 vibrate() 를 부르면 파형이
 * 매번 처음부터 재시작해 뚝뚝 끊긴 느낌이 난다.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 진동 어휘는 셋이다 — 서로 확실히 구분돼야 한다
 * ─────────────────────────────────────────────────────────────────────────────
 *   1) **찾았다** (여기 [playAcquired]) — 강한 2연타. "멈춰요, 여기예요."
 *   2) **조준 중** (이 클래스의 소나)        — 약한 단발 반복. 간격이 정렬도.
 *   3) **판정** (MainActivity 의 vibrateColor/vibrateWarning) — 색 확정·점멸 경고.
 *      빨강은 길게 한 번, 초록은 짧게 세 번으로 **색 자체가 촉각으로 구분된다.**
 *
 * (1)이 왜 필요한가: [SignalDecisionEngine] 은 확정에 **경과 시간**을 요구한다(0.6~0.8초).
 * 그런데 2배 줌이면 가로 화각이 20°라, 사용자가 90°/s 로 훑을 때 신호등이 화면에 머무는
 * 시간은 0.23초뿐이다. 즉 **훑는 중에는 절대 확정되지 않는다** — 멈춰야 한다.
 *
 * 그런데 소나만으로는 "언제 멈춰야 하는지"를 알 수 없다. 계속 울리고 있으니까.
 * 그래서 대상이 처음 잡히는 순간에 확실히 다른 촉감을 한 번 준다. 그래야
 * "빠르게 훑다가 → 2연타 → 멈춤 → 0.6초 → 판정" 의 흐름이 성립한다.
 */
class AimingFeedback(
    private val vibrator: Vibrator,
    /**
     * 접근성 용도 오디오 속성. **반드시 붙여야 한다** —
     * 속성 없이 vibrate() 하면 USAGE_UNKNOWN 으로 취급돼 시스템 진동 설정에서 통째로 억제된다.
     * ⚠️ 절전 모드는 접근성 용도도 면제하지 않는다(켜진 접근성 서비스가 없으면 꺼질 수 있음) —
     * 그 사실은 MainActivity 가 말로 알린다.
     */
    private val attrs: AudioAttributes,
) {

    /** 정렬 정도 구간. [gapMs] 는 펄스 시작 사이의 주기. */
    private enum class Tier(val gapMs: Long) {
        NONE(0L),
        ALIGNED(150L),
        NEAR(400L),
        FAR(800L),
    }

    private val usable: Boolean = vibrator.hasVibrator()

    /** 진폭 제어가 없는 기기에서는 amplitude 배열을 쓰지 않는다 (무시되거나 이상하게 동작). */
    private val hasAmplitudeControl: Boolean = vibrator.hasAmplitudeControl()

    /**
     * 톡 한 번의 길이. 세기 조절이 안 되는 보급형 모터(ERM)는 20ms 로는 제대로 돌지도 못해
     * 장갑·케이스 너머로 거의 안 느껴진다 — 그런 기기는 길게 준다.
     */
    private val pulseMs: Long = if (hasAmplitudeControl) PULSE_MS else PULSE_MS_NO_AMPLITUDE

    private var currentTier: Tier = Tier.NONE

    /**
     * 한 번이라도 파형을 건 적이 있는가.
     * [currentTier] 와 따로 두는 이유: 반복 파형(repeat=0)은 [Vibrator.cancel] 전까지
     * 영원히 뛴다. tier 만 보고 조기 반환하면 "tier 는 NONE 인데 모터는 돌고 있는"
     * 상태를 영영 못 끄게 된다.
     */
    private var armed = false

    /** 이 기기가 반복 파형을 거부했다. 매 프레임 재시도하지 않기 위한 영구 플래그. */
    private var armFailed = false

    /** 이 시각까지는 아무것도 걸지 않는다 — 안전 진동 보호 구간. */
    private var holdOffUntilMs = 0L

    /** 마지막으로 반복 파형을 건 시각 — 다른 앱 진동에 끊겼을 때 다시 걸기 위한 기준. */
    private var armedAtMs = 0L

    /** 마지막으로 "찾았다"를 울린 시각. 검출이 깜빡일 때 연타되는 걸 막는 쿨다운용. */
    private var lastAcquiredAtMs = 0L

    /** 마지막으로 대상이 보인 시각. "충분히 오래 없었다가 다시 나타났나" 판단용. */
    private var lastTargetSeenAtMs = 0L

    /** 소나를 연속으로 걸기 시작한 시각. 0 이면 안 걸린 상태. */
    private var sonarSinceMs = 0L

    /**
     * 소나가 상한([SONAR_MAX_MS])에 걸려 잠긴 상태.
     * 대상이 한 번 사라져야 풀린다.
     */
    private var sonarExhausted = false

    /**
     * 이번 프레임의 조준 대상 위치로 진동을 갱신한다.
     *
     * @param targetCenterX 대상의 화면 가로 중심(0~1). 대상이 없으면 null 또는 음수.
     */
    fun update(targetCenterX: Float?) {
        // ⚠️ armFailed 를 여기서 보면 안 된다. 그 플래그는 **반복** 파형(repeat=0)을 거부하는
        //    기기에서만 서고, 단발(repeat=-1)인 "찾았다"는 그 기기에서도 잘 울린다.
        //    위에서 막으면 소나와 획득 진동이 **함께** 사라져 촉각 안내가 통째로 없어진다.
        if (!usable) return
        val now = SystemClock.elapsedRealtime()
        if (now < holdOffUntilMs) return   // 안전 진동 / 획득 패턴 보호 중
        val tier = tierFor(targetCenterX)
        val targetPresent = tier != Tier.NONE

        // 아무것도 없다가 대상이 잡힌 순간 = "찾았다". 소나보다 먼저, 확실히 다른 촉감으로.
        //
        // 조건이 셋인 이유: 이 진동은 "멈추세요"라는 **지시**다. 검출이 경계에서 깜빡일 때
        // 2초마다 계속 울리면, 잡히지도 않은 상태에서 계속 멈추라고 하는 꼴이 된다.
        // 그래서 (1) 지금 막 잡혔고 (2) 그 전에 충분히 오래 없었고 (3) 쿨다운도 지났을 때만.
        if (targetPresent &&
            currentTier == Tier.NONE &&
            now - lastTargetSeenAtMs >= ACQUIRE_REARM_GAP_MS &&
            now - lastAcquiredAtMs >= ACQUIRE_COOLDOWN_MS
        ) {
            lastAcquiredAtMs = now
            lastTargetSeenAtMs = now
            playAcquired()
            // 패턴이 끝날 때까지 소나를 막는다. currentTier 는 NONE 으로 두므로
            // 홀드가 끝나면 다음 update 가 소나를 새로 건다.
            holdOffUntilMs = now + ACQUIRE_HOLD_MS
            return
        }
        if (targetPresent) lastTargetSeenAtMs = now

        // 대상이 사라지면 상한 잠금을 푼다 — 다음에 다시 찾으면 소나가 새로 시작한다.
        if (!targetPresent) {
            sonarSinceMs = 0L
            sonarExhausted = false
        }

        // ⚠️ 소나에 시간 상한을 둔다.
        //
        // 조준 임계(0.10)와 판정 임계(0.25~0.55) 사이 간격이 커서, "대상은 보이는데
        // 엔진은 확정하지 않는" 구간이 **무한히** 이어질 수 있다. 원거리 신호등이 딱 그
        // 구간이고, 간판·후미등 같은 오탐도 거기 산다. 그동안 손은 계속 울린다.
        //
        // 20~30초면 촉각이 순응해서 펄스 간격 차이를 못 느끼게 된다. 그러면 소나의
        // 정보(정렬 정도)가 사라질 뿐 아니라, 같은 모터를 쓰는 "찾았다"와 판정 진동까지
        // 이 배경 위에 묻힌다. 게다가 한 손은 흰지팡이를 쥐고 있어 쉴 손이 없다.
        //
        // 상한에 걸리면 조용해지고, 그 뒤는 MainActivity 의 생존 워치독이 말로 설명한다.
        if (targetPresent && !sonarExhausted) {
            if (sonarSinceMs == 0L) sonarSinceMs = now
            if (now - sonarSinceMs >= SONAR_MAX_MS) {
                sonarExhausted = true
                currentTier = Tier.NONE
                cancel()
                return
            }
        }
        if (sonarExhausted) return

        if (tier == currentTier) {
            // 같은 구간이면 파형 유지 — 재설정하면 끊긴다.
            // 다만 다른 앱의 진동(카톡 알림·벨)이 우리 반복 파형을 끊으면 다시 걸리지 않는다(안드로이드 14 이상은
            // 알림 진동이 우선이고, 끝나도 우리 것이 돌아오지 않는다). 잘 겨눈 채로 진동이 사라지면 사용자는
            // 놓쳤다고 오해한다 — 몇 초마다 같은 파형을 다시 건다. 박자 한 번이 살짝 어긋날 뿐이다.
            if (tier != Tier.NONE && !armFailed && now - armedAtMs >= REARM_INTERVAL_MS) arm(tier.gapMs)
            return
        }
        currentTier = tier
        if (tier == Tier.NONE) cancel() else if (!armFailed) arm(tier.gapMs)
    }

    /**
     * 조준 진동 중단. 화면을 떠나거나 조준 단계가 끝났을 때.
     *
     * [armed] 가 true 면 tier 와 무관하게 실제로 취소한다 — 그러지 않으면 백그라운드
     * 진입 직후 도착한 늦은 콜백이 건 파형을 아무도 못 끄는 경로가 생긴다.
     */
    fun stop() {
        if (!usable) return
        currentTier = Tier.NONE
        sonarSinceMs = 0L
        sonarExhausted = false
        if (!armed) return   // 판정 진동을 취소하지 않기 위한 조기 반환
        cancel()
    }

    /**
     * [durationMs] 동안 조준 진동을 걸지 않는다. **안전 진동을 울리기 직전에** 호출한다.
     * 이미 뛰고 있던 조준 파형은 즉시 끈다.
     *
     * ⚠️ [stop] 과 달리 **소나 예산([sonarSinceMs]·[sonarExhausted])은 건드리지 않는다.**
     * holdOff 는 "잠깐 비켜라"이지 "처음부터 다시"가 아니다. 예산을 함께 비우면,
     * 상한에 걸려 조용해진 소나를 안전 진동 한 번이 되살려서 25초 켜짐 / 20초 꺼짐이
     * 무한 반복된다 — 상한을 둔 이유(촉각 순응)가 통째로 무효가 된다.
     */
    fun holdOff(durationMs: Long) {
        if (!usable) return
        currentTier = Tier.NONE
        if (armed) cancel()
        holdOffUntilMs = SystemClock.elapsedRealtime() + durationMs
    }

    private fun tierFor(targetCenterX: Float?): Tier {
        if (targetCenterX == null || targetCenterX < 0f) return Tier.NONE
        val offset = abs(targetCenterX - 0.5f)
        return when {
            offset < ALIGNED_OFFSET -> Tier.ALIGNED
            offset < NEAR_OFFSET -> Tier.NEAR
            else -> Tier.FAR
        }
    }

    /**
     * "찾았다" — 강한 2연타. **한 번만** 울린다(repeat = -1).
     *
     * 소나(20ms · 진폭 160 · 단발)와 촉감이 확실히 달라야 하므로
     * 더 길고(45ms) 더 세게(최대 진폭) 두 번 친다. 손등이 아니라 손바닥으로 느껴지는 세기다.
     * 무한 반복이 아니므로 [armed] 는 건드리지 않는다 — 취소할 것이 남지 않는다.
     */
    private fun playAcquired() {
        // 세기 조절이 안 되는 기기는 "더 세게"로 구별할 수 없고 소나도 40ms 로 길어져서,
        // 45ms 2연타가 정렬 소나와 거의 같게 느껴진다 — 그런 기기는 길이로 확실히 다르게.
        val pulse = if (hasAmplitudeControl) ACQUIRE_PULSE_MS else ACQUIRE_PULSE_MS_NO_AMPLITUDE
        val timings = longArrayOf(0, pulse, ACQUIRE_GAP_MS, pulse)
        val effect = if (hasAmplitudeControl) {
            VibrationEffect.createWaveform(timings, intArrayOf(0, 255, 0, 255), -1)
        } else {
            VibrationEffect.createWaveform(timings, -1)
        }
        try {
            vibrator.vibrate(effect, attrs)
        } catch (_: Exception) {
            // 보조 채널이라 실패해도 조용히 넘어간다. 소나는 계속 동작한다.
        }
    }

    /** repeat = 0 → timings 를 인덱스 0 부터 무한 반복. [cancel] 전까지 계속 뛴다. */
    private fun arm(gapMs: Long) {
        val off = (gapMs - pulseMs).coerceAtLeast(1L)
        val timings = longArrayOf(0L, pulseMs, off)
        val effect = if (hasAmplitudeControl) {
            VibrationEffect.createWaveform(timings, intArrayOf(0, PULSE_AMPLITUDE, 0), 0)
        } else {
            VibrationEffect.createWaveform(timings, 0)
        }
        // ⚠️ vibrate() **전에** 세운다. 모터가 이미 돌기 시작한 뒤에 예외가 나는 경우,
        // armed 가 false 로 남으면 stop() 이 조기 반환해 그 무한 파형을 아무도 못 끈다.
        // 거짓 양성(실제로는 안 걸렸는데 armed=true)의 대가는 불필요한 cancel() 한 번뿐이다.
        armed = true
        armedAtMs = SystemClock.elapsedRealtime()
        try {
            vibrator.vibrate(effect, attrs)
        } catch (_: Exception) {
            // 일부 기기가 반복 파형을 거부한다. 조준 진동은 보조 채널이므로 조용히 포기하되,
            // 매 프레임(333ms) 재시도하며 예외를 쏟지 않도록 영구히 꺼 둔다.
            armFailed = true
            currentTier = Tier.NONE
        }
    }

    private fun cancel() {
        // ⚠️ armed 는 cancel() 이 **정상 반환한 뒤에만** 내린다.
        // 먼저 내려 버리면, cancel() 이 실패했을 때 이후의 stop() 이 전부 조기 반환해
        // 무한 파형을 끌 주체가 사라진다 ([arm] 과 정확히 대칭인 함정).
        try {
            vibrator.cancel()
            armed = false
        } catch (_: Exception) {
            // armed 는 true 로 남긴다 — 다음 stop() 이 다시 시도한다.
        }
    }

    companion object {
        /** 한 번의 톡 — 짧게. 길면 연속 진동처럼 뭉개져 박자가 안 느껴진다. */
        private const val PULSE_MS = 20L
        private const val PULSE_MS_NO_AMPLITUDE = 40L

        /** 0~255. 걸으면서도 느껴지되 손이 피로하지 않은 선. */
        private const val PULSE_AMPLITUDE = 160

        private const val ALIGNED_OFFSET = 0.10f
        private const val NEAR_OFFSET = 0.25f

        // ── "찾았다" 패턴 ──
        /** 소나의 20ms 보다 확실히 길게 — 손이 "다른 신호"로 인식할 만큼. */
        private const val ACQUIRE_PULSE_MS = 45L
        private const val ACQUIRE_PULSE_MS_NO_AMPLITUDE = 90L
        private const val ACQUIRE_GAP_MS = 70L

        /**
         * 패턴(45+70+45 = 160ms)이 끝나고 한 박자 쉰 뒤 소나가 이어지도록.
         * 추론 간격(333ms)보다 **짧게** 둔다 — 넘으면 추론 한 사이클을 통째로 날린다.
         */
        private const val ACQUIRE_HOLD_MS = 320L

        /**
         * "찾았다"를 다시 울리려면 대상이 이만큼 **안 보였어야** 한다.
         * 쿨다운만으로는 부족하다 — 쿨다운은 빈도만 제한할 뿐,
         * "정말 놓쳤다가 다시 찾았나"를 묻지 않는다.
         */
        private const val ACQUIRE_REARM_GAP_MS = 800L

        /**
         * 검출이 깜빡일 때 2연타가 연달아 터지는 걸 막는다.
         * 너무 길면 "다음 신호등을 새로 찾았다"를 놓치므로 2초로 잡았다.
         */
        private const val ACQUIRE_COOLDOWN_MS = 2_000L

        /**
         * 소나를 연속으로 걸 수 있는 최대 시간.
         * 촉각 순응이 시작되는 20~30초 구간보다 앞에서 끊는다.
         * 대상이 한 번 사라져야 다시 걸린다.
         */
        private const val SONAR_MAX_MS = 25_000L

        /** 같은 구간의 반복 파형을 다시 거는 주기 — 외부 진동에 끊겨도 이 안에 돌아온다. */
        private const val REARM_INTERVAL_MS = 3_000L
    }
}
