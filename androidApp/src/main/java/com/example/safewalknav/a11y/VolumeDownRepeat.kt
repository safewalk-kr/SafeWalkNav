package com.example.safewalknav.a11y

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/**
 * 일반 화면(홈·안전 고지·주변 장소·음향신호기)의 **볼륨 아래 키 = 다시 듣기.**
 *
 * 왜: 신호등 카메라에서 "볼륨 아래 짧게 = 다시 듣기"를 배운 사람이 다른 화면에서 같은 키를 누르면,
 * 예전에는 실제로 볼륨이 내려갔다. 안내를 놓쳐서 누른 건데 소리가 더 작아지고, 몇 번 누르면 무음이 된다.
 * TalkBack 없이 쓰는 사람에게는 화면을 보지 않고 다시 들을 유일한 방법이기도 하다.
 *
 *  - [LONG_PRESS_MS] 보다 짧게 누름 → 손을 뗄 때 [onRepeat] (그 화면의 마지막 안내를 다시)
 *  - [LONG_PRESS_MS] 이상 누르고 있음 → 원래대로 볼륨을 내린다. 단 **1칸 밑으로는 내리지 않는다** — 앱 안에서
 *    실수로 완전히 꺼 버리면 그 사실조차 소리로 알릴 수 없다.
 *  - TalkBack 켜짐 → 손대지 않는다(TalkBack 사용자는 상태 줄로 다시 듣고, 볼륨 키는 TalkBack 볼륨용).
 *
 * 짧게/길게는 **누른 시간**(eventTime - downTime)으로 가른다. 예전에는 첫 키 반복(약 0.5초)이 오면 곧바로
 * "길게"로 쳐서, 0.5~0.7초쯤 천천히 누르는 어르신은 아무 일도 안 일어나고(다시 듣기도, 볼륨도 아님),
 * 조금 더 누르면 말없이 볼륨만 내려갔다.
 *
 * 우리가 DOWN 을 받지 못한 누름의 UP 은 무시한다. 전화벨이 울릴 때 볼륨 키를 누르면 시스템이 DOWN 으로
 * 벨소리를 끄고 UP 만 앱에 넘기는데, 이걸 다시 듣기로 치면 벨소리를 끄려던 순간 안내가 그 위로 나온다.
 *
 * 볼륨 **올리기** 키는 건드리지 않는다 — 소리를 키울 길은 언제나 열어 둔다.
 */
class VolumeDownRepeat(
    private val activity: Activity,
    private val onRepeat: () -> Unit,
) {
    /** 우리가 받은 마지막 DOWN(반복 0)의 downTime. UP 의 downTime 과 같아야 "우리 누름"이다. */
    private var pressDownTime = NO_PRESS

    /** 길게 누름으로 넘어간 첫 반복의 번호 — 볼륨을 내리는 박자의 기준. [NOT_HELD] 면 아직 짧은 누름. */
    private var holdStartRepeat = NOT_HELD

    /** Activity.onKeyDown 에서 먼저 부른다. true 면 처리했으니 그대로 true 를 돌려준다. */
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!handles(keyCode)) return false
        if (event.repeatCount == 0) {
            pressDownTime = event.downTime
            holdStartRepeat = NOT_HELD
            return true
        }
        // 기준 시간 전의 반복은 아직 짧은 누름이다 — 천천히 눌러도 손을 떼면 다시 듣기가 된다.
        if (pressedFor(event) < LONG_PRESS_MS) return true
        // 넘어가는 순간 바로 한 칸(눌렀는데 반응이 없으면 더 세게 오래 누른다), 그 뒤로는 일정한 박자로.
        if (holdStartRepeat == NOT_HELD) holdStartRepeat = event.repeatCount
        if ((event.repeatCount - holdStartRepeat) % HOLD_REPEATS_PER_STEP == 0) lowerVolumeKeepingAudible()
        return true
    }

    /** Activity.onKeyUp 에서 먼저 부른다. */
    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!handles(keyCode)) return false
        val ours = pressDownTime != NO_PRESS && event.downTime == pressDownTime
        val wasHeld = holdStartRepeat != NOT_HELD
        pressDownTime = NO_PRESS
        holdStartRepeat = NOT_HELD
        // DOWN 을 못 본 UP(시스템이 DOWN 으로 벨소리를 끔 등), 취소된 누름(누르는 도중 전화가 옴),
        // 이미 볼륨을 내린 길게 누름은 다시 듣기로 치지 않는다.
        if (!ours || event.isCanceled || wasHeld) return true
        if (pressedFor(event) < LONG_PRESS_MS) {
            onRepeat()
        } else {
            // 기준 시간을 넘겼는데 반복이 한 번도 안 왔다 — '길게 누르기 지연'을 길게 둔 기기는 첫 반복이
            // 1.5초에야 온다. 길게 누른 것이니 볼륨을 한 칸 내린다(아무 반응 없이 끝나지 않게).
            lowerVolumeKeepingAudible()
        }
        return true
    }

    private fun handles(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && !Speaker.isScreenReaderOn(activity)

    private fun pressedFor(event: KeyEvent): Long = event.eventTime - event.downTime

    private fun lowerVolumeKeepingAudible() {
        val am = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val stream = AudioRouting.speechStream(activity)
        try {
            if (am.getStreamVolume(stream) <= 1) return
            am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        } catch (_: Exception) {
        }
    }

    companion object {
        /**
         * 이보다 짧으면 짧게 누름(다시 듣기), 이 이상이면 길게 누름(볼륨 내리기).
         * 1초: 천천히 누르는 어르신의 "짧게"도 넉넉히 담고, 일부러 누르고 있는 것과는 헷갈리지 않는 길이.
         * 신호등 카메라(MainActivity)도 이 값을 그대로 써서 화면마다 손버릇이 같게 한다.
         */
        const val LONG_PRESS_MS = 1_000L
    }
}

/** 키를 누르고 있으면 반복 이벤트가 약 50ms 마다 온다 — 4번에 한 칸(약 0.2초). */
private const val HOLD_REPEATS_PER_STEP = 4

private const val NOT_HELD = -1
private const val NO_PRESS = Long.MIN_VALUE
