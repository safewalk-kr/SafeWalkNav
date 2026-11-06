package com.example.safewalknav.navigation.signal

/**
 * 신호등 안내 결정 엔진 — **플랫폼 무관 순수 로직**.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 왜 shared 에 있나
 * ─────────────────────────────────────────────────────────────────────────────
 * 이 결정 로직(신뢰도 비대칭 필터 · 3프레임 안정성 · 점멸 감지 · 색 확정 상태기계)은
 * "지금 안내해도 안전한가"를 판단하는 **안전 핵심**이다.
 *
 * 예전에는 이 로직이 Android(MainActivity.kt)와 iOS(TrafficLightDetector.swift)에
 * **따로** 구현돼 있었다. 그 결과 한쪽만 고치면 두 플랫폼의 안전 동작이 갈라졌다.
 * 실제로 2026-07 시점에 Android 는 빨강0.25/초록0.45 비대칭 + 안정성 + 점멸 감지가
 * 있었지만, iOS 는 `confidence >= 0.5` 한 줄뿐이었다. **같은 앱이 두 OS 에서 다르게
 * 위험했다.**
 *
 * 이 엔진을 commonMain 에 두고 양 플랫폼이 호출하면, 안전 규칙이 **구조적으로 갈라질 수
 * 없다.** 플랫폼별로 남는 것은 "카메라 텐서를 뽑는 부분(TFLite/CoreML)"과
 * "결정에 따른 부수효과(TTS·진동·로그)"뿐이다.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 순수성 규칙
 * ─────────────────────────────────────────────────────────────────────────────
 * - 이 클래스는 **부수효과를 일으키지 않는다.** TTS·진동·네트워크·로그 전부 호출자 몫.
 * - 현재 시각은 [decide] 의 인자로 받는다 (테스트 결정성 + 순수성).
 * - 안내 문구도 여기서 만들지 않는다 — [SignalDecision] 은 "무엇을 할지"(enum)만 담고,
 *   실제 문자열(한국어/영어)은 플랫폼이 로컬라이즈한다. (미국 영어 대응 대비)
 *
 * 사용법 (호출자):
 *   val engine = SignalDecisionEngine()
 *   // 매 프레임:
 *   when (val d = engine.decide(detections, nowMs)) {
 *       is SignalDecision.Announce -> { speak(localize(d)); if (d.vibrate) vibrate() }
 *       is SignalDecision.Repeat   -> speak(localizeRepeat(d.color))
 *       is SignalDecision.Flicker  -> { speak(flickerMsg); vibrateWarning() }
 *       is SignalDecision.Silent   -> log(d.reason)   // 침묵도 정보다
 *   }
 *   // zone 이탈/네비 종료 시:
 *   engine.reset()
 */
class SignalDecisionEngine(
    private val config: SignalDecisionConfig = SignalDecisionConfig(),
) {
    /**
     * 무인자 편의 생성자.
     *
     * KMP 는 Kotlin 기본 인자를 Obj-C/Swift 로 노출하지 않는다. 그래서 iOS 에서는
     * `SignalDecisionEngine()` 나 `SignalDecisionConfig()` 를 무인자로 만들 수 없다.
     * 이 보조 생성자가 iOS 에 무인자 경로를 열어 준다(기본 config 사용).
     * (Android 는 원래대로 `SignalDecisionEngine()` 그대로 동작)
     */
    constructor() : this(SignalDecisionConfig())

    // ── 색 상수 (앱 전체 계약) ──
    //    0 = 빨강, 1 = 초록. TrafficLightDetector 가 이 계약을 보장한다.
    private val red get() = COLOR_RED
    private val green get() = COLOR_GREEN

    // ── 상태 (순수 데이터. 부수효과 없음) ──
    private var currentColorCandidate: Int = COLOR_NONE   // 안정성 필터에서 추적 중인 색
    private var colorStreak: Int = 0                       // 연속 검출 수
    private var candidateSinceMs: Long = 0L                // 현재 후보 스트릭이 시작된 시각
    private var lastConfirmedColor: Int = COLOR_NONE       // 확정된 마지막 색
    private var lastHeartbeatMs: Long = 0L                 // 마지막 반복 안내 시각
    private var lastValidatedMs: Long = 0L                 // 마지막 유효 검출 시각 (timeout 판정)
    private var lastTransitionMs: Long = 0L                // 마지막 색 전환 시각 (점멸 판정)
    private var flickerLockoutUntilMs: Long = 0L           // 점멸 락아웃 종료 시각
    private var lastRedSeenMs: Long = 0L                   // 빨강이 선택된 마지막 유효 프레임 시각 (R→G 목격 판정)
    private var lastTransitionWasFirstRed: Boolean = false // 마지막 전환이 "첫 빨강 확정"이었나 (점멸 예외 판정)
    private var restartedAfterFlicker: Boolean = false     // 점멸 락아웃이 막 끝나 다시 시작하는 중인가

    /** 마지막 확정 색 (읽기용). 호출자의 문맥 판단·디버그에 쓴다. -1/0/1. */
    val confirmedColor: Int get() = lastConfirmedColor

    /**
     * zone 이탈 / 네비게이션 종료 / 카메라 정지 시 호출.
     * 다음 인식을 보수적으로 새로 시작하도록 상태를 완전히 비운다.
     */
    fun reset() {
        currentColorCandidate = COLOR_NONE
        colorStreak = 0
        candidateSinceMs = 0L
        lastConfirmedColor = COLOR_NONE
        lastHeartbeatMs = 0L
        lastValidatedMs = 0L
        lastTransitionMs = 0L
        flickerLockoutUntilMs = 0L
        lastRedSeenMs = 0L
        lastTransitionWasFirstRed = false
        restartedAfterFlicker = false
    }

    /**
     * 한 프레임의 검출 리스트를 받아 안내 결정을 돌려준다.
     *
     * @param detections 이번 프레임 검출들 (classId 0=빨강/1=초록, confidence, box 크기 정규화)
     * @param nowMs 현재 Unix epoch millis (호출자가 주입 — 순수성/테스트용)
     */
    fun decide(detections: List<RawSignalDetection>, nowMs: Long): SignalDecision {
        // ── 1) 물리적 하한(크기) + 색상별 신뢰도 ──
        //    크기는 두 색 공통의 낮은 바닥(서브픽셀 노이즈 제거)만.
        //    안전 마진은 신뢰도에 건다 — 초록 오탐(차도로 나감)이 빨강 오탐(더 기다림)보다
        //    비교할 수 없이 위험하므로 초록에 훨씬 높은 확신을 요구한다.
        val bigEnough = detections.filter {
            it.boxWidth >= config.minBoxDimension && it.boxHeight >= config.minBoxDimension
        }
        if (bigEnough.isEmpty()) {
            return SignalDecision.Silent(
                if (detections.isEmpty()) SilentReason.NO_DETECTION else SilentReason.ALL_TOO_SMALL
            )
        }
        val validated = bigEnough.filter { it.confidence >= requiredConfidence(it.classId) }
        if (validated.isEmpty()) {
            return SignalDecision.Silent(SilentReason.ALL_LOW_CONFIDENCE)
        }

        // ── 2) 판단 대상 하나 선택 ──
        val nearest = selectSignal(validated, lastConfirmedColor)
            ?: return SignalDecision.Silent(SilentReason.NO_DETECTION)
        val detectedColor = nearest.classId

        // ── 3) 점멸 락아웃 진행 중이면 무조건 침묵 ──
        if (flickerLockoutUntilMs > 0L && nowMs < flickerLockoutUntilMs) {
            return SignalDecision.Silent(SilentReason.FLICKER_LOCKOUT)
        }
        if (flickerLockoutUntilMs > 0L && nowMs >= flickerLockoutUntilMs) {
            // 락아웃 종료 — 상태를 비워 다음 프레임부터 보수적으로 재인식.
            // lastConfirmedColor 도 -1 로 둬서 락아웃 직후 초록이 "전환"으로 오인돼
            // "건너세요"가 나가는 일을 막는다.
            currentColorCandidate = COLOR_NONE
            colorStreak = 0
            lastConfirmedColor = COLOR_NONE
            flickerLockoutUntilMs = 0L
            // 락아웃 직후의 첫 빨강은 "처음 보는 긴 빨강의 끝자락"이 아니다 — 방금까지 깜빡이던 신호다.
            // 그 뒤 몇 초 만의 초록을 점멸 예외로 통과시키면 "건너세요"가 나간다.
            restartedAfterFlicker = true
        }

        // ── 4) 검출 타임아웃 → 상태 리셋 ──
        //    유효 검출이 오래 끊겼다 다시 오면 이전 확정색을 신뢰하지 않는다
        //    (그 사이 신호가 바뀌었을 수 있음).
        val observationGapMs = if (lastValidatedMs > 0L) nowMs - lastValidatedMs else 0L
        if (lastValidatedMs > 0L && nowMs - lastValidatedMs > config.detectionTimeoutMs) {
            currentColorCandidate = COLOR_NONE
            colorStreak = 0
            lastConfirmedColor = COLOR_NONE
        }
        lastValidatedMs = nowMs

        // ── 4-b) 관찰이 끊겼으면 스트릭을 새로 시작한다 ──
        //
        // ⚠️ 이게 없으면 시간 바닥이 뚫린다. 검출이 없거나 확신이 모자란 프레임은
        //    위에서 조기 반환하므로 colorStreak 을 건드리지 않는다. 그래서
        //    "초록 1프레임 → 10초 공백 → 초록 4프레임(160ms)" 같은 시퀀스가
        //    스트릭 5 + 경과 10초로 통과해 버린다. 확정 프레임 중 4장이 160ms 안에
        //    몰려 있는데도 말이다 — 시간 바닥으로 막으려던 바로 그 상황이다.
        //
        //    시간 바닥이 보장해야 하는 건 "스트릭이 오래 전에 시작됐다"가 아니라
        //    "그동안 **계속** 같은 색을 보고 있었다"이다. 그래서 관찰이 끊기면 리셋한다.
        if (observationGapMs > config.maxObservationGapMs) {
            currentColorCandidate = COLOR_NONE
            colorStreak = 0
        }

        // ── 5) 안정성 필터 — 빨강은 빠르게, 초록은 보수적으로 확정 ──
        //
        // 조건이 **두 개**다: 연속 프레임 수 AND 경과 시간. 둘 다 채워야 확정된다.
        // 왜 시간까지 보는지는 [SignalDecisionConfig.redStabilityMs] 주석 참조.
        if (detectedColor == currentColorCandidate) {
            colorStreak++
        } else {
            currentColorCandidate = detectedColor
            colorStreak = 1
            candidateSinceMs = nowMs
        }
        if (detectedColor == red) lastRedSeenMs = nowMs

        // ── 5-a) R→G 를 "목격"했는가 ──
        //
        // ⚠️ 2026-10 추가. "빨강 확정" 상태는 detectionTimeoutMs(20초)까지 남는다. 그래서 예전에는
        //    "빨간불입니다" 를 듣고 폰을 내렸다가 15초 뒤 다시 들면, 신호는 이미 12초째 초록인데도
        //    0.8초 뒤 "방금 초록불로 바뀌었습니다. 건너세요" 가 나갔다 — 남은 시간이 몇 초뿐일 수 있다.
        //    폰을 돌려 옆 횡단보도의 초록을 본 경우도 같다.
        //
        //    "방금 바뀌었다"고 말하려면 빨강을 **직전까지** 보고 있었어야 한다. 이 초록 스트릭이 시작되기
        //    직전 [SignalDecisionConfig.transitionWitnessWindowMs] 안에 빨강을 봤을 때만 전환으로 친다.
        //    아니면 정적 초록("멈춰서 다음 신호를 기다리세요")으로 강등한다.
        val redWitnessed = lastConfirmedColor == red &&
            lastRedSeenMs > 0L &&
            candidateSinceMs - lastRedSeenMs <= config.transitionWitnessWindowMs

        val requiredFrames = when {
            detectedColor == red -> config.redStabilityFrames
            redWitnessed -> config.greenTransitionStabilityFrames
            else -> config.greenStabilityFrames
        }
        val requiredMs = when {
            detectedColor == red -> config.redStabilityMs
            redWitnessed -> config.greenTransitionStabilityMs
            else -> config.greenStabilityMs
        }
        val elapsedMs = nowMs - candidateSinceMs
        if (colorStreak < requiredFrames || elapsedMs < requiredMs) {
            // 프레임과 시간을 모두 실어 보낸다 — 현장에서 "4/4 프레임인데 왜 조용하지?" 를
            // 로그만 보고 5초 안에 진단할 수 있어야 한다.
            return SignalDecision.Silent(
                reason = SilentReason.STABILITY_PENDING,
                streak = colorStreak,
                requiredFrames = requiredFrames,
                color = detectedColor,
                elapsedMs = elapsedMs,
                requiredMs = requiredMs,
            )
        }

        // ── 6) 안정성 통과 = 확정 ──
        val confirmed = currentColorCandidate
        val previous = lastConfirmedColor

        // (6-1) 같은 색 지속 → heartbeat 간격마다 반복 안내, 아니면 침묵
        if (confirmed == previous) {
            return if (nowMs - lastHeartbeatMs >= config.heartbeatIntervalMs) {
                lastHeartbeatMs = nowMs
                SignalDecision.Repeat(confirmed)
            } else {
                SignalDecision.Silent(SilentReason.SAME_COLOR_QUIET, color = confirmed)
            }
        }

        // (6-2) 색 변경 (또는 첫 확정)
        // 빨강 확정 상태였어도 전환을 목격하지 못했으면(5-a) "빨강을 놓쳤다" = 첫 확정과 같게 다룬다.
        // → 정적 초록, 진동 없음, 점멸 판정 대상도 아님(본 적 없는 전환으로 점멸 경고를 내지 않는다).
        val effectivePrevious =
            if (previous == red && confirmed == green && !redWitnessed) COLOR_NONE else previous
        val isRedToGreen = effectivePrevious == red && confirmed == green
        // 점멸 감지: 정상 신호 phase 는 최소 시간 지속한다. 그보다 짧은 간격의 전환은 점멸로 본다.
        // 첫 확정은 제외 (항상 정상 안내).
        // R→G 는 직전 전환이 "첫 빨강 확정"일 때만 제외한다 — 긴 빨강의 끝자락을 잡은 것일 수 있어서.
        // ⚠️ 2026-10: 예전에는 R→G 를 무조건 제외해, 초록→빨강 직후 1~3초 만에 초록(차량 신호·간판 오탐)이
        //    잡혀도 "건너세요"가 나갔다. 보행 빨강이 몇 초 만에 끝나는 일은 없다 → 점멸(오탐)로 본다.
        val exemptFromFlicker = isRedToGreen && lastTransitionWasFirstRed
        if (!exemptFromFlicker &&
            effectivePrevious != COLOR_NONE &&
            lastTransitionMs > 0L &&
            nowMs - lastTransitionMs < config.minPhaseDurationMs
        ) {
            val gapMs = nowMs - lastTransitionMs
            // 락아웃 걸고 상태 비움 — 락아웃 종료 후 보수적 재시작.
            flickerLockoutUntilMs = nowMs + config.flickerLockoutMs
            lastConfirmedColor = COLOR_NONE
            currentColorCandidate = COLOR_NONE
            colorStreak = 0
            lastTransitionMs = nowMs
            lastHeartbeatMs = nowMs
            lastTransitionWasFirstRed = false
            return SignalDecision.Flicker(gapMs, previous, confirmed)
        }

        // (6-3) 정상 전환 (또는 첫 확정) → 안내
        lastConfirmedColor = confirmed
        lastHeartbeatMs = nowMs
        lastTransitionMs = nowMs

        val transition = when {
            confirmed == red && effectivePrevious == green -> SignalTransition.GREEN_TO_RED
            confirmed == red -> SignalTransition.RED_NEW
            isRedToGreen -> SignalTransition.RED_TO_GREEN
            else -> SignalTransition.STATIC_GREEN
        }
        lastTransitionWasFirstRed = transition == SignalTransition.RED_NEW && !restartedAfterFlicker
        restartedAfterFlicker = false
        // 진동: 초록→빨강 전환, R→G 전환에서만. 정적 초록/신규 빨강은 진동 없음.
        val vibrate = transition == SignalTransition.GREEN_TO_RED ||
            transition == SignalTransition.RED_TO_GREEN
        // 발화 인터럽트: 안전 관련 전환은 즉시 끼어들어 말한다.
        val interrupt = transition != SignalTransition.STATIC_GREEN

        return SignalDecision.Announce(
            color = confirmed,
            transition = transition,
            vibrate = vibrate,
            interrupt = interrupt,
            confidence = nearest.confidence,
        )
    }

    /**
     * 이 검출이 요구하는 최소 신뢰도. 초록은 오탐 비용이 비대칭적으로 크다.
     *
     * 순서 주의: 빨강 직후 초록(R→G)이 정적 초록보다 **더 높은** 확신을 요구한다.
     * 이 케이스만이 "건너세요"로 이어지므로, 가장 위험한 안내에 가장 높은 문턱을 둔다.
     */
    private fun requiredConfidence(classId: Int): Float = when {
        classId == red -> config.minConfidenceRed
        // 직전이 빨강 확정 = R→G 전환("건너세요") 문맥. 오탐이 치명적이라 프리미엄 문턱.
        lastConfirmedColor == red -> config.minConfidenceGreenAfterRed
        else -> config.minConfidenceGreen
    }

    /**
     * 판단 대상 하나 선택.
     *
     * 핵심 안전 규칙: 직전이 빨강(= R→G 전환 감시 중)일 때, 초록이 빨강을 이기려면
     * (1) 초록 신뢰도가 충분히 높고 (2) 빨강을 마진만큼 앞서야 한다. 애매하면 **빨강 유지**.
     * 성급하게 초록으로 넘어가 "건너세요"가 나가는 것을 막는다.
     */
    private fun selectSignal(detections: List<RawSignalDetection>, previousColor: Int): RawSignalDetection? {
        val bestRed = detections.filter { it.classId == red }
            .maxWithOrNull(compareBy({ it.confidence }, { it.boxArea }))
        val bestGreen = detections.filter { it.classId == green }
            .maxWithOrNull(compareBy({ it.confidence }, { it.boxArea }))

        if (previousColor == red && bestGreen != null) {
            if (bestRed == null) return bestGreen
            val greenStrongEnough = bestGreen.confidence >= config.greenTransitionMinConfidence
            val greenBeatsRed = bestGreen.confidence >= bestRed.confidence + config.greenOverRedConfidenceMargin
            if (greenStrongEnough && greenBeatsRed) return bestGreen
        }
        if (bestRed != null) return bestRed
        return detections.maxWithOrNull(compareBy({ it.confidence }, { it.boxArea }))
    }

    companion object {
        const val COLOR_NONE = -1
        const val COLOR_RED = 0
        const val COLOR_GREEN = 1
    }
}

/**
 * 검출 1건 — 플랫폼 무관 입력 타입.
 * Android TrafficLightDetection / iOS Detection 을 이 형태로 변환해 엔진에 넘긴다.
 * box 값은 화면 대비 정규화(0~1).
 */
data class RawSignalDetection(
    val classId: Int,        // 0 = 빨강, 1 = 초록
    val confidence: Float,
    val boxWidth: Float,
    val boxHeight: Float,
) {
    val boxArea: Float get() = boxWidth * boxHeight
}

/** 색 전환 종류 — 안내 문구·진동·인터럽트가 여기서 갈린다. */
enum class SignalTransition {
    RED_NEW,        // 신규 빨강 (첫 인식)
    GREEN_TO_RED,   // 초록 → 빨강
    RED_TO_GREEN,   // 빨강 → 초록 (유일하게 "건너세요" 계열 안내)
    STATIC_GREEN,   // 정적 초록 (전환 못 봄) — 절대 "건너세요" 안 함
}

/** 침묵한 이유 — 침묵도 정보다. 플랫폼이 로그·디버그에 쓴다. */
enum class SilentReason {
    NO_DETECTION,        // 검출 없음
    ALL_TOO_SMALL,       // 크기 하한 미달 (너무 멀거나 노이즈)
    ALL_LOW_CONFIDENCE,  // 크기는 통과했으나 확신 부족
    STABILITY_PENDING,   // 연속 프레임 아직 부족
    FLICKER_LOCKOUT,     // 점멸 감지 후 안내 차단 중
    SAME_COLOR_QUIET,    // 같은 색 유지 — 다음 반복까지 조용
}

/** 엔진의 결정. 호출자는 이걸 받아 TTS·진동·로그 등 부수효과를 수행한다. */
sealed class SignalDecision {
    /** 안내하지 않음. [reason] 으로 왜인지 안다. 부수효과 없음(로그만). */
    data class Silent(
        val reason: SilentReason,
        val streak: Int = 0,
        val requiredFrames: Int = 0,
        val color: Int = SignalDecisionEngine.COLOR_NONE,
        /** STABILITY_PENDING 일 때 현재 후보를 관찰한 시간(ms). 진단용. */
        val elapsedMs: Long = 0L,
        /** STABILITY_PENDING 일 때 요구되는 관찰 시간(ms). 진단용. */
        val requiredMs: Long = 0L,
    ) : SignalDecision()

    /** 같은 색이 지속되어 heartbeat 간격이 됨 → 같은 색 재안내. */
    data class Repeat(val color: Int) : SignalDecision()

    /** 점멸 감지 → 경고 발화 + 강한 진동. [gapMs] 는 직전 전환과의 간격. */
    data class Flicker(val gapMs: Long, val previousColor: Int, val newColor: Int) : SignalDecision()

    /** 정상 안내. 플랫폼이 [transition] 에 맞는 문구를 로컬라이즈해 발화한다. */
    data class Announce(
        val color: Int,
        val transition: SignalTransition,
        val vibrate: Boolean,
        val interrupt: Boolean,
        val confidence: Float,
    ) : SignalDecision()
}

/**
 * 결정 파라미터. 기본값은 2026-07 Android 운영값과 동일.
 * 두 플랫폼이 같은 config 를 쓰면 동작이 완전히 일치한다.
 */
data class SignalDecisionConfig(
    // 신뢰도 (초록 비대칭)
    val minConfidenceRed: Float = 0.25f,
    val minConfidenceGreen: Float = 0.45f,
    // ⚠️ 빨강 직후 초록(= R→G 전환 = 유일하게 "건너세요"를 부르는 케이스)은
    //    '할인'이 아니라 '프리미엄'이다. 오탐이 곧 사람을 차도로 내보내므로,
    //    가장 위험한 안내인 만큼 정적 초록(0.45)보다 더 높은 확신을 요구한다.
    //    (0.55 = selectSignal 의 R→G 게이트와 동일 — 두 게이트를 일치시킨다)
    val minConfidenceGreenAfterRed: Float = 0.55f,
    // 크기 하한 (서브픽셀 노이즈 제거용, 두 색 공통). 0.004 ≈ 50m+.
    // 2026-07 실측: 0.008(≈27m)은 광폭 도로 건너편 신호(30~50m)를 ALL_TOO_SMALL 로 버렸다.
    // 완화해도 초록 오탐은 뒤의 높은 신뢰도 게이트(0.45~0.55)와 안정성 프레임이 막는다.
    val minBoxDimension: Float = 0.004f,
    // ── 안정성 (연속 프레임) ──
    // 2026-07: 구값(빨강 2·초록 3)은 화면에 색이 잠깐만 스쳐도 확정돼
    //   "켜자마자 빨간불" · "깜빡이지 않는데 깜빡임" 오작동을 냈다(모델 순간 오탐이 상태로 굳음).
    //   표본 수를 늘려 순간 노이즈를 걸러낸다.
    val redStabilityFrames: Int = 4,
    val greenStabilityFrames: Int = 5,
    val greenTransitionStabilityFrames: Int = 4,

    // ── 안정성 (경과 시간) ──
    // ⚠️ 2026-09 추가. **프레임 수만으로는 안전이 보장되지 않는다.**
    //
    //   2026-07 주석은 "추론이 3fps라 프레임 수가 곧 시간이다"라고 적었다. 그건 Android 얘기였고,
    //   iOS 는 스로틀 없이 초당 20~30회 추론하고 있었다. 같은 '4프레임'이
    //   Android 에서는 1.33초, iOS 에서는 0.16초였다 —
    //   **같은 앱이 두 OS 에서 8배 다르게 위험했다.** 이 엔진을 shared 에 둔 이유가
    //   바로 그런 분기를 막기 위해서인데, 단위가 프레임이라 뚫렸다.
    //
    //   그래서 벽시계 시간 바닥을 함께 건다. 프레임 수 AND 경과 시간을 둘 다 채워야 확정된다.
    //   이러면 추론 속도를 올려도(조준 성공률을 위해 필요하다) 안전이 깎이지 않고,
    //   플랫폼이 달라도 "최소 이만큼은 같은 색을 보고 있었다"가 보장된다.
    //
    //   값 근거: 초록은 오탐 비용이 비대칭적으로 크므로 빨강보다 길게.
    //   R→G 전환은 유일하게 "건너세요"를 부르므로 정적 초록과 같은 최장 시간을 요구한다.
    //   (보행 초록은 보통 15~30초라 0.8초 늦게 알리는 손해보다 틀리는 손해가 비교할 수 없이 크다)
    val redStabilityMs: Long = 600L,
    val greenStabilityMs: Long = 800L,
    val greenTransitionStabilityMs: Long = 800L,

    /**
     * 유효 검출 사이의 최대 허용 공백. 이보다 오래 끊기면 안정성 스트릭을 새로 시작한다.
     *
     * 시간 바닥([redStabilityMs] 등)이 "계속 보고 있었다"를 뜻하려면 이 가드가 반드시 있어야 한다.
     * 없으면 "1프레임 → 긴 공백 → 몰아서 N프레임" 으로 바닥을 우회할 수 있다.
     *
     * ⚠️ 2026-09-22 현장 로그로 1_000 → 2_000 상향.
     *   발열 스로틀링이 걸리면 추론이 226ms → 464ms(약 2fps)까지 느려진다. 그 상태에서
     *   신호등이 간헐적으로만 잡히면(한 프레임 보고 다음 놓침) 유효 검출 사이 간격이
     *   920~1400ms 로 벌어져 1초를 자주 넘긴다. 그때마다 스트릭이 리셋돼 **정상적으로
     *   보고 있는 초록조차 영영 확정되지 않았다** — 인식률 급락의 실제 원인.
     *   2초면 2fps 에서도 한두 프레임 놓침을 견디면서, "10초 공백 후 몰아치기" 우회는 여전히 막는다.
     *   (같은 색 연속 스트릭에만 관여하므로, 그 사이 다른 색이 잡히면 어차피 스트릭은 새로 시작된다)
     */
    val maxObservationGapMs: Long = 2_000L,
    /**
     * R→G "전환을 목격했다"고 인정하는 최대 간격: 마지막 빨강 프레임 ~ 초록 스트릭 시작.
     * 이보다 길면 그 사이 언제 초록이 됐는지 모르므로 "방금 바뀌었습니다"(건너세요) 대신 정적 초록으로 안내.
     * 보행 신호는 빨강→초록이 즉시 바뀐다. 3초면 발열 스로틀(약 2fps)과 모션 게이트의 프레임 누락을 견디면서,
     * "폰을 내렸다 15초 뒤 다시 듦" 같은 경우는 확실히 걸러낸다.
     */
    val transitionWitnessWindowMs: Long = 3_000L,
    // 선택 단계 R→G 안전 바이어스 (빨강·초록 동시 검출 시 초록이 이기는 조건)
    val greenTransitionMinConfidence: Float = 0.55f,
    val greenOverRedConfidenceMargin: Float = 0.05f,
    // 타이밍
    //   heartbeat < timeout 이어야 한다: 같은 색을 계속 보고 있는데(연속 프레임)
    //   heartbeat 가 timeout 이상이면, heartbeat 직전에 timeout 리셋이 먼저 걸려
    //   반복 안내가 영영 안 나갈 수 있다.
    //   2026-07: 같은 신호 반복이 잦다는 피드백 → 8초 → 12초로 완화 (timeout 도 20초로 상향).
    val heartbeatIntervalMs: Long = 12_000L,
    val detectionTimeoutMs: Long = 20_000L,
    val minPhaseDurationMs: Long = 4_000L,   // 정상 phase 최소 지속 (이보다 짧은 전환 = 점멸)
    val flickerLockoutMs: Long = 6_000L,     // 점멸 감지 후 안내 차단 기간
)
