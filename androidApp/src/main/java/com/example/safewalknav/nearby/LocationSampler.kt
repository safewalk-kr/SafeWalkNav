package com.example.safewalknav.nearby

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.location.LocationManagerCompat
import com.example.safewalknav.navigation.nearby.LocationFusion
import com.example.safewalknav.navigation.nearby.LocationSample
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationSettingsResponse
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 몇 초 동안 위치 픽스를 모은다 (GPS 오차 보정의 수집 단계).
 *
 * - 고정밀(PRIORITY_HIGH_ACCURACY)로 1초마다 받는다.
 * - 픽스 4개 이상·3초 이상 모였고 그중 오차 8m 이하가 2개면 일찍 끝낸다 ([LocationFusion.isGoodEnough]).
 *   (1초 간격 2개만 보고 끝내던 예전 기준은 도심 멀티패스에서 "오차 5m"를 그대로 믿었다)
 * - 오래된 캐시 픽스(10초 넘은 것)는 버린다 — Fused 는 첫 결과로 예전 위치를 줄 수 있는데,
 *   그건 "지금 여기"가 아니다.
 *
 * 합치는 일(가중 평균·튄 값 제거)은 shared 의 [LocationFusion] 이 한다.
 * 콜백은 메인 루퍼에서 오고, 호출자도 메인(lifecycleScope)이라 리스트 접근이 한 스레드다.
 */
class LocationSampler(context: Context) {

    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    /** 휴대폰의 위치 기능(설정)이 켜져 있는가. 꺼져 있으면 아무리 기다려도 픽스가 안 온다. */
    fun isLocationEnabled(): Boolean {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    /**
     * 위치 기능을 켜는 시스템 창(Google 위치 설정 창)을 띄울 수 있는지 묻는다.
     * 실패가 [com.google.android.gms.common.api.ResolvableApiException] 이면 그 `resolution` 으로 창을 띄워
     * 한 번 눌러 켤 수 있다 — 빠른 설정의 위치 아이콘을 화면을 보지 않고 찾기는 어렵다.
     * 그 밖의 실패(Play 서비스 없음 등)는 창을 띄울 수 없다는 뜻이다.
     *
     * [sample] 이 실제로 쓰는 것과 같은 고정밀 요청으로 묻는다 — "그 요청이 채워지는가"가 기준이다.
     */
    fun checkLocationSettings(): Task<LocationSettingsResponse> =
        LocationServices.getSettingsClient(appContext).checkLocationSettings(
            LocationSettingsRequest.Builder()
                .addLocationRequest(highAccuracyRequest())
                // 이 화면은 위치 없이는 할 수 있는 일이 없다 — "꼭 필요함"으로 알려 창이 매번 뜨게 한다.
                // (안내의 "두 버튼 중 오른쪽"은 이 설정의 창 기준이다. 실기에서 버튼 수·순서를 확인할 것)
                .setAlwaysShow(true)
                .build()
        )

    private fun highAccuracyRequest(): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()

    /**
     * @param maxDurationMs 최대 대기 시간. 조기 종료 조건을 못 채우면 이때까지 모은 걸 돌려준다.
     * @return 모은 픽스 (비어 있을 수 있음). 권한 확인은 호출자 책임.
     */
    @SuppressLint("MissingPermission")
    suspend fun sample(maxDurationMs: Long = DEFAULT_MAX_DURATION_MS): List<LocationSample> {
        val samples = mutableListOf<LocationSample>()
        val enough = CompletableDeferred<Unit>()
        // 단조 시계로 비교한다. loc.time 은 GPS 시각일 수 있어, 휴대폰 시계가 10초 넘게 틀린 기기에선
        // 벽시계 비교가 모든 픽스를 "오래됨"으로 버린다 → 영원히 "위치를 찾지 못함".
        val startedAtNanos = SystemClock.elapsedRealtimeNanos()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    if (!loc.hasAccuracy()) continue
                    // 캐시된 옛 위치 거르기
                    if (startedAtNanos - loc.elapsedRealtimeNanos > STALE_FIX_NANOS) continue
                    // 시각도 단조 시계로 — loc.time 은 제공자마다 시계가 달라(GPS 시각 vs 휴대폰 시계)
                    // 섞이면 픽스 사이 시간 폭(조기 종료 기준 3초)이 엉뚱하게 계산된다.
                    // 시작 전에 생긴(캐시된) 픽스는 시작 시각으로 친다 — 안 그러면 몇 초 전 캐시 하나가
                    // "3초 이상 모았다" 조건을 채워 줘, 새 픽스 2초어치만으로 일찍 끝났다.
                    val nanos = maxOf(loc.elapsedRealtimeNanos, startedAtNanos)
                    samples += LocationSample(
                        loc.latitude, loc.longitude, loc.accuracy, nanos / 1_000_000L
                    )
                }
                if (!enough.isCompleted && LocationFusion.isGoodEnough(samples)) enough.complete(Unit)
            }
        }

        try {
            client.requestLocationUpdates(highAccuracyRequest(), callback, Looper.getMainLooper())
            withTimeoutOrNull(maxDurationMs) { enough.await() }
        } finally {
            client.removeLocationUpdates(callback)
        }
        return samples.toList()
    }

    companion object {
        const val DEFAULT_MAX_DURATION_MS = 8_000L
        private const val INTERVAL_MS = 1_000L
        private const val MIN_INTERVAL_MS = 500L
        private const val STALE_FIX_NANOS = 10_000_000_000L   // 10초
    }
}
