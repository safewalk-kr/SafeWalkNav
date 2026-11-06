package com.example.safewalknav.a11y

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 이 앱의 설정 화면(권한 항목이 있는 곳)을 연다.
 *
 * 권한을 "다시 묻지 않음"으로 거부하면(안드로이드 11+ 는 두 번 거부하면 자동으로 그렇게 된다)
 * 시스템이 권한 창을 더는 띄우지 않는다. 그때 앱이 같은 안내만 반복하면 사용자는 빠져나갈 길이 없다 —
 * 설정 화면으로 직접 보내야 한다.
 */
object AppSettings {
    fun open(activity: Activity): Boolean = try {
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }
}
