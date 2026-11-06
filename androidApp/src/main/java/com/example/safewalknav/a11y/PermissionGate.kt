package com.example.safewalknav.a11y

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.core.app.ActivityCompat

/**
 * "권한이 영구 거부(다시 묻지 않음) 상태인가"를 판단한다.
 *
 * 흔한 판정 `거부 && !shouldShowRequestPermissionRationale` 은 **틀릴 수 있다.**
 * 처음 뜬 권한 창을 뒤로 가기·바깥 탭으로 그냥 닫아도 같은 값(거부, rationale=false)이 온다.
 * TalkBack 뒤로 제스처를 실수로 쓰면 바로 이 경우다. 그걸 영구 거부로 오판하면, 간단한 권한 창 대신
 * 훨씬 다루기 어려운 설정 화면으로 보내게 된다.
 *
 * 그래서 두 가지 근거가 있을 때만 영구 거부로 본다.
 *  1. 이 권한에서 rationale=true 를 본 적이 있다 — 한 번 거부한 뒤 다시 거부해 시스템이 막은 경우(안드로이드 11+).
 *  2. 결과가 사람이 누를 수 없을 만큼 빨리 왔다 — 시스템이 창을 아예 띄우지 않고 바로 거부를 돌려준 경우.
 */
object PermissionGate {

    private const val PREFS = "safewalk_permission_memory"

    /** 이보다 빨리 거부가 돌아오면 창이 뜨지 않은 것으로 본다. 사람이 읽고 누르는 데는 훨씬 오래 걸린다. */
    private const val INSTANT_DENIAL_MS = 500L

    /** 권한 요청 직전에 부르고, 돌려받은 값을 [isBlocked] 에 넘긴다. */
    fun markRequested(): Long = SystemClock.elapsedRealtime()

    /**
     * 거부 결과를 받은 직후에 부른다.
     * @param requestedAtMs [markRequested] 가 돌려준 값. 모르면 0 (빠른 거부 판정을 건너뜀).
     */
    fun isBlocked(activity: Activity, permission: String, requestedAtMs: Long): Boolean {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
            prefs.edit().putBoolean(permission, true).apply()
            return false
        }
        val answeredInstantly = requestedAtMs > 0L &&
            SystemClock.elapsedRealtime() - requestedAtMs < INSTANT_DENIAL_MS
        return prefs.getBoolean(permission, false) || answeredInstantly
    }
}
