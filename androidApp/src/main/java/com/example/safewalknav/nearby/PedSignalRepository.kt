package com.example.safewalknav.nearby

import android.content.Context
import android.util.Log
import com.example.safewalknav.navigation.nearby.PointGridIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 서울시 보행등(보행자 신호등) 위치 — 앱에 번들된 CSV 를 한 번만 읽어 격자 인덱스로 보관한다.
 *
 * 출처: 서울특별시 보행등 위치 (공공누리 1유형), 2026-02-13 갱신본. iOS 와 같은 파일
 *       (iosApp/iosApp/Resources/pedlights_seoul_20260213.csv). 갱신할 때는 두 곳을 같이 바꾼다.
 *
 * 2.5만 행이라 파싱은 수십 ms — 그래도 메인 스레드는 피한다. 실패하면 null 이고,
 * 호출부는 신호등 정보 없이 나머지 기능을 그대로 쓴다(없다고 말하지 않는다).
 */
object PedSignalRepository {

    const val ASSET = "pedlights_seoul_20260213.csv"
    private const val TAG = "PedSignal"

    private val mutex = Mutex()
    private var index: PointGridIndex? = null
    private var failed = false

    suspend fun get(context: Context): PointGridIndex? = mutex.withLock {
        index?.let { return@withLock it }
        if (failed) return@withLock null
        withContext(Dispatchers.IO) {
            try {
                val text = context.applicationContext.assets.open(ASSET).bufferedReader().use { it.readText() }
                PointGridIndex.fromCsv(text).also {
                    index = it
                    Log.d(TAG, "보행등 ${it.size}개 로드")
                }
            } catch (e: Exception) {
                Log.e(TAG, "보행등 데이터 로드 실패", e)
                failed = true
                null
            }
        }
    }
}
