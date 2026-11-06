package com.example.safewalknav.navigation.nearby

import com.example.safewalknav.navigation.geo.distanceBetween
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** 위경도 점 하나. */
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * 고정 점 데이터(서울시 보행등 위치 등)를 격자로 보관해 "내 주변 N m" 를 빠르게 찾는다.
 *
 * iOS `PedestrianSignalIndex.swift` 의 격자 로직을 commonMain 으로 옮긴 것 — 두 플랫폼이 같은
 * 규칙을 쓰도록(iOS 는 이후 이걸로 갈아탈 수 있다). 안전 규칙도 그대로 가져왔다:
 *
 *  ⚠️ **"없다"고 단정하지 않는다.** 서울 사각형 안에도 데이터가 0개인 도시(부천·과천 등)가 통째로
 *     들어 있다. 그래서 [isInsideCoverage] — "반경 500m 안에 점이 하나라도 있는가" — 를 먼저 본다.
 *     그 밖이면 '신호등이 없는 곳'이 아니라 '데이터가 닿지 않는 곳'이다.
 *  ⚠️ 이웃 셀 탐색 범위는 반경에서 유도한다. ±1 로 고정하면 반경이 셀보다 클 때 조용히 과소 계수하고,
 *     과소 계수는 "신호등 없음" 쪽으로 틀리는 가장 위험한 방향이다.
 */
class PointGridIndex private constructor(
    private val grid: Map<Long, List<GeoPoint>>,
    /** 중복 제거 후 점 개수 */
    val size: Int,
) {

    /** 반경 안의 점을 거리순으로. */
    fun nearest(lat: Double, lon: Double, radiusM: Double, limit: Int = Int.MAX_VALUE): List<Pair<GeoPoint, Float>> {
        val found = mutableListOf<Pair<GeoPoint, Float>>()
        forEachWithin(lat, lon, radiusM) { p, d ->
            found += p to d
            true
        }
        return found.sortedBy { it.second }.take(limit)
    }

    fun countWithin(lat: Double, lon: Double, radiusM: Double): Int {
        var n = 0
        forEachWithin(lat, lon, radiusM) { _, _ -> n++; true }
        return n
    }

    /**
     * 이 지점에 데이터가 실제로 닿는가.
     * 빠른 기각용 사각형([SEOUL_BBOX]) 밖이면 확실히 없고, 안이면 반경 [COVERAGE_RADIUS_M] 안에
     * 점이 하나라도 있어야 "닿는다"로 본다.
     */
    fun isInsideCoverage(lat: Double, lon: Double): Boolean {
        val b = SEOUL_BBOX
        if (lat < b[0] || lat > b[1] || lon < b[2] || lon > b[3]) return false
        var any = false
        forEachWithin(lat, lon, COVERAGE_RADIUS_M) { _, _ -> any = true; false }
        return any
    }

    /** body 가 false 를 돌려주면 즉시 멈춘다. */
    private fun forEachWithin(lat: Double, lon: Double, radiusM: Double, body: (GeoPoint, Float) -> Boolean) {
        val span = max(1, ceil(radiusM / MIN_CELL_M).toInt())
        val latIdx = floor(lat / CELL_DEG).toLong()
        val lonIdx = floor(lon / CELL_DEG).toLong()
        for (dLat in -span..span) {
            for (dLon in -span..span) {
                val pts = grid[key(latIdx + dLat, lonIdx + dLon)] ?: continue
                for (p in pts) {
                    val d = distanceBetween(lat, lon, p.lat, p.lon)
                    if (d <= radiusM && !body(p, d)) return
                }
            }
        }
    }

    companion object {
        /** 위도 0.001° ≈ 111m, 경도 0.001° ≈ 88m(위도 37.5°). */
        private const val CELL_DEG = 0.001

        /** 셀 한 변의 최솟값(경도 방향) — 이웃 셀 범위를 반경에서 유도할 때. */
        private const val MIN_CELL_M = 85.0

        /** 데이터 실제 범위(위 37.434~37.692, 경 126.769~127.183)를 완전히 포함하는 사각형. */
        val SEOUL_BBOX = doubleArrayOf(37.40, 37.70, 126.70, 127.20)

        /**
         * 서울 평균 보행등 밀도(약 42개/km²)면 500m 반경 기대값이 30개를 넘는다.
         * ⚠️ bbox 여백(가장 좁은 북쪽 약 880m)보다 작아야 한다 — 크면 bbox 밖 점을 못 세서 과소 계수.
         */
        const val COVERAGE_RADIUS_M = 500.0

        /** 같은 위치로 보는 거리 — CSV 에 같은 좌표가 두 번 들어간 행이 있다(약 500행). */
        private const val DEDUP_M = 1.0

        private fun key(latIdx: Long, lonIdx: Long): Long = latIdx * 1_000_000L + lonIdx

        /**
         * "lat,lon" 2열 CSV(헤더 1줄). 파싱 못 하는 행은 건너뛴다.
         * 같은 셀 안에서 [DEDUP_M] 이내로 겹치는 점은 하나로 친다.
         */
        fun fromCsv(text: String): PointGridIndex {
            val grid = HashMap<Long, MutableList<GeoPoint>>()
            var count = 0
            text.lineSequence().drop(1).forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEach
                val comma = line.indexOf(',')
                if (comma <= 0) return@forEach
                val lat = line.substring(0, comma).trim().toDoubleOrNull() ?: return@forEach
                val lon = line.substring(comma + 1).trim().toDoubleOrNull() ?: return@forEach
                val k = key(floor(lat / CELL_DEG).toLong(), floor(lon / CELL_DEG).toLong())
                val cell = grid.getOrPut(k) { mutableListOf() }
                if (cell.any { distanceBetween(lat, lon, it.lat, it.lon) <= DEDUP_M }) return@forEach
                cell += GeoPoint(lat, lon)
                count++
            }
            return PointGridIndex(grid, count)
        }
    }
}
