package com.example.safewalknav

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.safewalknav.a11y.AudioRouting
import com.example.safewalknav.a11y.Speaker
import com.example.safewalknav.a11y.VolumeDownRepeat
import com.example.safewalknav.ble.BleScanActivity
import com.example.safewalknav.nearby.NearbyActivity

/**
 * 홈 — 기능 3개 (2026-10 축소안).
 *
 *  위    : 내 위치 · 주변 장소   → [NearbyActivity]
 *  가운데: 신호등 카메라          → [MainActivity] (기존 그대로)
 *  아래  : 음향신호기 (준비 중)   → [BleScanActivity] (규격 확보 전까지는 블루투스 조사.
 *          "켜기"라고 부르지 않는다 — 아직 켤 수 없는데 켜 준다고 약속하면 교차로에서 믿고 기다린다)
 *
 * 내비게이션(경로 안내)은 사용자 요청에 따라 넣지 않는다.
 *
 * 음성: TalkBack 사용자는 TalkBack 이 버튼을 읽는다. TalkBack 이 꺼진 사용자를 위해
 * 앱을 처음 열었을 때 한 번만 버튼 배치를 말해 준다(돌아올 때마다 말하면 잔소리).
 * 다시 듣고 싶으면 볼륨 아래 키를 짧게 누른다(신호등 카메라와 같은 손버릇).
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var speaker: Speaker
    private var skipNextResumeCue = false

    /** 첫 안내(버튼 배치)를 말하는 중인가 / 그게 화면을 떠나며 끊겼는가. */
    private var introPlaying = false
    private var introCutOff = false

    /**
     * "SafeWalk를 닫습니다"를 말하고 닫히기를 기다리는 중.
     * 이 동안 onPause 가 말을 끊지 않고, 뒤로 가기·버튼·다시 듣기는 무시한다.
     */
    private var closing = false
    private val handler = Handler(Looper.getMainLooper())
    private val finishRunnable = Runnable { finish() }

    /** 볼륨 아래 짧게 = 버튼 배치 다시 듣기 (TalkBack 꺼진 사용자용 — 켜져 있으면 VolumeDownRepeat 가 손대지 않는다). */
    private val volumeRepeat = VolumeDownRepeat(this) {
        if (!closing) speakIntro()
    }

    /**
     * TalkBack 이 꺼져 있을 때만 켠다. TalkBack 없이 뒤로 가기를 누르면 화면이 소리 없이 사라져
     * 앱이 닫혔는지, 다른 화면으로 갔는지 알 수 없었다 — 닫는다고 말한 뒤 닫는다.
     * TalkBack 사용자는 TalkBack 이 화면 전환을 읽으므로 시스템 기본 동작(예측 뒤로 가기 포함)을 그대로 둔다.
     */
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (closing) return   // 닫는 중 연타 — 무시
            if (speaker.isScreenReaderOn()) {
                // 앱이 떠 있는 동안 TalkBack 을 켠 경우(볼륨 두 키 단축키) — 기본 동작으로 넘긴다.
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                return
            }
            closing = true
            introPlaying = false
            speaker.speak(CLOSING)
            handler.postDelayed(finishRunnable, CLOSE_DELAY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        title = "SafeWalk 홈"

        // 볼륨 올리기가 벨소리가 아니라 안내 음성 볼륨을 움직이게.
        AudioRouting.bindVolumeKeys(this)
        speaker = Speaker(this, statusView = null)
        onBackPressedDispatcher.addCallback(this, backCallback)

        findViewById<Button>(R.id.btnNearby).setOnClickListener { open(NearbyActivity::class.java) }
        findViewById<Button>(R.id.btnSignal).setOnClickListener { open(MainActivity::class.java) }
        findViewById<Button>(R.id.btnAps).setOnClickListener { open(BleScanActivity::class.java) }

        // (아이콘 바로가기는 SafetyNoticeActivity 가 홈+목적 화면을 한 번에 쌓는다 — 홈은
        //  사용자가 뒤로 가기를 했을 때 여기서 처음 만들어지고, 그때 아래 첫 안내가 나간다)

        // 프로세스당 한 번 (회전·재생성으로 다시 말하지 않게 savedInstanceState 도 본다)
        if (savedInstanceState == null && !introSpoken) {
            introSpoken = true
            speakIntro()
            skipNextResumeCue = true   // 바로 이어지는 onResume 에서 "홈입니다"를 겹쳐 말하지 않게
        }
    }

    /** 사용자가 고른 화면으로 — 버튼을 고른 것이니 하던 안내는 끊는다(다시 들려줄 필요도 없다). */
    private fun open(target: Class<*>) {
        // 닫는다고 말한 뒤 화면을 열면, 곧 홈이 닫혀 그 화면에서 뒤로 가기가 앱을 끝내 버린다.
        if (closing) return
        introPlaying = false
        speaker.stop()
        startActivity(Intent(this, target))
    }

    private fun speakIntro() {
        introPlaying = true
        speaker.speak(INTRO)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        volumeRepeat.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        volumeRepeat.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)

    /**
     * 다른 화면에서 돌아왔을 때 "홈입니다" + 버튼 배치 (TalkBack 이 꺼진 사용자용).
     * TalkBack 사용자는 TalkBack 이 창 제목("SafeWalk 홈")을 읽으므로 Speaker 가 알아서 침묵한다.
     * 이게 없으면 TalkBack 없이 쓰는 사람은 뒤로 가기 후 어느 화면에 있는지 알 길이 없다.
     */
    override fun onResume() {
        super.onResume()
        // TalkBack 을 켜고 끈 뒤 돌아왔을 수 있다 — 뒤로 가기 가로채기는 TalkBack 이 꺼져 있을 때만.
        backCallback.isEnabled = !speaker.isScreenReaderOn()
        if (closing) return
        if (skipNextResumeCue) {
            skipNextResumeCue = false
            return
        }
        // 첫 안내(버튼 배치)를 듣다가 홈 버튼으로 나갔다면, 돌아왔을 때 "홈입니다"가 아니라 배치를 다시 알려 준다.
        if (introCutOff) {
            introCutOff = false
            speakIntro()
            return
        }
        speaker.speak(BACK_HOME)
    }

    /** 홈 버튼·최근 앱으로 나가면 즉시 말을 멈춘다. 단 닫는 인사는 끝까지(닫히면서 onPause 가 먼저 불린다). */
    override fun onPause() {
        super.onPause()
        if (closing) return
        if (introPlaying && speaker.isBusy()) introCutOff = true
        introPlaying = false
        speaker.stop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishRunnable)
        // 닫는 인사가 아직 나오는 중이면 끝난 뒤에 엔진을 놓는다 — 바로 shutdown 하면 문장 끝이 잘린다.
        if (closing) shutdownWhenQuiet(speaker) else speaker.shutdown()
        super.onDestroy()
    }

    companion object {
        /** 아이콘 바로가기(res/xml/shortcuts.xml)가 넘기는 목적지. 값은 그 파일과 맞춰야 한다. */
        const val EXTRA_OPEN = "com.safewalknav.extra.OPEN"
        const val OPEN_SIGNAL = "signal"
        const val OPEN_NEARBY = "nearby"

        private var introSpoken = false

        // 음향신호기는 말로도 늘 "준비 중"을 붙인다 — 버튼 글씨·화면 제목과 같은 이름이어야 하고,
        // "음향신호기"만 들으면 켜 주는 기능인 줄 알고 교차로에서 믿고 기다린다.
        private const val INTRO =
            "SafeWalk입니다. 버튼은 세 개입니다. 맨 위는 내 위치와 주변 장소, 가운데는 신호등 카메라, " +
                "맨 아래는 음향신호기인데, 아직 준비 중입니다. 소리 줄이는 버튼을 짧게 누르면 다시 들을 수 있습니다."
        // 돌아올 때마다 나오므로 다시 듣기 안내는 붙이지 않는다(첫 안내와 다시 듣기 낭독에 이미 들어 있다 — 잔소리).
        private const val BACK_HOME =
            "홈입니다. 위에서부터 내 위치와 주변 장소, 신호등 카메라, 음향신호기 준비 중 버튼입니다."
        private const val CLOSING = "SafeWalk를 닫습니다."

        /** 닫는 인사를 들을 시간. 너무 길면 "안 닫힌다"고 또 누른다. */
        private const val CLOSE_DELAY_MS = 1_200L

        /** 닫는 인사가 끝나기를 기다리는 최대 시간 — TTS 가 멈춰 버려도 엔진을 놓도록. */
        private const val SHUTDOWN_WAIT_MAX_MS = 3_000L
        private const val SHUTDOWN_POLL_MS = 200L

        /**
         * 말이 끝나면(최대 [SHUTDOWN_WAIT_MAX_MS]) Speaker 를 정리한다.
         * companion 에 두어 람다가 Activity 를 붙잡지 않게 한다(Speaker 는 applicationContext 만 쥔다).
         */
        private fun shutdownWhenQuiet(s: Speaker, waitedMs: Long = 0L) {
            if (!s.isBusy() || waitedMs >= SHUTDOWN_WAIT_MAX_MS) {
                s.shutdown()
                return
            }
            Handler(Looper.getMainLooper()).postDelayed(
                { shutdownWhenQuiet(s, waitedMs + SHUTDOWN_POLL_MS) },
                SHUTDOWN_POLL_MS,
            )
        }
    }
}
