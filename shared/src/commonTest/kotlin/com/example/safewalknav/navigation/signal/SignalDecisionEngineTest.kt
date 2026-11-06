package com.example.safewalknav.navigation.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * SignalDecisionEngine 안전 로직 테스트.
 *
 * 이 로직은 "지금 건너도 되는지"를 판단한다. 규칙이 하나라도 깨지면 사람이 다칠 수 있으므로
 * 각 안전 규칙을 개별로 못 박는다. (Android/iOS 가 이 하나를 공유하므로 여기만 지키면 됨)
 */
class SignalDecisionEngineTest {

    private val cfg = SignalDecisionConfig()  // 운영 기본값

    /**
     * 프레임 간격. Android 운영값(333ms, 약 3fps)에 가깝게 잡는다.
     *
     * ⚠️ 100ms 로 잡으면 안 된다. 확정에는 프레임 수뿐 아니라 **경과 시간**도 필요해서
     * (`redStabilityMs` 등), 100ms × 4프레임 = 300ms 로는 600ms 바닥을 못 넘는다.
     * 이 상수는 "실제 기기에서 이 정도 간격으로 프레임이 온다"는 전제를 테스트에 박아둔 것이다.
     */
    private val STEP = 300L

    // 헬퍼: 충분히 크고 확신 있는 검출 하나
    private fun red(conf: Float = 0.9f) = RawSignalDetection(0, conf, 0.1f, 0.1f)
    private fun green(conf: Float = 0.9f) = RawSignalDetection(1, conf, 0.1f, 0.1f)

    /** 안정성 프레임을 채워 특정 색을 확정 상태로 만든다. 마지막 프레임 **다음** 시각을 돌려준다. */
    private fun SignalDecisionEngine.confirm(det: RawSignalDetection, frames: Int, startMs: Long): Long {
        var t = startMs
        repeat(frames) { decide(listOf(det), t); t += STEP }
        return t
    }

    /** [durationMs] 동안 같은 검출을 STEP 간격으로 계속 넣는다(실제 기기처럼 계속 보고 있음). 다음 시각을 돌려준다. */
    private fun SignalDecisionEngine.watch(det: RawSignalDetection, startMs: Long, durationMs: Long): Long {
        var t = startMs
        val until = startMs + durationMs
        while (t < until) { decide(listOf(det), t); t += STEP }
        return t
    }

    /** 확정(Announce/Flicker)이 나올 때까지 최대 [maxFrames] 넣고, 그 결정을 돌려준다. */
    private fun SignalDecisionEngine.feedUntilDecision(
        det: RawSignalDetection, startMs: Long, maxFrames: Int = 20,
    ): Pair<SignalDecision, Long> {
        var t = startMs
        var last: SignalDecision = SignalDecision.Silent(SilentReason.NO_DETECTION)
        repeat(maxFrames) {
            last = decide(listOf(det), t); t += STEP
            if (last is SignalDecision.Announce || last is SignalDecision.Flicker) return last to t
        }
        return last to t
    }

    // ─────────────────────────────────────────────────────────────
    // 1) 크기 하한
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 너무_작은_박스는_침묵_ALL_TOO_SMALL() {
        val engine = SignalDecisionEngine(cfg)
        // ⚠️ 예전에는 0.005f 로 하드코딩돼 있었는데, minBoxDimension 이 0.008 → 0.004 로
        //    내려간 뒤로 0.005 가 오히려 **통과**해서 이 테스트는 계속 실패하고 있었다.
        //    설정값을 참조해 따라가게 한다.
        val half = cfg.minBoxDimension / 2f
        val tiny = RawSignalDetection(0, 0.9f, half, half)
        val d = engine.decide(listOf(tiny), 1000)
        assertIs<SignalDecision.Silent>(d)
        assertEquals(SilentReason.ALL_TOO_SMALL, d.reason)
    }

    @Test
    fun 검출이_아예_없으면_NO_DETECTION() {
        val engine = SignalDecisionEngine(cfg)
        val d = engine.decide(emptyList(), 1000)
        assertIs<SignalDecision.Silent>(d)
        assertEquals(SilentReason.NO_DETECTION, d.reason)
    }

    // ─────────────────────────────────────────────────────────────
    // 2) 신뢰도 비대칭 — 이게 안전의 핵심
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 초록은_045미만이면_침묵한다() {
        val engine = SignalDecisionEngine(cfg)
        // 0.40 초록 — 빨강 임계(0.25)는 넘지만 초록 임계(0.45)는 못 넘음
        val d = engine.decide(listOf(green(0.40f)), 1000)
        assertIs<SignalDecision.Silent>(d)
        assertEquals(SilentReason.ALL_LOW_CONFIDENCE, d.reason)
    }

    @Test
    fun 빨강은_같은_040신뢰도로도_통과한다() {
        val engine = SignalDecisionEngine(cfg)
        // 같은 0.40 이라도 빨강은 임계 0.25 를 넘으므로 후보로 살아남아 안정성 단계로 감
        val d = engine.decide(listOf(red(0.40f)), 1000)
        // 아직 1프레임이라 STABILITY_PENDING (침묵이지만 이유가 다름 = 필터는 통과)
        assertIs<SignalDecision.Silent>(d)
        assertEquals(SilentReason.STABILITY_PENDING, d.reason)
    }

    // ─────────────────────────────────────────────────────────────
    // 3) 안정성 프레임
    // ─────────────────────────────────────────────────────────────

    // ⚠️ 이 두 테스트는 2026-07 에 프레임 수를 2·3 → 4·5 로 올릴 때 같이 안 고쳐져서
    //    한동안 깨진 채 방치돼 있었다. 하드코딩 대신 cfg 를 참조하도록 바꿔
    //    앞으로 설정값이 바뀌어도 자동으로 따라가게 한다.

    @Test
    fun 빨강은_설정된_프레임수를_채워야_확정된다() {
        val engine = SignalDecisionEngine(cfg)
        var t = 1000L
        repeat(cfg.redStabilityFrames - 1) {   // 마지막 직전까지는 전부 침묵
            assertIs<SignalDecision.Silent>(engine.decide(listOf(red()), t))
            t += STEP
        }
        val d = engine.decide(listOf(red()), t)
        assertIs<SignalDecision.Announce>(d)
        assertEquals(SignalDecisionEngine.COLOR_RED, d.color)
        assertEquals(SignalTransition.RED_NEW, d.transition)
    }

    @Test
    fun 초록은_빨강보다_더_많은_프레임을_요구한다() {
        assertTrue(
            cfg.greenStabilityFrames > cfg.redStabilityFrames,
            "초록 오탐은 사람을 차도로 내보낸다. 빨강보다 보수적이어야 한다."
        )
        val engine = SignalDecisionEngine(cfg)
        var t = 1000L
        repeat(cfg.greenStabilityFrames - 1) {
            assertIs<SignalDecision.Silent>(engine.decide(listOf(green()), t))
            t += STEP
        }
        val d = engine.decide(listOf(green()), t)
        assertIs<SignalDecision.Announce>(d)
        assertEquals(SignalTransition.STATIC_GREEN, d.transition)  // 첫 초록은 정적 초록
    }

    // ─────────────────────────────────────────────────────────────
    // 3-b) 시간 바닥 — 프레임 수를 채워도 시간이 모자라면 확정 안 됨
    //      iOS 가 스로틀 없이 초당 25회 추론해 0.16초 만에 확정하던 문제를 못 박는다.
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 프레임수를_채워도_시간이_모자라면_확정되지_않는다() {
        val engine = SignalDecisionEngine(cfg)
        // 10ms 간격 = 초당 100프레임. 프레임 수는 순식간에 채워지지만 시간 바닥은 못 넘는다.
        var t = 1000L
        var last: SignalDecision = SignalDecision.Silent(SilentReason.NO_DETECTION)
        repeat(cfg.redStabilityFrames * 3) {
            last = engine.decide(listOf(red()), t)
            t += 10
        }
        assertIs<SignalDecision.Silent>(last)
        assertEquals(SilentReason.STABILITY_PENDING, last.reason)

        // 같은 색을 계속 보되 시간만 흘리면 확정된다.
        val after = engine.decide(listOf(red()), 1000 + cfg.redStabilityMs + 10)
        assertIs<SignalDecision.Announce>(after)
        assertEquals(SignalTransition.RED_NEW, after.transition)
    }

    /**
     * 시간 바닥을 우회하는 경로를 막는다.
     *
     * 검출 없음·확신 부족 프레임은 스트릭을 건드리지 않고 조기 반환하므로,
     * 관찰 공백 가드가 없으면 "1프레임 → 긴 공백 → 몰아서 N프레임" 으로
     * "오래 전에 시작됐다"는 사실만 가지고 바닥을 통과할 수 있다.
     * 확정 프레임이 전부 0.3초 안에 몰려 있는데도 말이다.
     */
    @Test
    fun 관찰이_끊겼다_몰아치면_확정되지_않는다() {
        val engine = SignalDecisionEngine(cfg)
        engine.decide(listOf(green()), 1_000_000)          // 1프레임만 보고
        // 10초 공백 뒤, 40ms 간격(초당 25프레임)으로 몰아친다 — iOS 스로틀 없는 상황
        var t = 1_010_000L
        var last: SignalDecision = SignalDecision.Silent(SilentReason.NO_DETECTION)
        repeat(cfg.greenStabilityFrames + 3) {
            last = engine.decide(listOf(green()), t)
            t += 40
        }
        assertIs<SignalDecision.Silent>(last)
        assertEquals(SilentReason.STABILITY_PENDING, last.reason)
        assertTrue(
            last.elapsedMs < cfg.greenStabilityMs,
            "공백 뒤 스트릭은 새로 시작돼야 한다. elapsedMs=${last.elapsedMs}"
        )
    }

    @Test
    fun 한두프레임_드롭은_견딘다() {
        // 모션 게이트가 한 프레임을 건너뛰어도(666ms 공백) 스트릭이 살아 있어야 한다.
        // ⚠️ 2026-10 수정: 예전엔 **마지막** 결정이 Announce 이길 기대했는데, 확정은 5번째 프레임
        //    (1665ms)에서 일어나고 6번째는 같은 색 유지(SAME_COLOR_QUIET)라 테스트가 늘 실패했다.
        //    검증하려던 것은 "드롭이 있어도 확정에 도달한다"이므로 시퀀스 중 확정 여부를 본다.
        val engine = SignalDecisionEngine(cfg)
        val decisions = listOf(0L, 333L, 999L, 1332L, 1665L, 1998L).map {
            engine.decide(listOf(green()), 1_000_000 + it)
        }
        assertTrue(
            decisions.any { it is SignalDecision.Announce },
            "666ms 드롭 한 번으로 스트릭이 끊기면 안 된다. got=$decisions"
        )
    }

    @Test
    fun 시간바닥도_초록이_빨강보다_길다() {
        assertTrue(
            cfg.greenStabilityMs > cfg.redStabilityMs,
            "초록은 오탐 비용이 비대칭적으로 크다"
        )
        assertTrue(
            cfg.greenTransitionStabilityMs >= cfg.greenStabilityMs,
            "R→G 는 유일하게 '건너세요'를 부르는 전환이다. 가장 보수적이어야 한다."
        )
    }

    // ─────────────────────────────────────────────────────────────
    // 4) 정적 초록은 "건너세요"가 아니다 — 진동 없음, STATIC_GREEN
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 첫_초록은_정적초록이며_진동하지_않는다() {
        val engine = SignalDecisionEngine(cfg)
        var t = 1000L
        var last: SignalDecision = SignalDecision.Silent(SilentReason.NO_DETECTION)
        repeat(cfg.greenStabilityFrames) { last = engine.decide(listOf(green()), t); t += STEP }
        assertIs<SignalDecision.Announce>(last)
        assertEquals(SignalTransition.STATIC_GREEN, last.transition)
        assertTrue(!last.vibrate, "정적 초록은 진동하면 안 된다 (출발 신호로 오인 위험)")
    }

    // ─────────────────────────────────────────────────────────────
    // 5) R→G 전환만이 유일하게 진동+인터럽트하는 "건너세요" 케이스
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 빨강에서_초록_전환은_RED_TO_GREEN이고_진동한다() {
        val engine = SignalDecisionEngine(cfg)
        // 빨강 확정
        var t = engine.confirm(red(), cfg.redStabilityFrames, 1000)
        assertEquals(SignalDecisionEngine.COLOR_RED, engine.confirmedColor)
        // 빨강을 **계속 보면서** 시간을 흘린다 (minPhaseDuration 초과).
        // ⚠️ 2026-10: 예전엔 빈 시간으로 건너뛰었는데, 그건 "빨강을 못 본 채 초록을 만난" 경우라
        //    이제는 전환 목격이 아니다(정적 초록으로 강등 — 아래 별도 테스트).
        t = engine.watch(red(), t, cfg.minPhaseDurationMs + 1000)
        // 강한 초록(0.9) 으로 전환 — greenTransitionMinConfidence(0.55) 넘고 빨강 없음
        var last: SignalDecision = SignalDecision.Silent(SilentReason.NO_DETECTION)
        repeat(cfg.greenTransitionStabilityFrames) { last = engine.decide(listOf(green()), t); t += STEP }
        assertIs<SignalDecision.Announce>(last)
        assertEquals(SignalTransition.RED_TO_GREEN, last.transition)
        assertTrue(last.vibrate, "R→G 전환은 강한 진동 필수")
        assertTrue(last.interrupt, "R→G 전환은 즉시 인터럽트 발화")
    }

    // ─────────────────────────────────────────────────────────────
    // 6) R→G 선택 안전 바이어스 — 애매하면 빨강 유지
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 빨강확정_상태에서_약한초록은_빨강을_이기지_못한다() {
        val engine = SignalDecisionEngine(cfg)
        var t = engine.confirm(red(0.9f), cfg.redStabilityFrames, 1000)
        // ⚠️ 2026-10 수정: 예전엔 5초를 **빈 시간**으로 건너뛰었다. maxObservationGapMs(2초) 도입 뒤로는
        //    그 공백이 스트릭을 리셋해 STABILITY_PENDING 이 나와 테스트가 늘 실패했다.
        //    실제 기기처럼 그동안 빨강을 계속 보고 있었다고 두고 시간을 흘린다.
        val until = t + cfg.minPhaseDurationMs + 1000
        while (t < until) { engine.decide(listOf(red(0.9f)), t); t += STEP }
        // 초록 0.50 (< 0.55 임계) + 빨강 0.9 동시 존재 → 초록이 이기면 안 됨 → 빨강 유지
        val d = engine.decide(listOf(green(0.50f), red(0.9f)), t)
        // 빨강이 선택돼 같은 색 유지 → SAME_COLOR_QUIET 또는 Repeat (둘 다 빨강 유지 의미)
        val stillRed = when (d) {
            is SignalDecision.Silent -> d.reason == SilentReason.SAME_COLOR_QUIET
            is SignalDecision.Repeat -> d.color == SignalDecisionEngine.COLOR_RED
            else -> false
        }
        assertTrue(stillRed, "애매한 초록으로 성급히 넘어가면 안 된다 (빨강 유지 기대), got=$d")
    }

    // ─────────────────────────────────────────────────────────────
    // 7) 점멸 감지 — 짧은 간격의 초록↔빨강 반복은 Flicker + 락아웃
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 짧은간격_전환은_점멸로_감지되고_이후_락아웃된다() {
        val engine = SignalDecisionEngine(cfg)
        // 초록 확정
        var t = engine.confirm(green(), cfg.greenStabilityFrames, 1000)
        assertEquals(SignalDecisionEngine.COLOR_GREEN, engine.confirmedColor)
        // 곧바로(=minPhaseDuration 이내) 빨강으로 전환 시도 → 점멸
        var flick: SignalDecision? = null
        repeat(cfg.redStabilityFrames) { flick = engine.decide(listOf(red()), t); t += STEP }
        assertIs<SignalDecision.Flicker>(flick)

        // 락아웃 동안은 무조건 침묵
        val during = engine.decide(listOf(red()), t + 100)
        assertIs<SignalDecision.Silent>(during)
        assertEquals(SilentReason.FLICKER_LOCKOUT, during.reason)
    }

    // ─────────────────────────────────────────────────────────────
    // 8) heartbeat — 같은 색 유지 시 간격마다 재안내
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 같은색_유지시_heartbeat_간격마다_재안내() {
        val engine = SignalDecisionEngine(cfg)
        var t = engine.confirm(red(), cfg.redStabilityFrames, 1000)  // 빨강 확정(첫 Announce)
        // 바로 다음 프레임 — 조용
        val quiet = engine.decide(listOf(red()), t)
        assertIs<SignalDecision.Silent>(quiet)
        assertEquals(SilentReason.SAME_COLOR_QUIET, quiet.reason)
        t += STEP
        // heartbeat 간격 동안 빨강을 **계속** 본다 — 재안내가 정확히 한 번 나와야 한다.
        // ⚠️ 2026-10 수정: 예전엔 12초를 빈 시간으로 건너뛰어, maxObservationGapMs(2초) 리셋에 걸려
        //    STABILITY_PENDING 이 나왔다(늘 실패). 실제 기기는 그동안 프레임이 계속 온다.
        val until = t + cfg.heartbeatIntervalMs + 200
        val repeats = mutableListOf<SignalDecision.Repeat>()
        while (t < until) {
            val d = engine.decide(listOf(red()), t)
            if (d is SignalDecision.Repeat) repeats += d
            t += STEP
        }
        assertEquals(1, repeats.size, "heartbeat 간격 안에서는 정확히 한 번 재안내")
        assertEquals(SignalDecisionEngine.COLOR_RED, repeats[0].color)
    }

    // ─────────────────────────────────────────────────────────────
    // 9) reset — 상태 완전 초기화
    // ─────────────────────────────────────────────────────────────

    @Test
    fun reset하면_확정색이_사라진다() {
        val engine = SignalDecisionEngine(cfg)
        engine.confirm(red(), cfg.redStabilityFrames, 1000)
        assertEquals(SignalDecisionEngine.COLOR_RED, engine.confirmedColor)
        engine.reset()
        assertEquals(SignalDecisionEngine.COLOR_NONE, engine.confirmedColor)
    }

    // ─────────────────────────────────────────────────────────────
    // 10) 정상 G→R 전환 — 진동 있는 안전 안내 (점멸 아님)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 정상_초록에서_빨강_전환은_진동한다() {
        val engine = SignalDecisionEngine(cfg)
        var t = engine.confirm(green(), cfg.greenStabilityFrames, 1000)
        assertEquals(SignalDecisionEngine.COLOR_GREEN, engine.confirmedColor)
        // 점멸로 오판되지 않도록 정상 phase(minPhaseDuration) 초과 대기
        t += cfg.minPhaseDurationMs + 1000
        var last: SignalDecision = SignalDecision.Silent(SilentReason.NO_DETECTION)
        repeat(cfg.redStabilityFrames) { last = engine.decide(listOf(red()), t); t += STEP }
        assertIs<SignalDecision.Announce>(last)
        assertEquals(SignalTransition.GREEN_TO_RED, last.transition)
        assertTrue(last.vibrate, "G→R 전환도 진동으로 주의 환기")
        assertTrue(last.interrupt, "G→R 전환은 즉시 인터럽트")
    }

    // ─────────────────────────────────────────────────────────────
    // 11) R→G 프리미엄 문턱 — 빨강 직후 약한 초록(단독)은 "건너세요" 안 됨
    //     (가장 위험한 안내이므로 정적 초록보다 높은 확신 요구)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 빨강직후_약한초록_단독은_건너세요로_확정되지_않는다() {
        val engine = SignalDecisionEngine(cfg)
        var t = engine.confirm(red(), cfg.redStabilityFrames, 1000)
        t = engine.watch(red(), t, cfg.minPhaseDurationMs + 1000)
        // 빨강 사라지고 초록만 존재하되 신뢰도 0.50 (< minConfidenceGreenAfterRed 0.55)
        // → 필터에서 걸러져 침묵. R→G "건너세요" 로 넘어가면 안 된다.
        var last: SignalDecision = SignalDecision.Announce(
            SignalDecisionEngine.COLOR_GREEN, SignalTransition.RED_TO_GREEN, true, true, 1f
        )
        repeat(cfg.greenTransitionStabilityFrames + 1) {
            last = engine.decide(listOf(green(0.50f)), t); t += STEP
        }
        val notCrossing = when (last) {
            is SignalDecision.Silent -> (last as SignalDecision.Silent).reason == SilentReason.ALL_LOW_CONFIDENCE
            else -> false
        }
        assertTrue(notCrossing, "빨강 직후 0.50 초록은 확신 부족으로 침묵해야 한다, got=$last")

        // 반면 강한 초록(0.60 ≥ 0.55)은 정상적으로 R→G 확정 — 빨강을 다시 보고 있다가 바로 바뀐 경우
        var t2 = engine.watch(red(), t + STEP, 2_000)
        var strong: SignalDecision = SignalDecision.Silent(SilentReason.NO_DETECTION)
        repeat(cfg.greenTransitionStabilityFrames) { strong = engine.decide(listOf(green(0.60f)), t2); t2 += STEP }
        assertIs<SignalDecision.Announce>(strong)
        assertEquals(SignalTransition.RED_TO_GREEN, strong.transition)
    }

    // ─────────────────────────────────────────────────────────────
    // 12) R→G 는 "목격"해야 한다 — 빨강을 놓친 뒤 만난 초록은 "건너세요"가 아니다
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 빨강을_듣고_폰을_내렸다_다시_들면_초록은_정적초록이다() {
        val engine = SignalDecisionEngine(cfg)
        var t = engine.confirm(red(), cfg.redStabilityFrames, 1000)
        assertEquals(SignalDecisionEngine.COLOR_RED, engine.confirmedColor)
        // 15초 동안 아무것도 못 봄 (detectionTimeoutMs 20초보다 짧아 빨강 확정은 남아 있다)
        t += 15_000
        val (d, _) = engine.feedUntilDecision(green(), t)
        assertIs<SignalDecision.Announce>(d)
        assertEquals(
            SignalTransition.STATIC_GREEN, d.transition,
            "그 사이 언제 초록이 됐는지 모른다 — '방금 바뀌었습니다, 건너세요'를 말하면 안 된다"
        )
        assertTrue(!d.vibrate, "정적 초록은 진동하지 않는다")
    }

    @Test
    fun 빨강을_보다가_목격창_안에서_바뀐_초록은_RED_TO_GREEN() {
        val engine = SignalDecisionEngine(cfg)
        var t = engine.confirm(red(), cfg.redStabilityFrames, 1000)
        t = engine.watch(red(), t, cfg.minPhaseDurationMs + 1000)
        // 프레임 몇 장 놓침(목격창보다 짧은 공백) 뒤 초록
        t += cfg.transitionWitnessWindowMs - 1000
        val (d, _) = engine.feedUntilDecision(green(), t)
        assertIs<SignalDecision.Announce>(d)
        assertEquals(SignalTransition.RED_TO_GREEN, d.transition)
    }

    // ─────────────────────────────────────────────────────────────
    // 13) 초록→빨강 직후 몇 초 만의 초록은 "건너세요"가 아니라 점멸(오탐)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun 초록에서_빨강으로_바뀐_직후_초록은_점멸로_본다() {
        val engine = SignalDecisionEngine(cfg)
        var t = engine.confirm(green(), cfg.greenStabilityFrames, 1000)
        t = engine.watch(green(), t, cfg.minPhaseDurationMs + 1000)
        val (toRed, t1) = engine.feedUntilDecision(red(), t)
        assertIs<SignalDecision.Announce>(toRed)
        assertEquals(SignalTransition.GREEN_TO_RED, toRed.transition)
        // 빨강이 된 지 몇 초 안에 강한 초록이 잡힘 — 보행 빨강이 이렇게 짧을 수 없다
        val (d, _) = engine.feedUntilDecision(green(), t1)
        assertIs<SignalDecision.Flicker>(d, "G→R 직후 R→G 는 '건너세요'가 아니라 점멸 경고여야 한다, got=$d")
    }

    @Test
    fun 점멸_락아웃_직후의_빨강에서_곧바로_초록은_점멸로_본다() {
        val engine = SignalDecisionEngine(cfg)
        // 초록 확정 → 곧바로 빨강 = 점멸 → 락아웃
        var t = engine.confirm(green(), cfg.greenStabilityFrames, 1000)
        val (flick, t1) = engine.feedUntilDecision(red(), t)
        assertIs<SignalDecision.Flicker>(flick)
        // 락아웃이 끝날 때까지 빨강을 계속 본다
        t = engine.watch(red(), t1, cfg.flickerLockoutMs + 100)
        val (redAgain, t2) = engine.feedUntilDecision(red(), t)
        assertIs<SignalDecision.Announce>(redAgain)
        assertEquals(SignalTransition.RED_NEW, redAgain.transition)
        // 방금까지 깜빡이던 신호 — 몇 초 만의 초록을 "건너세요"로 통과시키면 안 된다
        val (d, _) = engine.feedUntilDecision(green(), t2)
        assertIs<SignalDecision.Flicker>(d, "락아웃 직후 빨강 → 곧바로 초록은 점멸이어야 한다, got=$d")
    }

    @Test
    fun 첫_빨강_확정_직후의_초록은_정상_전환이다() {
        // 긴 빨강의 끝자락을 잡은 경우 — 점멸로 보면 안 된다(기존 동작 유지)
        val engine = SignalDecisionEngine(cfg)
        val t = engine.confirm(red(), cfg.redStabilityFrames, 1000)
        val (d, _) = engine.feedUntilDecision(green(), t)
        assertIs<SignalDecision.Announce>(d)
        assertEquals(SignalTransition.RED_TO_GREEN, d.transition)
    }
}
