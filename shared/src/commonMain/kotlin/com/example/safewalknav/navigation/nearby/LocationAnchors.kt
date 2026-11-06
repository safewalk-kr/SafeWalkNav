package com.example.safewalknav.navigation.nearby

import com.example.safewalknav.navigation.geo.distanceBetween
import com.example.safewalknav.navigation.tmap.POIResult

/**
 * "지금 어디인가"를 시각장애인이 쓸 수 있는 말로 바꾸기 위한 **기준점(랜드마크)**.
 *
 * 왜 도로명 주소가 아닌가 (2026-10 실기 피드백):
 *   "필동로1길 30 근처입니다"는 눈으로 표지판을 볼 수 없는 사람에게 쓸모가 없다.
 *   시각장애인은 보행 교육(O&M)에서 **지하철 출구·버스정류장·교차로·큰 건물**을 기준으로
 *   길을 익히고, 모르면 지나가는 사람에게 그 이름으로 묻는다. 그래서 위치를
 *   "충무로역 1번 출구, 2시 방향 약 80미터" 처럼 기준점과의 관계로 말한다.
 */
enum class AnchorKind { PED_SIGNAL, SUBWAY_EXIT, BUS_STOP, INTERSECTION }

data class Anchor(
    val kind: AnchorKind,
    val name: String,
    val lat: Double,
    val lon: Double,
    val distanceM: Float,
) {
    /** 말할 때 붙일 이름. 정류장은 "정류장"이 이름에 없으면 붙인다. */
    val spokenName: String
        get() = when (kind) {
            AnchorKind.BUS_STOP -> if (name.contains("정류")) name else "$name 버스정류장"
            else -> name
        }
}

object LocationAnchors {

    /** 이 검색어로 찾은 결과를 각 기준점 후보로 넘긴다. (화면 박스 검색과 같은 호출을 재사용) */
    const val KEYWORD_SUBWAY_EXIT = "출구"
    const val KEYWORD_BUS_STOP = "버스정류장"
    const val KEYWORD_INTERSECTION = "사거리"

    /** 이보다 멀면 기준점으로 쓰지 않는다 — 너무 멀면 "근처"가 아니라 혼란이다. */
    const val PED_SIGNAL_MAX_M = 50f
    const val SUBWAY_EXIT_MAX_M = 300f
    const val BUS_STOP_MAX_M = 200f
    const val INTERSECTION_MAX_M = 150f

    /** 한 번에 말하는 기준점 수. 셋 이상이면 기억에 안 남는다. */
    const val MAX_ANCHORS = 2

    /** "충무로역 1번출구", "을지로3가역 12번 출구" 같은 이름만. 주차장 출구 등은 거른다. */
    private val SUBWAY_EXIT = Regex("""역.*\d+\s*번\s*출구""")

    fun isSubwayExit(name: String): Boolean = SUBWAY_EXIT.containsMatchIn(name)

    fun isIntersection(name: String): Boolean =
        name.endsWith("사거리") || name.endsWith("삼거리") || name.endsWith("오거리") || name.contains("교차로")

    /**
     * "버스정류장" 검색에 같이 걸리는 '정류장 앞 카페' 같은 것을 거른다.
     * TMap 정류장 POI 는 이름에 "정류장"이 없는 경우가 많아(예: "동국대입구.한국의집") 업종명을 우선 본다.
     */
    fun isBusStop(poi: POIResult): Boolean =
        poi.category?.contains("정류") == true || poi.name.trim().endsWith("정류장")

    /**
     * 종류별로 가장 가까운 기준점 하나씩 → 거리순 → 최대 [MAX_ANCHORS] 개.
     */
    /**
     * @param nearestPedSignal 가장 가까운 보행 신호등(서울시 데이터)과 그 거리. 50m 안이면 기준점이 된다 —
     *   횡단보도 앞에 선 사람에게는 이게 제일 쓸모 있는 기준점이다.
     */
    fun pick(
        fixLat: Double,
        fixLon: Double,
        exitCandidates: List<POIResult>,
        busStopCandidates: List<POIResult>,
        intersectionCandidates: List<POIResult>,
        nearestPedSignal: Pair<GeoPoint, Float>? = null,
    ): List<Anchor> {
        val signal = nearestPedSignal
            ?.takeIf { it.second <= PED_SIGNAL_MAX_M }
            ?.let { (p, d) -> Anchor(AnchorKind.PED_SIGNAL, "보행 신호등", p.lat, p.lon, d) }

        fun nearest(kind: AnchorKind, pois: List<POIResult>, maxM: Float, ok: (POIResult) -> Boolean): Anchor? =
            pois.asSequence()
                .filter(ok)
                .map { Anchor(kind, it.name.trim(), it.lat, it.lon, distanceBetween(fixLat, fixLon, it.lat, it.lon)) }
                .filter { it.distanceM <= maxM }
                .minByOrNull { it.distanceM }

        return listOfNotNull(
            signal,
            nearest(AnchorKind.INTERSECTION, intersectionCandidates, INTERSECTION_MAX_M) { isIntersection(it.name) },
            nearest(AnchorKind.SUBWAY_EXIT, exitCandidates, SUBWAY_EXIT_MAX_M) { isSubwayExit(it.name) },
            nearest(AnchorKind.BUS_STOP, busStopCandidates, BUS_STOP_MAX_M) { isBusStop(it) },
        ).sortedBy { it.distanceM }.take(MAX_ANCHORS)
    }
}
