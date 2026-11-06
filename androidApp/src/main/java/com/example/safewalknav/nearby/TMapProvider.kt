package com.example.safewalknav.nearby

import android.os.SystemClock
import com.example.safewalknav.BuildConfig
import com.example.safewalknav.navigation.geo.distanceBetween
import com.example.safewalknav.navigation.tmap.POIResult
import com.example.safewalknav.navigation.tmap.TMapApiClient

/**
 * 앱 전체에서 TMap 클라이언트 하나를 같이 쓴다.
 * 예전에는 화면을 열 때마다 새 HTTP 클라이언트를 만들고 닫지도 않아서, 연결 재사용 없이
 * 매번 TLS 핸드셰이크부터 다시 했다(주변 장소 검색은 한 번에 11건을 동시에 보낸다).
 */
object TMapProvider {
    val client: TMapApiClient by lazy {
        TMapApiClient(BuildConfig.TMAP_APP_KEY).apply {
            // 릴리스에선 진단 로그를 끈다 — 검색 한 번에 70줄 넘게 찍힌다.
            verboseLogging = BuildConfig.DEBUG
        }
    }
}

/**
 * 직전 주변 검색 결과를 잠깐 기억한다.
 *
 * 같은 자리에서 [다시 찾기]를 연달아 누르거나 신호등 카메라에 다녀오면 매번 10건씩
 * 유료 API 를 다시 부른다(앱 전체가 키 하나를 같이 쓰므로 한도에 그대로 쌓인다).
 * 25m 안·2분 안이면 장소 목록은 같다 — 목록만 재사용하고, 거리·방향은 새 위치로 다시 계산한다.
 * 실패가 하나라도 섞인 결과는 기억하지 않는다(다음에 다시 시도하도록).
 *
 * ⚠️ 주소(건물명)는 기억하지 않는다 — 1건이라 싸고, 20m 옆 건물로 옮겼는데 "A빌딩 근처입니다"(이전 건물)라고
 *    하면 위치 설명이 틀린다. 장소 목록은 25m 정도 움직여도 거의 같지만 "지금 어느 건물 앞"은 다르다.
 */
object NearbySearchCache {
    private const val MAX_AGE_MS = 120_000L
    private const val MAX_MOVE_M = 25f

    private var savedAt = 0L
    private var lat = 0.0
    private var lon = 0.0
    private var groups: List<Pair<String, List<POIResult>?>>? = null

    fun get(lat: Double, lon: Double): List<Pair<String, List<POIResult>?>>? {
        val g = groups ?: return null
        if (SystemClock.elapsedRealtime() - savedAt > MAX_AGE_MS) return null
        if (distanceBetween(this.lat, this.lon, lat, lon) > MAX_MOVE_M) return null
        return g
    }

    fun put(lat: Double, lon: Double, groups: List<Pair<String, List<POIResult>?>>) {
        if (groups.any { it.second == null }) return
        this.lat = lat
        this.lon = lon
        this.groups = groups
        savedAt = SystemClock.elapsedRealtime()
    }
}
