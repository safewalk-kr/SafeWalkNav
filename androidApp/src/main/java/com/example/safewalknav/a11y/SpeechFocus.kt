package com.example.safewalknav.a11y

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * 앱이 말하는 동안 다른 소리(음악·유튜브·다른 길안내 앱)를 **줄여 달라고** 시스템에 요청한다(오디오 포커스).
 *
 * 왜: 예전에는 포커스를 전혀 요청하지 않아서, 블루투스로 음악을 듣는 중이면 음악이 그대로 크게 나오고
 * 그 위에 "빨간불"이 섞여 묻혔다. 시각장애인에게 이 안내는 안전 정보다.
 *
 * GAIN_TRANSIENT_MAY_DUCK: 다른 앱을 멈추게 하지 않고 잠깐 작게만 한다. 말이 끝나면 [release] 로 돌려준다.
 * 요청이 거절돼도(통화 중 등) 말은 그대로 한다 — 안내를 못 하는 것보다 겹치는 게 낫다.
 *
 * 포커스를 뺏기면(다른 길안내 앱이 말하기 시작 등) [held] 를 내린다. 예전에는 알림을 받지 않아
 * 계속 "쥐고 있다"고 믿고 다시 요청하지 않았다 — 그 뒤로는 음악이 줄지 않은 채 안내가 묻혔다.
 */
class SpeechFocus(context: Context, attributes: AudioAttributes) {

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * 뺏김의 종류(영구·잠깐·작게)와 상관없이 "이제 쥐고 있지 않다"로 본다 — 다음 말에서 다시 요청해야
     * 우리가 다시 맨 위가 되어 다른 소리가 줄어든다. 메인 스레드에서 받는다([held] 를 건드리므로).
     */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> held = false
        }
    }

    private val request: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
            .build()

    // 리스너는 메인 스레드, acquire/release 는 호출한 스레드 — 서로 최신 값을 보게.
    @Volatile
    private var held = false

    fun acquire() {
        if (held) return
        val am = audioManager ?: return
        held = try {
            am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * [held] 와 상관없이 항상 반납한다. 잠깐 뺏긴(LOSS_TRANSIENT) 동안에도 우리 요청은 시스템 포커스 목록에
     * 남아 있어서, 그대로 두면 상대 앱이 끝난 뒤 포커스가 우리에게 돌아와 음악이 계속 작게 남는다.
     * 쥐고 있지 않을 때 반납해도 아무 일도 일어나지 않는다.
     */
    fun release() {
        held = false
        try {
            audioManager?.abandonAudioFocusRequest(request)
        } catch (_: Exception) {
        }
    }
}
