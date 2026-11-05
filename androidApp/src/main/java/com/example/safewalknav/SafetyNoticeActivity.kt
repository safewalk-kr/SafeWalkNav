package com.example.safewalknav

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.safewalknav.a11y.AudioRouting
import com.example.safewalknav.a11y.Speaker
import com.example.safewalknav.a11y.VolumeDownRepeat
import com.example.safewalknav.a11y.WindowFocus

/**
 * 첫 실행 안전 고지 게이트.
 *
 * 흐름:
 *  - 이미 동의함(SharedPreferences) → 즉시 HomeActivity 이동 (고지 스킵)
 *  - 미동의 → 고지 화면 표시
 *      · [동의]   → 동의 저장 후 HomeActivity 이동
 *      · [비동의] → 차단 화면 ("안내 다시 듣기" 로 복귀 가능)
 *
 * 버튼 배치 (2026-10 변경): 위 [동의하지 않음](회색·작게) / **맨 아래 [동의하고 시작하기](노랑·크게)**.
 * 예전엔 [동의] 가 위, [동의하지 않음] 이 맨 아래였는데, 화면 아래 가장자리를 손끝으로 더듬어 누르는
 * 시각장애인이 "동의하지 않음"을 눌러 막혔다. 손이 먼저 가는 맨 아래에 앞으로 나아가는 버튼을 둔다.
 * TalkBack 읽는 순서는 고지 문장 → 동의 → 동의하지 않음 그대로(레이아웃의 traversalBefore/After).
 *
 * 접근성 (2026-10 수정)
 *  - TalkBack 켜짐: 창이 포커스를 얻은 뒤 **고지 문장에 접근성 포커스를 옮긴다.** TalkBack 이 그 문장을
 *    한 번 읽고, 다음 스와이프가 곧바로 [동의] 버튼이다.
 *    예전에는 announceForAccessibility 로 따로 읽혀서 TalkBack 이 포커스 낭독과 겹쳐 **두 번** 읽거나,
 *    창 전환 직후라 아예 묻혔다. (그 API 는 Android 16 에서 폐기 예정이기도 하다)
 *  - TalkBack 꺼짐: 예전에는 **아무 소리도 안 났다** — TalkBack 을 안 쓰는 시각장애인은 고지를 들을
 *    방법이 없었다. 이제 앱 음성으로 고지 + 버튼 위치를 읽는다. 볼륨 아래 짧게 = 다시 듣기.
 */
class SafetyNoticeActivity : AppCompatActivity() {

    private lateinit var noticeContainer: LinearLayout
    private lateinit var blockedContainer: LinearLayout
    private var speaker: Speaker? = null

    /**
     * 볼륨 아래 짧게 = 지금 화면(고지 또는 차단)을 처음 보여 줄 때처럼 다시 들려준다.
     * 긴 고지를 한 번에 못 알아들으면 다시 들을 방법이 없었다(TalkBack 꺼진 사용자용 — 켜져 있으면 손대지 않음).
     */
    private val volumeRepeat = VolumeDownRepeat(this) { presentCurrent() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 아이콘 바로가기로 처음 켠 뒤 일반 아이콘을 누르면, 시스템이 기존 화면들(홈·카메라) 위에
        // 이 화면을 **새로** 올린다(태스크의 첫 인텐트가 바로가기라 아이콘 인텐트와 안 맞아서).
        // 그대로 진행하면 홈이 하나 더 쌓여, 뒤로 가기에 떠난 줄 알았던 카메라가 다시 켜진다.
        // 이미 앱이 떠 있으면 그 화면으로 돌아가게 그냥 닫는다.
        if (!isTaskRoot && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish()
            return
        }

        // 이미 동의한 사용자는 고지 건너뛰고 바로 본 앱으로.
        if (hasAgreed()) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_safety_notice)
        title = "SafeWalk 안전 안내"
        // 볼륨 올리기가 벨소리가 아니라 안내 음성 볼륨을 움직이게.
        AudioRouting.bindVolumeKeys(this)
        speaker = Speaker(this, statusView = null)

        noticeContainer = findViewById(R.id.noticeContainer)
        blockedContainer = findViewById(R.id.blockedContainer)

        findViewById<Button>(R.id.btnAgree).setOnClickListener {
            setAgreed()
            goToMain()
        }
        findViewById<Button>(R.id.btnDecline).setOnClickListener { showBlocked() }
        findViewById<Button>(R.id.btnReconsider).setOnClickListener { showNotice() }

        // 설정 변경(다크 모드 자동 전환·글꼴 크기 등)으로 화면이 다시 만들어지면 레이아웃은 고지 화면으로 돌아온다.
        // 차단 화면에 있었다면 차단 화면을 그대로 보여 주고 그 짧은 안내만 한다 — 예전에는 긴 고지를 처음부터 다시 읽었다.
        if (savedInstanceState?.getBoolean(STATE_BLOCKED, false) == true) showBlocked() else showNotice()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 이미 동의해 곧바로 닫히는 경우엔 화면을 만들지 않았다.
        if (::blockedContainer.isInitialized) {
            outState.putBoolean(STATE_BLOCKED, blockedContainer.visibility == View.VISIBLE)
        }
    }

    /** 고지를 읽던 중에 화면을 떠났다 — 돌아오면 처음부터 다시 들려준다. */
    private var cutOffWhileLeaving = false

    /**
     * 홈 버튼·최근 앱으로 나가면 **즉시** 말을 멈춘다.
     * 예전에는 이 화면만 멈추는 코드가 없어서, 앱 밖으로 나가도 긴 고지문이 끝까지 흘러나왔다
     * (새로 설치하면 맨 먼저 뜨는 화면이라 가장 먼저 겪는다).
     */
    override fun onPause() {
        super.onPause()
        val s = speaker ?: return
        if (s.isBusy()) cutOffWhileLeaving = true
        s.stop()
    }

    override fun onResume() {
        super.onResume()
        // 끊긴 채 조용한 동의 화면은 막다른 길이다 — 무엇에 동의하는지, 버튼이 어디인지 다시 알려 준다.
        if (cutOffWhileLeaving) {
            cutOffWhileLeaving = false
            presentCurrent()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        volumeRepeat.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        volumeRepeat.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)

    /** 지금 보이는 화면(고지 / 차단)을 처음 보여 줄 때와 똑같이 다시 들려준다. */
    private fun presentCurrent() {
        // 이미 동의해 곧바로 닫히는 경우엔 화면을 만들지 않았다.
        if (!::blockedContainer.isInitialized || isFinishing) return
        if (blockedContainer.visibility == View.VISIBLE) showBlocked() else showNotice()
    }

    override fun onDestroy() {
        speaker?.shutdown()
        super.onDestroy()
    }

    private fun showNotice() {
        noticeContainer.visibility = View.VISIBLE
        blockedContainer.visibility = View.GONE
        present(findViewById(R.id.tvNotice), NOTICE_GUIDE)
    }

    private fun showBlocked() {
        noticeContainer.visibility = View.GONE
        blockedContainer.visibility = View.VISIBLE
        present(findViewById(R.id.tvBlocked), BLOCKED_GUIDE)
    }

    /**
     * 화면의 핵심 문장을 들려준다.
     *  - TalkBack: 그 문장으로 접근성 포커스를 옮긴다(TalkBack 이 읽음). 비동의 → 차단 화면처럼
     *    방금 누른 버튼이 사라지는 경우에도 포커스가 허공에 남지 않는다.
     *  - 그 외: 앱 음성으로 문장 + 버튼 위치 안내.
     */
    private fun present(text: TextView, guide: String) {
        val s = speaker ?: return
        if (s.isScreenReaderOn()) {
            WindowFocus.runWhenFocused(text) {
                text.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            }
        } else {
            s.speak("${text.text} $guide")
        }
    }

    /**
     * 2026-10: 첫 화면이 카메라에서 기능 3개짜리 홈으로 바뀌었다.
     *
     * 아이콘 바로가기로 들어왔으면(EXTRA_OPEN) **홈과 목적 화면을 한 번에** 쌓는다(startActivities).
     * 목적 화면이 바로 앞에 뜨고, 뒤로 가면 홈이 그때 처음 만들어진다.
     * 예전처럼 홈이 받아서 다시 넘기면 홈이 잠깐 보이고 TalkBack 이 "SafeWalk 홈"을 읽은 뒤
     * 카메라로 넘어가 어수선했다.
     */
    private fun goToMain() {
        // [동의] 연타 — finish() 뒤에도 두 번째 클릭이 처리돼 홈(+카메라)이 두 번 쌓이는 것을 막는다.
        if (isFinishing) return
        speaker?.stop()
        val home = Intent(this, HomeActivity::class.java)
        val target = when (intent.getStringExtra(HomeActivity.EXTRA_OPEN)) {
            HomeActivity.OPEN_SIGNAL -> Intent(this, MainActivity::class.java)
            HomeActivity.OPEN_NEARBY -> Intent(this, com.example.safewalknav.nearby.NearbyActivity::class.java)
            else -> null
        }
        if (target != null) startActivities(arrayOf(home, target)) else startActivity(home)
        finish()
    }

    // ---- SharedPreferences ----
    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun hasAgreed() = prefs().getBoolean(KEY_AGREED, false)
    private fun setAgreed() = prefs().edit().putBoolean(KEY_AGREED, true).apply()

    companion object {
        private const val PREFS = "safewalk_prefs"
        private const val KEY_AGREED = "hasAgreedToSafetyNotice"
        private const val STATE_BLOCKED = "blockedShowing"

        /**
         * TalkBack 이 꺼진 사용자용 — 버튼을 눈으로 못 찾으므로 위치를 말해 준다.
         * 동의 버튼이 맨 아래(손끝이 먼저 닿는 곳), 그 위가 회색 [동의하지 않음].
         */
        private const val NOTICE_GUIDE =
            "동의하려면 화면 맨 아래 큰 노란 버튼을 누르세요. 그 위 회색 버튼은 동의하지 않음입니다. " +
                "소리 줄이는 버튼을 짧게 누르면 다시 들을 수 있습니다."
        private const val BLOCKED_GUIDE =
            "안내를 다시 들으려면 화면 아래쪽 노란 버튼을 누르세요."
    }
}
