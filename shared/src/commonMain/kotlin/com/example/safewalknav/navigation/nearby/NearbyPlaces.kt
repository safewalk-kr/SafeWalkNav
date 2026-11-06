package com.example.safewalknav.navigation.nearby

import com.example.safewalknav.navigation.geo.distanceBetween
import com.example.safewalknav.navigation.tmap.POIResult
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 주변 장소 한 곳 (화면 박스 하나).
 *
 * @property lat/lon 방향·거리 계산에 쓰는 점 — 장소 중심 좌표(입구 좌표 아님, [NearbyAssembler] 참고).
 * @property distanceM 보정된 내 위치에서의 거리(m)
 */
data class NearbyPlace(
    val name: String,
    val category: String,
    val lat: Double,
    val lon: Double,
    val distanceM: Float,
)

/** 장소 검색 한 묶음의 결과. [radiusM] 은 실제로 적용된(넓혀졌을 수도 있는) 반경. */
data class NearbyResult(
    val places: List<NearbyPlace>,
    val radiusM: Int,
    /** 기본 반경에서 결과가 모자라 넓혔는가 — 화면·음성에 알린다. */
    val expanded: Boolean,
)

/**
 * 검색 반경 정책.
 *
 * 기본은 10m. 단, 위치 오차가 그보다 크면 반경을 오차만큼 넓힌다(최대 [ACCURACY_RADIUS_CAP_M]) —
 * 오차가 15m 인데 10m 안만 보면, 실제로 바로 옆에 있는 곳이 빠지고 엉뚱한 곳이 들어온다.
 *
 * 결과가 [MIN_RESULTS] 개보다 적으면 30 → 50 → 100 → 200 → 300m 로 넓힌다.
 * (2026-10 실기 피드백: 50m 상한이면 주택가·이면도로에서 "찾은 장소가 없습니다"만 반복됐다.
 *  박스에 거리가 함께 표시되므로, 비워 두는 것보다 가장 가까운 곳들을 보여 주는 편이 낫다.)
 */
object NearbyPolicy {
    const val BASE_RADIUS_M = 10
    const val ACCURACY_RADIUS_CAP_M = 50
    const val MAX_RADIUS_M = 300
    val EXPAND_STEPS_M = listOf(10, 30, 50, 100, 200, 300)
    const val MIN_RESULTS = 3
    const val MAX_SHOWN = 8

    /** 이보다 오차가 크면 "위치가 정확하지 않을 수 있습니다" 를 먼저 말한다. */
    const val POOR_ACCURACY_M = 20f

    /** 같은 이름이 이 거리 안에 또 있으면 하나로 본다(카테고리 검색 중복). */
    const val DEDUP_DISTANCE_M = 15f

    /** 위치 오차를 반영한 시작 반경. */
    fun baseRadius(accuracyM: Float): Int {
        val acc = if (accuracyM.isFinite() && accuracyM > 0f) ceil(accuracyM).toInt() else BASE_RADIUS_M
        return min(ACCURACY_RADIUS_CAP_M, max(BASE_RADIUS_M, acc))
    }

    /**
     * 시작 반경 이상인 단계 중, 결과가 [MIN_RESULTS] 개 이상 들어오는 가장 작은 반경.
     * 어느 단계도 못 채우면 최대 반경.
     */
    fun chooseRadius(base: Int, sortedDistances: List<Float>): Int {
        val steps = (listOf(base) + EXPAND_STEPS_M.filter { it > base }).distinct()
        for (r in steps) {
            if (sortedDistances.count { it <= r } >= MIN_RESULTS) return r
        }
        return max(base, MAX_RADIUS_M)
    }
}

/**
 * 카테고리별 검색 결과를 합쳐 화면에 띄울 목록을 만든다.
 *
 * 1) 장소 중심 좌표로 거리 계산
 * 2) 같은 이름·가까운 위치 중복 제거 (여러 카테고리 검색에 같은 곳이 걸린다)
 * 3) 거리순 정렬 → 반경 정책 적용 → 최대 [NearbyPolicy.MAX_SHOWN] 개
 */
object NearbyAssembler {

    fun assemble(
        fixLat: Double,
        fixLon: Double,
        accuracyM: Float,
        poisByCategory: List<Pair<String, List<POIResult>>>,
    ): NearbyResult {
        val all = poisByCategory.flatMap { (categoryLabel, pois) ->
            pois.map { poi -> toPlace(fixLat, fixLon, categoryLabel, poi) }
        }.sortedBy { it.distanceM }

        val deduped = mutableListOf<NearbyPlace>()
        for (p in all) {
            val dup = deduped.any { q ->
                q.name == p.name &&
                    distanceBetween(q.lat, q.lon, p.lat, p.lon) <= NearbyPolicy.DEDUP_DISTANCE_M
            }
            if (!dup) deduped += p
        }

        val base = NearbyPolicy.baseRadius(accuracyM)
        val radius = NearbyPolicy.chooseRadius(base, deduped.map { it.distanceM })
        val shown = deduped.filter { it.distanceM <= radius }.take(NearbyPolicy.MAX_SHOWN)
        return NearbyResult(places = shown, radiusM = radius, expanded = radius > base)
    }

    private fun toPlace(fixLat: Double, fixLon: Double, categoryLabel: String, poi: POIResult): NearbyPlace {
        // 장소 중심 좌표(noorLat/Lon)를 쓴다. TMap 의 front 좌표는 **차량 진입 지점**이라
        // 큰 건물이면 수십 m 떨어진 다른 도로 위에 찍힌다(예: SKT타워 중심↔front 약 50m).
        // 보행자 기준 "그곳"과 더 어긋나고, 거리 필터에서 가까운 곳을 떨어뜨리는 원인이 된다.
        val lat = poi.lat
        val lon = poi.lon
        val category = poi.category?.takeIf { it.isNotBlank() } ?: categoryLabel
        return NearbyPlace(
            name = poi.name.trim(),
            category = category,
            lat = lat,
            lon = lon,
            distanceM = distanceBetween(fixLat, fixLon, lat, lon),
        )
    }
}
