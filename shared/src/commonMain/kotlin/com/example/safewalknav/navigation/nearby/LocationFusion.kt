package com.example.safewalknav.navigation.nearby

import com.example.safewalknav.navigation.geo.distanceBetween
import kotlin.math.max

/**
 * 위치 픽스 하나. accuracyM 은 OS 가 보고한 수평 정확도(68% 신뢰 반경, m).
 * timeMs 는 픽스 시각(ms) — 한 목록 안에서 같은 시계면 된다(차이만 쓴다). Android 는 단조 시계를 넣는다.
 */
data class LocationSample(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val timeMs: Long,
)

/**
 * 여러 픽스를 합친 결과.
 *
 * @property accuracyM 보고용 오차(m). **평균으로 줄었다고 주장하지 않는다** — 아래 설명 참고.
 * @property sampleCount 실제로 평균에 들어간 픽스 수
 */
data class FusedFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val sampleCount: Int,
)

/**
 * GPS 오차 보정 — 몇 초 동안 모은 픽스를 정확도 가중 평균으로 합친다.
 *
 * 왜 이렇게 하나:
 *  - 첫 픽스는 대개 Wi-Fi·기지국 기반이라 오차가 크다(30~100m). 그걸 그대로 쓰면
 *    "10m 안 주변 장소"가 엉뚱한 블록 이야기가 된다.
 *  - 정지 상태에서 픽스가 좌우로 흔들리는 성분(수 m)은 평균으로 꽤 줄어든다.
 *
 * 하지 않는 것:
 *  - **오차 값을 √n 으로 줄여 보고하지 않는다.** 도심 GPS 오차의 큰 몫은 건물 반사(멀티패스)라
 *    몇 초 사이 픽스끼리 같은 방향으로 틀린다(서로 독립이 아님). 평균해도 그 성분은 그대로다.
 *    그래서 보고 오차는 "가장 좋은 픽스의 오차" 로 보수적으로 둔다. 이 값이 검색 반경과
 *    "정확하지 않을 수 있습니다" 안내를 결정하므로, 낙관하면 사용자를 속이게 된다.
 */
object LocationFusion {

    /** 가장 좋은 픽스 대비 이 배수(또는 +[ACCURACY_SLACK_M])보다 나쁜 픽스는 버린다. */
    const val ACCURACY_RATIO_CUTOFF = 2.0f
    const val ACCURACY_SLACK_M = 5f

    /** 1차 평균에서 자기 오차의 이 배수보다 멀리 떨어진 픽스는 튄 값으로 보고 버린다. */
    const val OUTLIER_SIGMA = 2.0f

    /** 이 정도면 더 기다리지 않아도 된다(조기 종료 기준). */
    const val GOOD_ENOUGH_ACCURACY_M = 8f
    const val GOOD_ENOUGH_MIN_SAMPLES = 2

    /**
     * 조기 종료 전에 최소한 모을 픽스 수와 시간 폭.
     * 도심(빌딩 사이)에서는 OS 가 "오차 5m"라고 해도 실제로는 15~30m 틀리는 일이 흔하다.
     * 멀티패스 오차는 몇 초 안의 픽스끼리 같은 쪽으로 틀려서, 1초 간격 픽스 둘은 서로 맞아 보인다.
     * 3초 이상·4개 이상 모아야 흩어짐(spread)이 드러나고 [fuse] 가 오차를 넓혀 보고할 기회가 생긴다.
     */
    const val GOOD_ENOUGH_MIN_TOTAL_SAMPLES = 4
    const val GOOD_ENOUGH_MIN_SPAN_MS = 3_000L

    fun fuse(samples: List<LocationSample>): FusedFix? {
        val valid = samples.filter { it.accuracyM > 0f && it.accuracyM.isFinite() }
        if (valid.isEmpty()) return null

        val best = valid.minOf { it.accuracyM }
        val cutoff = max(best * ACCURACY_RATIO_CUTOFF, best + ACCURACY_SLACK_M)
        val kept = valid.filter { it.accuracyM <= cutoff }

        val first = weightedMean(kept)
        // 튄 값 제거 후 한 번 더 — 1차 평균에서 자기 오차의 2배 넘게 떨어진 픽스는 버린다.
        val inliers = kept.filter {
            distanceBetween(it.lat, it.lon, first.first, first.second) <= it.accuracyM * OUTLIER_SIGMA
        }

        if (inliers.isEmpty()) {
            // 픽스끼리 서로 크게 엇갈린다(예: 오차 5m 라던 두 픽스가 55m 떨어짐). 이때 평균은
            // **아무 데도 아닌 중간점**이고, 거기에 "오차 5m"를 붙이면 거짓 정밀도다.
            // 가장 정확한(같으면 가장 최근) 픽스 하나를 쓰고, 엇갈린 거리만큼 오차를 넓혀 보고한다 —
            // 그러면 반경이 넓어지고 "위치가 정확하지 않을 수 있습니다"가 먼저 나간다.
            val pick = kept.sortedWith(compareBy<LocationSample> { it.accuracyM }.thenByDescending { it.timeMs }).first()
            val spread = kept.maxOf { distanceBetween(it.lat, it.lon, pick.lat, pick.lon) }
            return FusedFix(pick.lat, pick.lon, max(pick.accuracyM, spread), sampleCount = 1)
        }

        val (lat, lon) = weightedMean(inliers)
        // 보고 오차 = max(가장 좋은 픽스 오차, 실제로 흩어진 정도). 흩어짐이 오차보다 크면
        // OS 가 말한 오차를 믿을 수 없다는 뜻이다.
        val spread = rmsDistance(inliers, lat, lon)
        return FusedFix(
            lat = lat,
            lon = lon,
            accuracyM = max(inliers.minOf { it.accuracyM }, spread),
            sampleCount = inliers.size,
        )
    }

    private fun rmsDistance(samples: List<LocationSample>, lat: Double, lon: Double): Float {
        var sum = 0.0
        for (s in samples) {
            val d = distanceBetween(s.lat, s.lon, lat, lon).toDouble()
            sum += d * d
        }
        return kotlin.math.sqrt(sum / samples.size).toFloat()
    }

    /**
     * 조기 종료해도 되는가.
     * 좋은 픽스 수만 보지 않고, 전체 픽스 수와 처음~마지막 픽스 사이 시간 폭도 본다
     * ([GOOD_ENOUGH_MIN_TOTAL_SAMPLES] 주석 — 짧게 모은 픽스는 서로 같은 쪽으로 틀려 정확해 보인다).
     * 시간 폭은 min~max 로 잰다 — 제공자(GPS·네트워크)가 섞이면 도착 순서와 시각 순서가 다를 수 있다.
     */
    fun isGoodEnough(samples: List<LocationSample>): Boolean {
        if (samples.size < GOOD_ENOUGH_MIN_TOTAL_SAMPLES) return false
        val span = samples.maxOf { it.timeMs } - samples.minOf { it.timeMs }
        if (span < GOOD_ENOUGH_MIN_SPAN_MS) return false
        val good = samples.count { it.accuracyM > 0f && it.accuracyM <= GOOD_ENOUGH_ACCURACY_M }
        return good >= GOOD_ENOUGH_MIN_SAMPLES
    }

    /** 1/σ² 가중 평균. 수십 m 범위라 위경도를 평면처럼 평균해도 된다. */
    private fun weightedMean(samples: List<LocationSample>): Pair<Double, Double> {
        var wSum = 0.0
        var latSum = 0.0
        var lonSum = 0.0
        for (s in samples) {
            val w = 1.0 / (s.accuracyM.toDouble() * s.accuracyM.toDouble())
            wSum += w
            latSum += s.lat * w
            lonSum += s.lon * w
        }
        return Pair(latSum / wSum, lonSum / wSum)
    }
}
