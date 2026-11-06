package com.example.safewalknav.a11y

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import java.util.Locale

/**
 * 홈·주변 장소·음향신호기 화면의 음성 안내 창구.
 *
 * ── TalkBack 과 겹치지 않게 ──
 * 이 화면들은 버튼이 있는 일반 화면이라 TalkBack 사용자는 TalkBack 으로 조작한다.
 * 그 위에 앱이 TTS 로 또 말하면 두 목소리가 겹친다(신호등 카메라 화면과 다른 점 —
 * 거기는 누를 것이 없어 TalkBack 을 통째로 침묵시켰다).
 *
 *  - TalkBack 켜짐 → TTS 를 쓰지 않고, 상태 글씨([statusView], accessibilityLiveRegion=polite)를
 *    바꾼다. TalkBack 이 자기 목소리·속도로 읽는다.
 *  - TalkBack 꺼짐 → TTS 로 말한다(접근성 용도 오디오).
 *    ⚠️ 접근성 볼륨이 따로 노는 건 TalkBack 같은 접근성 서비스가 켜져 있을 때뿐이다. 꺼져 있으면
 *       미디어 볼륨을 따라가므로, 미디어를 0 으로 둔 사람에게는 안 들린다. 그래서 첫 발화 때 볼륨이
 *       0 이면 진동(길게 세 번)으로 알린다 — 소리로는 알릴 방법이 없다.
 *
 * 상태 글씨는 두 경우 모두 바꾼다 — 저시력자·보호자가 눈으로도 본다.
 */
class Speaker(
    context: Context,
    private val statusView: TextView?,
) : TextToSpeech.OnInitListener {

    // ⚠️ 상태 필드는 반드시 tts 보다 **위에** 선언한다.
    //    TextToSpeech 생성자는 엔진 바인딩 실패 시 onInit(ERROR) 를 생성자 안에서 동기로 부른다.
    //    필드가 아래에 있으면 onInit 이 세운 unavailable=true 를 뒤이은 초기화(=false)가 덮어쓴다.
    private val appContext = context.applicationContext
    private var ready = false
    private var unavailable = false

    /** 엔진 준비 전에 들어온 말. 준비되면 마지막 것만 말한다(오래된 안내를 몰아서 읽지 않게). */
    private var pending: String? = null

    /** 같은 문장을 다시 넣어도 라이브 리전이 읽도록 번갈아 붙이는 보이지 않는 문자. */
    private var toggle = false

    /** 창이 포커스를 얻기 전에 들어와 TalkBack 이 못 읽었을 문장 — 포커스를 얻으면 다시 건다. */
    private var unannounced: String? = null
    private var waitingForWindowFocus = false

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** 마지막으로 말한(또는 상태 줄에 쓴) 문장 — 볼륨 아래 키 "다시 듣기"용. */
    private var lastSpoken: String? = null

    /** 말하는 동안 음악·다른 앱 소리를 줄인다. 말이 끝나면 돌려준다. */
    private val focus = SpeechFocus(appContext, attributes)
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 아직 말하는 중이면(일부 엔진은 끝난 직후에도 잠깐 true) 조금 뒤 다시 본다 — 한 번만 보면 포커스가 남는다. */
    private val releaseFocusIfIdle: Runnable = object : Runnable {
        override fun run() {
            if (ready && tts.isSpeaking) mainHandler.postDelayed(this, FOCUS_RELEASE_DELAY_MS) else focus.release()
        }
    }

    private val tts: TextToSpeech = TextToSpeech(appContext, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            unavailable = true
            return
        }
        val lang = tts.setLanguage(Locale.KOREAN)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            unavailable = true
            return
        }
        tts.setAudioAttributes(attributes)
        // 말이 다 끝나면(다음 말이 바로 이어지지 않으면) 줄였던 다른 소리를 돌려준다.
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = scheduleFocusRelease()
            @Suppress("OVERRIDE_DEPRECATION")   // 추상 메서드라 구현은 해야 한다
            override fun onError(utteranceId: String?) = scheduleFocusRelease()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = scheduleFocusRelease()
        })
        ready = true
        pending?.let { speakTts(it, interrupt = true) }
        pending = null
    }

    private fun scheduleFocusRelease() {
        mainHandler.removeCallbacks(releaseFocusIfIdle)
        mainHandler.postDelayed(releaseFocusIfIdle, FOCUS_RELEASE_DELAY_MS)
    }

    /** TalkBack(터치 탐색)이 켜져 있는가. */
    fun isScreenReaderOn(): Boolean = isScreenReaderOn(appContext)

    companion object {
        /** TalkBack(터치 탐색)이 켜져 있는가. 다른 화면(신호등 카메라 등)도 같은 기준을 쓴다. */
        fun isScreenReaderOn(context: Context): Boolean {
            val am = context.applicationContext
                .getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            return am?.isEnabled == true && am.isTouchExplorationEnabled
        }

        /** 말이 끝나고 이만큼 다음 말이 없으면 다른 앱 소리를 돌려준다(문장 사이마다 음악이 출렁이지 않게). */
        private const val FOCUS_RELEASE_DELAY_MS = 400L
    }

    /**
     * @param interrupt true 면 말하던 것을 끊고 바로 말한다(사용자가 방금 누른 것에 대한 응답).
     */
    fun speak(text: String, interrupt: Boolean = true) {
        lastSpoken = text
        statusView?.let { setLiveText(it, text) }
        if (isScreenReaderOn()) return   // TalkBack 이 라이브 리전으로 읽는다
        warnIfMuted()
        if (unavailable) return
        if (!ready) {
            pending = text
            return
        }
        speakTts(text, interrupt)
    }

    /**
     * 말하던 것과 **엔진 준비를 기다리던 말**을 모두 버린다.
     * 예전에는 기다리던 말(pending)이 남아, 콜드 스타트에 홈 안내가 준비되기 전에 버튼을 누르면
     * 그 홈 안내가 다음 화면(주변 장소·신호등 카메라) 위에서 뒤늦게 나왔다.
     */
    fun stop() {
        pending = null
        if (ready) tts.stop()
        mainHandler.removeCallbacks(releaseFocusIfIdle)
        focus.release()
    }

    /**
     * 마지막 안내를 다시 말한다(볼륨 아래 키). 다시 들려줄 것이 없으면 false.
     * 안내가 "지금 방향" 처럼 그 순간에만 맞는 화면은 이걸 쓰지 말고 새로 만들어 말할 것.
     */
    fun repeatLast(): Boolean {
        val text = lastSpoken ?: return false
        speak(text, interrupt = true)
        return true
    }

    /**
     * 앱 음성이 말하는 중이거나, 엔진 준비를 기다리는 말이 있는가.
     * 화면을 떠나며 [stop] 하기 **전에** 물어서, 안내가 중간에 끊겼는지 알아낸다
     * (돌아왔을 때 다시 들려줄지 판단 — 끊긴 채 조용하면 시각장애인은 어디인지 모른다).
     * TalkBack 이 켜져 있으면 앱 음성을 쓰지 않으므로 늘 false.
     */
    fun isBusy(): Boolean = pending != null || (ready && tts.isSpeaking)

    fun shutdown() {
        pending = null
        mainHandler.removeCallbacks(releaseFocusIfIdle)
        focus.release()
        tts.stop()
        tts.shutdown()
    }

    private var mutedWarned = false

    /** 볼륨이 0 이면 한 번 진동으로 알린다 (신호등 카메라 화면과 같은 패턴: 길게 세 번). */
    private fun warnIfMuted() {
        if (mutedWarned) return
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val level = try {
            am.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY)
        } catch (_: Exception) {
            return
        }
        if (level > 0) return
        mutedWarned = true
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        try {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1),
                attributes,
            )
        } catch (_: Exception) {
        }
    }

    /**
     * 라이브 리전 글씨를 바꾼다. 글씨는 언제나 즉시 바꾸되(눈으로 보는 사람용),
     * 창이 아직 포커스를 못 얻었으면 TalkBack 이 그 변화를 놓치므로 포커스를 얻는 순간 한 번 더 건드린다.
     * (화면 진입 직후 "확인하고 있습니다", 권한 대화상자 직후 "권한이 필요합니다" 가 여기 해당)
     */
    private fun setLiveText(v: TextView, text: String) {
        toggle = !toggle
        v.text = if (toggle) text else "$text​"
        if (v.isAttachedToWindow && v.hasWindowFocus()) {
            unannounced = null
            return
        }
        unannounced = text
        if (waitingForWindowFocus) return
        waitingForWindowFocus = true
        WindowFocus.runWhenFocused(v) {
            waitingForWindowFocus = false
            val t = unannounced ?: return@runWhenFocused
            unannounced = null
            toggle = !toggle
            v.text = if (toggle) t else "$t​"
        }
    }

    private fun speakTts(text: String, interrupt: Boolean) {
        mainHandler.removeCallbacks(releaseFocusIfIdle)
        focus.acquire()
        var mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        // 블루투스 이어폰은 첫 음절이 잘린다 — 짧은 무음으로 먼저 깨운다(AudioRouting 주석).
        if (AudioRouting.isBluetoothOutput(appContext)) {
            tts.playSilentUtterance(AudioRouting.BLUETOOTH_WAKE_MS, mode, "safewalk-wake-${System.nanoTime()}")
            mode = TextToSpeech.QUEUE_ADD
        }
        // 엔진이 거절하면 완료 콜백이 오지 않는다 — 포커스를 쥔 채 남지 않게 반납을 예약한다.
        if (tts.speak(text, mode, null, "safewalk-${System.nanoTime()}") == TextToSpeech.ERROR) {
            scheduleFocusRelease()
        }
    }
}
