package com.example.safewalknav.a11y

import android.view.View
import android.view.ViewTreeObserver

/**
 * "창이 실제로 포커스를 얻은 뒤"에 할 일을 미룬다.
 *
 * 왜 필요한가 — TalkBack 은 **활성 창**의 접근성 이벤트만 읽는다.
 *  - onCreate 에서 바꾼 라이브 리전 글씨: 뷰가 아직 창에 안 붙어 이벤트 자체가 안 나간다.
 *  - 권한 대화상자가 떠 있는 동안 바꾼 글씨: 활성 창이 대화상자라 무시된다.
 *  - onCreate 에서 요청한 접근성 포커스: 창이 뜨면서 TalkBack 이 포커스를 다시 잡아 덮어쓴다.
 * 셋 다 TalkBack 사용자에게는 "아무 말도 안 했다"가 된다. 창이 포커스를 얻는 순간으로 미루면 산다.
 */
object WindowFocus {

    fun runWhenFocused(view: View, action: () -> Unit) {
        if (view.isAttachedToWindow && view.hasWindowFocus()) {
            // 이미 포커스가 있어도 한 프레임 뒤로 — 방금 VISIBLE 로 바꾼 뷰(안전 고지의 차단 화면 등)는
            // 아직 배치 전이라 지금 접근성 포커스를 걸면 TalkBack 이 못 잡는다.
            view.post(action)
            return
        }
        // 붙기 전의 뷰가 돌려주는 ViewTreeObserver 는 임시 객체지만, 창에 붙을 때 리스너가
        // 창의 것으로 옮겨진다(merge). 그래서 지금 등록해도 된다.
        view.viewTreeObserver.addOnWindowFocusChangeListener(
            object : ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (!hasFocus) return
                    view.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                    // 포커스 콜백 안에서 바로 하면 TalkBack 의 창 전환 처리와 순서가 뒤엉킨다 — 한 프레임 뒤로.
                    view.post(action)
                }
            }
        )
    }
}
