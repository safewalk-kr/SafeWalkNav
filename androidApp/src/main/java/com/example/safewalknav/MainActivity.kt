package com.example.safewalknav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.example.safewalknav.a11y.AppSettings
import com.example.safewalknav.a11y.AudioRouting
import com.example.safewalknav.a11y.PermissionGate
import com.example.safewalknav.a11y.Speaker
import com.example.safewalknav.a11y.SpeechFocus
import com.example.safewalknav.a11y.VolumeDownRepeat
import com.example.safewalknav.ml.MotionGate
import com.example.safewalknav.ml.TrafficLightAnalyzer
import com.example.safewalknav.ml.TrafficLightDetection
import com.example.safewalknav.ml.TrafficLightDetector
import com.example.safewalknav.navigation.signal.RawSignalDetection
import com.example.safewalknav.navigation.signal.SignalDecision
import com.example.safewalknav.navigation.signal.SignalDecisionEngine
import com.example.safewalknav.navigation.signal.SignalTransition
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * SafeWalk — 시각장애인용 보행 신호등 인식 앱 (카메라 단일 기능).
 *
 * 흐름: 앱 실행 → (SafetyNoticeActivity 고지 게이트) → MainActivity 진입 즉시
 *       후방 카메라 + 신호등 인식 시작 → 색/전환을 음성·진동으로 안내.
 *
 * 특별한 점: 한국 보행 신호등에 맞춘 모델(kairess, `crosswalk_kairess.tflite`)을 온디바이스로 사용.
 * 판정 로직은 shared 모듈의 [SignalDecisionEngine] 에 있어 Android/iOS 동작이 일치한다.
 *
 * 2026-07 — 도보 내비게이션(TMap 경로·GPS·목적지 입력·상태기계)을 전면 제거하고
 *           "열면 바로 신호 인식" 단일 기능으로 재구성. (OKO 식)
 *
 * 2026-09 — 사용자 동행 테스트에서 두 가지가 드러났다.
 *   (1) 신호등이 어디 있는지 몰라 폰을 빠르게 휘두른다 → 블러 + 안정성 스트릭 리셋으로
 *       오히려 더 안 잡힌다.  → [MotionGate] 로 그 동안 추론을 건너뛴다.
 *   (2) "앱에서 소리가 너무 많이 난다".  → 조준 안내를 음성에서 [AimingFeedback] 진동으로
 *       옮기고, 남은 발화의 간격과 길이를 줄였다.
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // ===== 뷰 =====
    private lateinit var cameraPreviewContainer: FrameLayout
    private lateinit var overlayColorView: View
    private lateinit var overlayStatusText: TextView

    // ===== 카메라 / 검출 =====
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysisExecutor: ExecutorService? = null
    private var trafficLightDetector: TrafficLightDetector? = null

    // ===== 신호 판정 (shared 공용 엔진) =====
    private val signalDecisionEngine = SignalDecisionEngine()

    // ===== TTS / 진동 =====
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private lateinit var vibrator: Vibrator
    private var introSpoken = false

    /** TTS 를 아예 쓸 수 없는 상태(엔진 초기화 실패·한국어 음성 없음). 진동으로만 안내한다. */
    private var ttsUnavailable = false

    /** 카메라 바인딩 실패 — TTS 준비 전에 날 수 있어, 준비되면 인트로 대신 이걸 말한다. */
    private var cameraBindFailed = false

    /** 볼륨 0 경고를 이미 했는가. 볼륨이 올라가면 다시 false 로 돌아가 재경고가 가능해진다. */
    private var volumeWarned = false

    // ===== 볼륨 키 조작 (2026-09) =====

    /** 사용자가 직접 일시정지했는가. onPause 로 인한 정지와 구분해야 onResume 이 멋대로 켜지 않는다. */
    private var userPaused = false

    /** 볼륨 다운 롱프레스를 이미 처리했는가. 키를 뗄 때 짧은 누름으로 중복 처리하지 않기 위함. */
    private var volumeLongPressHandled = false

    /**
     * 지금 누르고 있는 볼륨 다운의 downTime. 이 앱이 DOWN 을 직접 받은 누름만 처리한다.
     * 전화가 울릴 때 볼륨 키를 누르면 시스템이 DOWN 을 가져가(벨소리 끄기) UP 만 앱에 넘긴다 —
     * 그 UP 을 다시 듣기로 치면 벨소리 위로 지난 안내가 나왔다.
     */
    private var volumeDownSeenAt = NO_KEY_PRESS

    /**
     * 롱프레스 문턱을 시스템(0.4~0.5초) 대신 직접 잰다([VolumeDownRepeat.LONG_PRESS_MS] = 1초).
     * 천천히 누르는 어르신은 0.5초를 쉽게 넘겨, 다시 듣기를 누르려다 **의도치 않게 일시정지**했다 —
     * 이 앱에서 가장 위험한 실수(영구 침묵)다. 다른 화면과 문턱을 같게 둔다.
     */
    private val volumeKeyHandler = Handler(Looper.getMainLooper())
    private val volumeLongPressRunnable = Runnable { onVolumeLongPress() }

    /** 일시정지 재알림을 몇 번 했는가. 일부러 멈춘 사람에게 영원히 잔소리하지 않기 위한 상한. */
    private var pausedReminderCount = 0

    /** 마지막으로 안내한 신호 문구·색·시각. 다시 듣기용. (heartbeat 반복은 여기 덮어쓰지 않는다) */
    private var lastAnnouncement: String? = null
    private var lastAnnouncedColor: Int = SignalDecisionEngine.COLOR_NONE
    private var lastAnnouncementAtMs = 0L

    /** 그 색을 마지막으로 다시 확인한 시각 (heartbeat 반복 포함). */
    private var lastColorConfirmedAtMs = 0L

    /** 마지막 안내가 이전 카메라 세션(일시정지·백그라운드 전)의 것인가 — 그러면 언제나 "지난 정보"다. */
    private var announcementFromEarlierSession = false

    /**
     * 접근성 용도 오디오 속성. **TTS 와 진동 양쪽**에 쓴다.
     *
     * TTS: 기본값(USAGE_MEDIA)은 미디어 볼륨을 타는데, 시각장애인 사용자는 미디어를 0 으로
     *      두고 접근성 볼륨만 쓰는 경우가 흔하다. 그러면 앱이 통째로 무음이 된다.
     * 진동: 속성이 없으면 USAGE_UNKNOWN 이라 시스템 진동 설정(터치 진동 끔 등)에서 억제된다.
     *      ⚠️ 절전 모드에서는 접근성 용도라도 **면제되지 않는다**(AOSP 기준 — 절전 모드가 진동을 끄는
     *      기기에서, 켜진 접근성 서비스가 하나도 없으면 모든 진동이 사라진다). [checkDeviceHealth] 가 알린다.
     */
    private val a11yAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    /**
     * 마지막으로 **발화를 큐에 넣은** 시각(단조 시계).
     * [livenessTick] 이 "살아 있는데 조용한 것"과 "죽어서 조용한 것"을 구분하는 근거.
     *
     * ⚠️ 진동 경로에서는 갱신하지 않는다. 조준 소나가 이걸 갱신하면
     * "계속 울리기만 하고 아무 판정도 안 나오는" 바로 그 상태에서 워치독이 영영 안 깨어난다.
     * 색 확정·경고 진동은 언제나 발화와 짝지어 나가므로 발화 쪽만 찍으면 충분하다.
     */
    private var lastAudibleAtMs = 0L

    /** 파이프라인이 멎었는지 감시하는 워치독 핸들러. */
    private val livenessHandler = Handler(Looper.getMainLooper())

    /**
     * 첫 안내 지연 전용 핸들러. livenessHandler 는 워치독·일시정지 알림이 removeCallbacksAndMessages(null)
     * 로 통째로 비우므로, 같이 쓰면 카메라 시작 순간 첫 안내가 지워진다.
     */
    private val introHandler = Handler(Looper.getMainLooper())

    /** 첫 안내를 아직 못 내보냄 (TalkBack 지연 중이거나 화면이 가려져 있음). 문장은 내보낼 때 고른다. */
    private var introPending = false

    /**
     * 첫 안내(조작법)를 말하는 중. TTS 콜백 스레드가 내리므로 @Volatile.
     * 홈 버튼으로 나가며 끊기면 onPause 가 [introPending] 을 다시 세워, 돌아왔을 때 처음부터 들려준다 —
     * 볼륨 키 조작법은 이 안내가 아니면 알 방법이 없다.
     */
    @Volatile
    private var introInFlight = false

    /** 말하는 동안 음악·다른 앱 소리를 줄이는 오디오 포커스. */
    private val speechFocus by lazy { SpeechFocus(this, a11yAudioAttributes) }
    private val focusHandler = Handler(Looper.getMainLooper())
    /** 아직 말하는 중이면(일부 엔진은 끝난 직후에도 잠깐 true) 조금 뒤 다시 본다 — 한 번만 보면 포커스가 남는다. */
    private val releaseFocusIfIdle: Runnable = object : Runnable {
        override fun run() {
            if (::tts.isInitialized && tts.isSpeaking) {
                focusHandler.postDelayed(this, FOCUS_RELEASE_DELAY_MS)
            } else {
                speechFocus.release()
            }
        }
    }

    /**
     * "방금 초록불로 바뀌었습니다. 건너세요" 뒤 [CROSSING_WINDOW_MS] 동안 = **건너는 중일 수 있는 시간.**
     *
     * 왜: 건너기 시작하면 폰은 아래로 처지고 팔이 흔들린다. 예전에는 그때 카메라가 바닥의 횡단보도 무늬를
     * 겨눠 조준 진동과 '찾았다' 2연타(= "멈춰요, 여기예요")가 울리고, 6초 뒤 "왼쪽으로"가 나왔다.
     * 차도 한가운데서 그걸 걷는 방향 지시로 알아듣고 틀거나 멈출 수 있다.
     * 그리고 건너는 도중 빨간불·점멸에 "정지하세요"는 보행교육 원칙("이미 건너는 중이면 멈추지 말고
     * 끝까지")과 반대다.
     * 이 동안은 조준·미탐지 안내·"천천히 움직여 주세요"를 내지 않고, 빨간불·점멸 문구를 바꾼다.
     * 안전 판정(색 확정)은 그대로 돈다.
     *
     * ⚠️ 카메라가 멈췄다 켜져도(일시정지·전원 키·통화·화면 재생성) **지우지 않는다.** 이건 엔진 상태가 아니라
     * 사용자의 몸이 차도 위에 있을 수 있다는 사실이다. 예전에는 stopCamera 가 지워서, 건너다 실수로
     * 일시정지했다 풀면 첫 빨간불에 "정지하세요"와 조준 진동이 차도 한가운데서 되살아났다. 끝은 시간뿐이다.
     */
    private var crossingUntilMs = 0L

    private fun inCrossingWindow(): Boolean = SystemClock.elapsedRealtime() < crossingUntilMs

    /** 건너는 중일 수 있으면 오류·상태 안내 앞에 "멈추지 말고 끝까지"를 붙인다 — 폰 조작 지시보다 먼저. */
    private fun crossingAware(message: String): String =
        if (inCrossingWindow()) CROSSING_KEEP_GOING + message else message

    /** 마지막 안내의 전환 종류 — 진동·다시 듣기 문구를 "행동" 기준으로 고르는 데 쓴다. 점멸이면 null. */
    private var lastAnnouncedTransition: SignalTransition? = null

    /** 권한 결과·설정 복귀 직후의 안내를 TalkBack 의 창 제목 낭독 뒤로 미루는 핸들러. */
    private val settledSpeechHandler = Handler(Looper.getMainLooper())

    /** 처음 들어왔는데 카메라 권한이 없음 — TTS 가 준비되면 권한 창 예고를 먼저 말하고 창을 띄운다. */
    private var firstPermissionPromptPending = false

    /** 카메라 권한을 "다시 묻지 않음"으로 거부함 — 시스템이 권한 창을 더 띄우지 않으므로 설정 화면으로 보낸다. */
    private var cameraPermissionBlocked = false

    /** 마지막 권한 요청 시각 — 창이 뜨지도 않고 즉시 거부됐는지 판단용([PermissionGate]). */
    private var cameraPermissionRequestedAt = 0L

    /**
     * 권한 창·설정 화면을 **앱이 직접** 띄우는 중. 그 안내 문장이 onPause 의 tts.stop() 에 잘리지 않도록
     * 말이 끝난 뒤에 띄우고([speakThen]), 설정 화면에서 그냥 돌아왔을 때 다시 안내하는 데 쓴다.
     */
    private var sentToSettings = false
    private var pendingSystemAction: (() -> Unit)? = null
    private val systemActionHandler = Handler(Looper.getMainLooper())

    /** 이번 카메라 세션에서 이미 안내한 카메라 오류(다른 앱 사용 중 등) — 같은 말을 반복하지 않게. */
    private var cameraInUseAnnounced = false

    /** 이번 카메라 세션에서 추론 오류를 이미 알렸는가. */
    private var inferenceErrorAnnounced = false

    /** 분석 결과(검출 0건 포함)가 마지막으로 온 시각. 결과가 끊겼는데 조준 진동이 계속 울리는 것을 막는다. */
    private var lastResultAtMs = 0L

    /** 결과 끊김 감시 전용 핸들러 (livenessHandler 는 통째로 비워지는 일이 잦아 따로 둔다). */
    private val aimStaleHandler = Handler(Looper.getMainLooper())

    /** 모델 로드 실패 — TTS 준비 전에 났을 수 있어, 준비되면 인트로 대신 이걸 말한다. */
    private var modelLoadFailed = false

    /**
     * 모델(약 25MB)을 분석 스레드에서 여는 중. 예전에는 메인 스레드에서 열어, NNAPI 컴파일이 긴 기기에서
     * 몇 초간 검은 화면 + 무음이었고 그 사이 키를 누르면 ANR 위험이 있었다.
     */
    private var detectorLoading = false
    private val detectorHandler = Handler(Looper.getMainLooper())

    /** 카메라 provider 는 준비됐는데 모델이 아직 열리는 중 — 모델이 열리면 이걸로 바인딩한다. */
    private var pendingProvider: ProcessCameraProvider? = null
    private var pendingPreviewView: PreviewView? = null

    /**
     * 워치독이 연달아 울린 횟수. 유효 판정이 나오면 0. 상한에 닿으면 자동 일시정지한다 — 카메라 점유·추론 실패가
     * 계속되면 프레임 결과가 없어 미탐지 자동 일시정지도 안 걸리고, 화면이 켜진 채 45초마다 경고만 끝없이 나왔다.
     * 0 보다 크면 꾹 누르기는 일시정지가 아니라 다시 켜기다(워치독 문구와 맞춘다).
     */
    private var livenessAlarmCount = 0

    /** 이번 카메라 세션이 시작된 시각 — 건너기 창이 열린 **뒤에** 카메라가 다시 켜졌는지 판단용. */
    private var cameraSessionStartedAtMs = 0L

    /** 볼륨 0 이 아니라 **수화부로 통화 중**이라 안 들리는 것 — 통화가 끝나면 그 사실을 알린다. */
    private var mutedByCall = false

    /** 배터리·발열·절전 안내를 이 화면에서 이미 했는가 (한 번씩만). */
    private var batteryLowWarned = false
    private var batteryCriticalWarned = false
    private var heatWarned = false
    private var powerSaveWarned = false

    /**
     * 화면을 떠날 때 인식이 돌고 있었다 — 돌아와 카메라가 다시 켜질 때 "다시 켰습니다"를 알린다.
     * 예전에는 전원 키·통화·앱 전환 뒤 카메라만 조용히 다시 켜져, 신호등이 안 보이면 9초 가까이 무음이었다.
     */
    private var resumeCueOwed = false

    /** 첫 안내(조작법)가 신호 안내에 잘렸다 — 기다리는 상태가 되면 조작법만 짧게 다시 알린다. 콜백 스레드가 내린다. */
    @Volatile
    private var introHintOwed = false

    /** 확정 진동을 조준 진동과 떼어 울리기 위한 짧은 지연. */
    private val confirmVibrationHandler = Handler(Looper.getMainLooper())

    private val audioManager: AudioManager? by lazy { getSystemService(Context.AUDIO_SERVICE) as? AudioManager }

    /**
     * 카메라 권한 거부 — 모델 실패와 같은 이유로 플래그가 필요하다.
     * 권한 다이얼로그는 앱을 켜자마자 뜨는데 TTS 초기화는 수백 ms 걸린다. 그래서
     * 거부 시점에는 [ttsReady] 가 대개 false 고, 그대로 두면 안내가 통째로 증발한 뒤
     * onInit 이 태연하게 "신호등을 카메라에 비춰 주세요" 라고 말한다.
     * 카메라도 분석기도 없으니 그 뒤로는 **영원히 아무 소리도 나지 않는다.**
     */
    private var permissionDenied = false

    // ===== 조준 보조 (2026-09) =====
    /** 대상이 중앙에 가까울수록 빨라지는 진동. 음성 조준 안내를 대체한다. */
    private lateinit var aiming: AimingFeedback

    /**
     * 빠르게 휘두르는 동안 추론을 건너뛰는 게이트.
     * 분석 스레드가 읽으므로 @Volatile. 소멸 시에도 null 로 만들지 않고 stop() 만 한다
     * (분석 스레드가 들고 있던 참조로 접근해도 안전한 상태가 되도록).
     */
    @Volatile
    private var motionGate: MotionGate? = null

    /**
     * 카메라가 살아 있고 분석 결과를 받아도 되는가.
     *
     * ⚠️ 이 플래그가 없으면 진동이 영영 안 꺼지는 경로가 생긴다.
     * 분석 스레드가 `detect()` 안에 있는 동안 사용자가 앱을 백그라운드로 보내면,
     * `onPause → stopCamera()` 가 먼저 끝나고 **그 뒤에** 늦은 결과가
     * `runOnUiThread` 로 도착한다. 그게 [AimingFeedback.update] 를 불러 repeat 파형을
     * 다시 걸면, 그걸 끌 주체가 아무도 남아 있지 않다 — 재부팅 전까지 계속 울린다.
     * 시각장애인 사용자에게는 "지금 잘 겨누고 있다"는 거짓 신호이기도 하다.
     */
    @Volatile
    private var analysisEnabled = false

    /** 마지막으로 검출이 1건이라도 있었던 시각. [updateAiming] 이 "아직 보고 있나" 판단에 쓴다. */
    private var lastDetectionAtMs = 0L

    /** 엔진이 마지막으로 **유효 판정**(Announce·Repeat·Flicker)을 낸 시각. 박스 개수와 무관. */
    private var lastValidatedAtMs = 0L

    /** startCamera 가 비동기 바인딩을 기다리는 중인지. 중복 시작 방지. */
    private var cameraStarting = false

    /**
     * 카메라 시작 세대 번호. startCamera 마다 +1, stopCamera 에서도 +1.
     * 비동기 리스너는 자기 세대가 아직 유효할 때만 바인딩한다 —
     * pause→resume 이 빠르게 일어나면 리스너가 두 개 떠서, 먼저 뜬(이미 화면에서 떼어낸
     * PreviewView 를 들고 있는) 쪽이 나중 것을 덮어쓸 수 있다.
     */
    private var cameraGeneration = 0

    // ===== 미탐지 안내 (카메라 켜고 6초 대기 → 30초 간격 반복 → 20초 넘으면 소리주의 1회) =====
    private var noDetStartedAt = 0L
    private var noDetLastSpeechAt = 0L
    private var noDetSafetyDone = false
    private val NO_DET_FIRST_MS = 6_000L

    // 2026-09: 12초 → 30초. 조준 정보는 이제 진동이 실시간으로 주므로, 음성은
    // "아직도 못 찾고 있다"는 사실만 가끔 확인해 주면 된다.
    private val NO_DET_REPEAT_MS = 30_000L
    private val NO_DET_SAFETY_MS = 20_000L
    // ⚠️ 판정 임계(0.25)보다 반드시 낮아야 한다. 같으면 "임계 미만 후보" 억제가 영영 발동 안 함.
    private val NO_DET_SUPPRESS_PEAK_CONFIDENCE = 0.18f

    /** '곧 잡힐 듯' 억제의 시작 시각. 0 이면 비활성. */
    private var noDetSuppressStartedAt = 0L

    /** '곧 잡힐 듯' 억제의 최대 지속 시간 — 이걸 넘기면 약한 오탐으로 보고 안내를 돌린다. */
    private val NO_DET_SUPPRESS_MAX_MS = 10_000L
    private var detectStartHoldUntil = 0L
    private val DETECT_START_HOLD_MS = 3_000L   // 카메라 켠 직후 겨눌 시간

    private val CAMERA_PERMISSION_CODE = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // TalkBack 은 창이 바뀔 때 이 제목을 읽는다. 기본값(앱 이름 "SafeWalk")이면 홈에서 들어와도
        // 어느 화면인지 알 수 없다.
        title = "신호등 카메라"

        cameraPreviewContainer = findViewById(R.id.cameraPreviewContainer)
        overlayColorView = findViewById(R.id.overlayColorView)
        overlayStatusText = findViewById(R.id.overlayStatusText)

        // ⚠️ 진동·조준을 **TTS 보다 먼저** 만든다.
        //    TextToSpeech 생성자는 엔진 바인딩에 실패하면 onInit(ERROR) 를 **동기로**,
        //    즉 생성자가 반환하기도 전에 호출한다. 그 분기가 진동으로 실패를 알리는데,
        //    순서가 반대면 그 순간 vibrator 가 아직 lateinit 미초기화라
        //    UninitializedPropertyAccessException 으로 onCreate 에서 죽는다.
        //    하필 "TTS 엔진이 없는 기기" — 그 분기가 도우려던 바로 그 사용자다.
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        aiming = AimingFeedback(vibrator, a11yAudioAttributes)

        tts = TextToSpeech(this, this)

        motionGate = MotionGate(
            context = this,
            // 휘두르기 시작 → 조준 진동을 즉시 끈다. 추론이 멈춰 콜백이 안 오므로
            // 여기서 끄지 않으면 마지막 박자가 그대로 반복된다(거짓 정보).
            onFastMotionStart = { aiming.stop() },
            // 1.5초 넘게 계속 휘두르면 한 번만 짚어 준다 (쿨다운은 MotionGate 가 관리).
            // 건너는 중에는 팔이 흔들리는 게 당연하다 — 그때 "천천히 움직여 주세요"는 걷는 속도 지시로 들린다.
            onSustainedFastMotion = {
                if (!inCrossingWindow()) {
                    speakTrafficLight("조금 더 천천히 돌려 주세요", interrupt = false, countsAsLiveness = false)
                }
            },
        )

        setIdleVisual()

        // ⚠️ return 보다 먼저 — 일시정지 상태로 복원될 때 빠지면, 재개해도 볼륨 올리기가 앱 음성 볼륨을 안 움직였다.
        AudioRouting.bindVolumeKeys(this)   // 볼륨 올리기가 앱 음성 볼륨을 움직이게

        // 회전·복원으로 다시 만들어졌다면 일시정지 상태를 되살린다.
        userPaused = savedInstanceState?.getBoolean(STATE_USER_PAUSED, false) ?: false
        sentToSettings = savedInstanceState?.getBoolean(STATE_SENT_TO_SETTINGS, false) ?: false
        cameraPermissionBlocked = savedInstanceState?.getBoolean(STATE_CAMERA_PERMISSION_BLOCKED, false) ?: false
        // 단조 시계(부팅 후 경과)라 프로세스가 죽었다 살아나도 그대로 유효하다.
        crossingUntilMs = savedInstanceState?.getLong(STATE_CROSSING_UNTIL, 0L) ?: 0L
        setKeepScreenOn(!userPaused)
        if (userPaused) {
            // ⚠️ 남은 알림 횟수도 함께 복원한다. startPausedReminder() 는 횟수를 0 으로
            //    되돌리므로, 다크모드 전환·글꼴 변경 한 번마다 잔소리 예산이 리필돼
            //    "3번만 알린다"는 약속이 깨진다 (onResume 쪽은 이미 지키고 있는 약속).
            pausedReminderCount = savedInstanceState?.getInt(STATE_PAUSED_REMINDER_COUNT, 0) ?: 0
            resumePausedReminder()
            return   // 카메라도 권한 요청도 하지 않는다 — 멈춰 있던 상태 그대로
        }

        if (hasCameraPermission()) {
            startCamera()
        } else if (savedInstanceState == null) {
            // TalkBack 없이 쓰는 사람은 권한 창이 떴다는 것조차 모른다 — 예전에는 홈 음성이 끊기고 그대로 무음이었다.
            // TTS 가 준비되면(onInit) 먼저 예고하고 창을 띄운다. TalkBack 사용자는 TalkBack 이 창을 읽으니 바로 띄운다.
            // TTS 가 생성자 안에서 이미 실패했으면(ttsUnavailable) 기다릴 onInit 이 없으므로 바로 띄운다.
            if (Speaker.isScreenReaderOn(this) || ttsUnavailable) {
                requestCameraPermission()
            } else {
                firstPermissionPromptPending = true
            }
        } else {
            // 재생성(프로세스 복원 등) 때는 권한 창을 다시 띄우지 않는다 — 떠 있던 창의 결과는 새 인스턴스로 온다.
            // 대신 "권한 없음" 상태로 둬서, 첫 안내가 "비춰 주세요"(카메라 없음) 대신 권한 안내가 되게 한다.
            permissionDenied = true
            overlayStatusText.text = "카메라 권한이 필요합니다"
        }
    }

    /**
     * TTS 를 쓸 수 없게 됐다 — 예고 없이라도 권한 창은 띄운다(안 띄우면 카메라가 영영 안 켜진다).
     * 화면을 떠난 뒤면 띄우지 않고 남겨 둔다 — 돌아오면 onResume 이 띄운다.
     */
    private fun requestPermissionIfAwaitingTts() {
        if (firstPermissionPromptPending && isResumedNow()) requestCameraPermission()
    }

    private fun isResumedNow(): Boolean =
        !isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    /**
     * 처음 들어왔는데 카메라 권한이 없다 — 화면 이름과 권한 창 예고를 먼저 말하고, 말이 끝나면 창을 띄운다.
     * 조작법(첫 안내)은 창이 닫힌 뒤 onResume 이 introPending 을 보고 들려준다.
     * 예고 도중 화면을 떠나면(onPause 가 대기 중인 창 열기를 취소) 돌아왔을 때 onResume 이 다시 부른다 —
     * [firstPermissionPromptPending] 은 창을 실제로 띄울 때에야 내려간다.
     */
    private fun promptFirstPermission() {
        introSpoken = true
        introPending = true
        speakThen("${INTRO_TITLE}곧 카메라 권한을 묻는 창이 뜹니다. ${permissionButtonHint()}") {
            requestCameraPermission()
        }
    }

    private fun requestCameraPermission() {
        firstPermissionPromptPending = false
        cameraPermissionRequestedAt = PermissionGate.markRequested()
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
        )
    }

    /** 인식 중에는 화면을 켜 둔다(꺼지면 카메라가 멈춘다). 일시정지 중에는 놓아준다 — 배터리·주머니 속 오작동. */
    private fun setKeepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun hasCameraPermission() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (hasCameraPermission()) {
                // 첫 실행에서 바로 허용한 경우엔 첫 안내가 따로 나가므로 말하지 않는다.
                // 거부했다가 다시 허용한 경우에만 "허용됐다 + 무엇을 하면 되는지"를 알린다.
                val wasDenied = permissionDenied
                permissionDenied = false
                cameraPermissionBlocked = false
                // 일시정지 상태에서 권한을 얻으러 갔다 온 경우 — 그대로 두면 카메라는 도는데
                // "일시정지 중입니다" 알림이 나가고, 다음 롱프레스가 엉뚱하게 해석된다. 여기서 재개로 정리한다.
                if (userPaused) {
                    userPaused = false
                    setKeepScreenOn(true)
                    stopPausedReminder()
                    vibratePauseAck(paused = false)
                }
                if (wasDenied) speakSettled(PERMISSION_GRANTED_MESSAGE)
                startCamera()
            } else {
                overlayStatusText.text = "카메라 권한이 필요합니다"
                permissionDenied = true
                // "다시 묻지 않음"이면 시스템이 창을 더는 안 띄운다 → 다음 롱프레스는 설정 화면을 연다.
                // (창을 그냥 닫은 것과 구별한다 — PermissionGate 주석 참조)
                cameraPermissionBlocked = PermissionGate.isBlocked(
                    this, Manifest.permission.CAMERA, cameraPermissionRequestedAt
                )
                supersedePendingIntro()
                // ttsReady 가 false 면 여기서는 묻히고, onInit 이 대신 말한다.
                // TalkBack 이 켜져 있으면 창 제목 낭독 뒤로 미룬다 — 겹치면 복구 방법("길게 누르면…")이 묻힌다.
                speakSettled(if (cameraPermissionBlocked) PERMISSION_BLOCKED_MESSAGE else PERMISSION_DENIED_MESSAGE)
            }
        }
    }

    // ==================== 카메라 ====================

    private fun startCamera() {
        if (cameraProvider != null) return   // 이미 작동 중
        // cameraProvider 는 비동기 리스너 안에서야 채워진다. 그 사이에 onCreate→onResume 이
        // 연달아 startCamera 를 부르면 PreviewView 와 바인딩이 이중으로 생긴다.
        if (cameraStarting) return
        if (!hasCameraPermission()) return
        cameraStarting = true
        val generation = ++cameraGeneration

        val pv = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cameraPreviewContainer.removeAllViews()
        cameraPreviewContainer.addView(pv)
        setKeepScreenOn(true)   // 인식 중에는 화면을 켜 둔다(꺼지면 카메라가 멈춘다)

        if (analysisExecutor == null) analysisExecutor = Executors.newSingleThreadExecutor()
        // 모델은 분석 스레드에서 연다 — 열리면 onDetectorLoaded 가 바인딩을 마저 한다.
        if (trafficLightDetector == null) loadDetectorInBackground()

        // 신호 판정 상태 초기화 + 겨눌 시간(3초)은 미탐지 안내 억제.
        signalDecisionEngine.reset()
        resetNoDetection()
        resetAimingState()
        detectStartHoldUntil = SystemClock.elapsedRealtime() + DETECT_START_HOLD_MS

        // 모션 게이트 가동. 센서가 없는 기기면 start() 가 조용히 아무것도 안 한다.
        motionGate?.reset()
        motionGate?.start()
        aiming.stop()
        cameraInUseAnnounced = false
        inferenceErrorAnnounced = false
        livenessAlarmCount = 0
        cameraSessionStartedAtMs = SystemClock.elapsedRealtime()
        analysisEnabled = true
        lastResultAtMs = SystemClock.elapsedRealtime()
        startLivenessWatchdog()
        startAimStaleWatch()

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            // provider 획득은 비동기다. 그 사이에 stopCamera 나 더 나중의 startCamera 가
            // 지나갔다면 이 리스너는 한물간 것이다. 그대로 바인딩하면 이미 컨테이너에서
            // 떼어낸 PreviewView 에 붙어 검은 화면 + 분석만 도는 좀비 상태가 된다.
            // 한물간 리스너는 **아무것도 바인딩한 적이 없으므로** unbindAll 하지 않는다.
            // 여기서 unbind 하면, 만에 하나 리스너 실행 순서가 뒤집혔을 때 살아 있는
            // 바인딩을 뜯어내 검은 화면으로 만든다. 그냥 빠져나가는 게 안전하다.
            if (generation != cameraGeneration || !analysisEnabled) {
                Log.d(TAG, "stale camera listener (gen=$generation, now=$cameraGeneration) — skip")
                return@addListener
            }
            val provider = try {
                future.get()
            } catch (e: Exception) {
                cameraStarting = false
                handleCameraFailure("provider", e)
                return@addListener
            }
            pendingProvider = provider
            pendingPreviewView = pv
            bindCameraIfReady()
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 모델을 분석 스레드에서 연다. 결과는 메인 스레드의 [onDetectorLoaded] 로 온다.
     * (메인 → 분석 스레드 → 메인 핸들러로 넘기므로 detector 필드의 가시성은 메시지 큐가 보장한다)
     */
    private fun loadDetectorInBackground() {
        if (detectorLoading) return
        val executor = analysisExecutor ?: return
        detectorLoading = true
        val appContext = applicationContext
        try {
            executor.execute {
                val loaded = try {
                    TrafficLightDetector(appContext).apply { diagnosticMode = false }
                } catch (e: Throwable) {
                    // Throwable — 네이티브 라이브러리 로드 실패(UnsatisfiedLinkError)나 모델 메모리 부족(OOM)은
                    // Exception 이 아니라서, 예전 catch 로는 못 잡고 앱이 통째로 죽었다.
                    Log.e(TAG, "Failed to load TrafficLightDetector", e)
                    null
                }
                detectorHandler.post { onDetectorLoaded(loaded) }
            }
        } catch (_: RejectedExecutionException) {
            detectorLoading = false   // 종료 중 — 다음 startCamera 가 다시 시도한다
        }
    }

    private fun onDetectorLoaded(detector: TrafficLightDetector?) {
        detectorLoading = false
        if (isDestroyed) {
            // onDestroy 가 이미 지나갔다 — 분석에 한 번도 쓰이지 않은 인터프리터라 여기서 닫아도 안전하다.
            detector?.close()
            return
        }
        if (detector != null) {
            trafficLightDetector = detector
            modelLoadFailed = false
            Log.d(TAG, "TrafficLightDetector loaded")
            bindCameraIfReady()
            return
        }
        // 실패. 그 사이 카메라를 멈췄다면(일시정지·화면 이탈) 지금은 아무것도 하지 않는다 —
        // 다음 startCamera 가 다시 시도하고, 그때 실패하면 그때 알린다.
        if (!cameraStarting) return
        pendingProvider = null
        pendingPreviewView = null
        cameraStarting = false
        // 카메라를 켜 봐야 할 일이 없다 — 감시·모션 게이트까지 끈다. 안 끄면 "인식을 시작할 수 없습니다"라고
        // 말한 뒤에도 폰을 휘두르면 "천천히 돌려 주세요"가 나와 방금 한 말과 모순이었다.
        analysisEnabled = false
        stopLivenessWatchdog()
        stopAimStaleWatch()
        motionGate?.stop()
        aiming.stop()
        cameraPreviewContainer.removeAllViews()
        setKeepScreenOn(false)
        setStatusText("모델 로드 실패")
        // 화면 글씨만 바꾸면 시각장애인에게는 '아무 일도 안 일어남'과 구별되지 않는다.
        // onResume 마다 재시도하며 같은 말을 반복하지 않도록 한 번만.
        if (!modelLoadFailed) {
            modelLoadFailed = true
            supersedePendingIntro()
            speakTts(crossingAware(MODEL_LOAD_FAILED_MESSAGE))
        }
    }

    /** provider 와 모델이 **둘 다** 준비되면 바인딩한다. 어느 쪽이 먼저 끝나든 나중 쪽이 부른다. */
    private fun bindCameraIfReady() {
        val provider = pendingProvider ?: return
        val pv = pendingPreviewView ?: return
        if (detectorLoading) return
        pendingProvider = null
        pendingPreviewView = null
        val generation = cameraGeneration
        if (!analysisEnabled) {
            cameraStarting = false
            return
        }
        try {
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(pv.surfaceProvider)
                }
                val useCases = mutableListOf<androidx.camera.core.UseCase>(preview)

                val detector = trafficLightDetector
                val executor = analysisExecutor
                if (detector != null && executor != null) {
                    // setTargetResolution 은 deprecated(→ ResolutionSelector) 지만 일부러 유지한다.
                    // 두 API 는 크기 해석 기준(회전 반영 여부)이 달라 분석 프레임 크기가 바뀔 수 있고,
                    // 전처리 변경이 인식률을 떨어뜨린 전례가 있다. 바꾸려면 실기 A/B 후에.
                    @Suppress("DEPRECATION")
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        // 원거리 신호를 위해 분석 프레임 해상도를 높인다(기본 저해상도면 640 리사이즈에서 뭉갬).
                        .setTargetResolution(android.util.Size(1280, 720))
                        .build()
                    analysis.setAnalyzer(
                        executor,
                        // 빠르게 휘두르는 동안은 추론 자체를 건너뛴다 — 블러 프레임이
                        // 안정성 스트릭을 계속 0 으로 되돌리는 악순환을 끊는다.
                        TrafficLightAnalyzer(
                            detector = detector,
                            isActive = { motionGate?.isFastMotion != true },
                            onFailure = { count ->
                                runOnUiThread { if (generation == cameraGeneration) onInferenceFailing(count) }
                            },
                        ) { detections ->
                            // 세대 확인 — 이전 카메라 세션의 늦은 결과가, 막 리셋한 새 엔진에
                            // 섞여 들어가 스트릭을 채우는 것을 막는다(analysisEnabled 는 재시작 즉시 true 라 못 거른다).
                            runOnUiThread { if (generation == cameraGeneration) onTrafficLightDetected(detections) }
                        }
                    )
                    useCases += analysis
                }

                provider.unbindAll()
                val bound = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray()
                )
                camera = bound
                cameraBindFailed = false   // 지난번 실패에서 회복 — 다시 듣기·첫 안내가 옛 실패를 말하지 않게
                // 카메라 열기 실패의 대부분(다른 앱이 사용 중·정책으로 비활성·HAL 오류)은 bindToLifecycle 의
                // 예외가 아니라 **나중에 비동기로** 카메라 상태로 온다. 예전에는 이걸 안 봐서 실패 안내가
                // 거의 안 나가고, 45초 뒤 일반 워치독 문구만 나왔다.
                bound.cameraInfo.cameraState.observe(this) { state -> onCameraState(generation, state) }
                val maxZoom = bound.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                bound.cameraControl.setZoomRatio(minOf(2.0f, maxZoom))
                Log.d(TAG, "Camera bound (use cases: ${useCases.size})")
        } catch (e: Exception) {
            // ⚠️ 예전에는 로그만 찍고 끝이었다. 그러면 분석기가 아예 안 붙어서
            //    onTrafficLightDetected 가 한 번도 안 불리고, 미탐지 안내 타이머조차
            //    돌지 않는다. 사용자는 인트로 한 마디를 듣고 **영원히 침묵**을 겪는다.
            // (메인 스레드에서 동기로 불리므로 그 사이 세대가 바뀔 수 없다 — 한물간 바인딩은 위에서 걸렀다)
            handleCameraFailure("bind", e)
        } finally {
            cameraStarting = false
        }
    }

    /**
     * 카메라를 쓸 수 없게 됨 — 바인딩 예외, 또는 바인딩 뒤 비동기로 온 치명 오류.
     * 살아날 수 없는 상태라 감시를 끄고 한 번 분명히 알린다. (onResume 마다 다시 시도한다)
     */
    private fun handleCameraFailure(reason: String, e: Throwable?) {
        Log.e(TAG, "Camera failed ($reason)", e)
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        cameraProvider = null
        camera = null
        analysisEnabled = false
        stopLivenessWatchdog()   // 살아날 수 없는 상태 — 45초마다 울려도 의미 없다
        stopAimStaleWatch()
        motionGate?.stop()
        aiming.stop()
        cameraBindFailed = true
        // 카메라가 죽기 직전의 안내는 이제 "지난 정보"다. 안 그러면 실패 안내를 듣고 다시 듣기를 누른 사람에게
        // "방금 초록불로 바뀌었습니다. 건너세요"가 초록 진동과 함께 최신처럼 재생된다.
        announcementFromEarlierSession = true
        signalDecisionEngine.reset()
        setStatusText("카메라를 열 수 없음")
        // 인식이 멎은 채 화면만 무기한 켜 두면 배터리와 주머니 속 오작동만 남는다.
        // (꺼지기 전까지는 소리 줄이는 버튼을 꾹 눌러 다시 켤 수 있다 — onVolumeLongPress)
        setKeepScreenOn(false)
        // ⚠️ 소리가 안 들리는 사용자에게 vibrateWarning 을 쓰면 안 된다 — 점멸 경고와 **같은 패턴**이라
        //    촉각만 남은 사용자가 '카메라 고장'을 '신호 점멸'로 읽는다.
        //    실패는 TTS 초기화보다 먼저 날 수 있어 ttsUnavailable 이 아직 false 일 수 있으므로 ttsReady 로 본다.
        if (!ttsReady || volumeWarned) {
            vibrateFatal()          // 자체적으로 holdOff 를 건다
        } else {
            aiming.holdOff(VIBRATE_WARNING_HOLD_MS)
            vibrateWarning()
        }
        // 늦춰 둔 "신호등을 비춰 주세요" 가 이 실패 안내 뒤에 나가면 안 된다 —
        // 그게 이 세션의 마지막 말이 되어, 카메라가 죽었는데 잘 되는 줄 알게 된다.
        supersedePendingIntro()
        speakTts(crossingAware(cameraFailedMessage()))
    }

    /**
     * 카메라 실패 문구. 고온으로 기기가 카메라를 닫은 경우 "다른 앱이 쓰고 있나"는 틀린 원인이라
     * 사용자가 할 수 없는 일을 시킨다 — 발열 상태를 보고 나눈다.
     */
    private fun cameraFailedMessage(): String =
        if (thermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) CAMERA_HOT_MESSAGE else CAMERA_FAILED_MESSAGE

    /** 기기 발열 단계 (API 29 미만이면 판단 불가 = 0). */
    private fun thermalStatus(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return 0
        return try {
            pm.currentThermalStatus
        } catch (_: Exception) {
            0
        }
    }

    private fun onCameraState(generation: Int, state: CameraState) {
        if (generation != cameraGeneration || !analysisEnabled) return
        val error = state.error
        if (error == null) {
            // "다른 앱이 쓰고 있다"고 알린 뒤 카메라가 풀려 CameraX 가 스스로 다시 열었다 —
            // 예전에는 다시 켜졌다는 말이 없어, 사용자는 계속 안 되는 줄 알았다.
            if (state.type == CameraState.Type.OPEN && cameraInUseAnnounced) {
                // 다시 빼앗기면 그것도 알리도록 되돌린다. 건너는 중이면 "비춰 주세요"(조준 지시)는 빼고 접두를 붙인다.
                cameraInUseAnnounced = false
                val msg = if (inCrossingWindow()) crossingAware(CAMERA_RECOVERED_SHORT) else CAMERA_RECOVERED_MESSAGE
                speakTrafficLight(msg, interrupt = false)
            }
            return
        }
        if (error.type == CameraState.ErrorType.CRITICAL) {
            handleCameraFailure("state code=${error.code}", error.cause)
            return
        }
        // 회복 가능한 오류 — CameraX 가 카메라가 풀리면 스스로 다시 연다. 그 사이 무음이 되지 않게 한 번 알린다.
        val inUse = error.code == CameraState.ERROR_CAMERA_IN_USE ||
            error.code == CameraState.ERROR_MAX_CAMERAS_IN_USE
        if (inUse && !cameraInUseAnnounced) {
            cameraInUseAnnounced = true
            aiming.stop()
            speakTrafficLight(crossingAware(CAMERA_IN_USE_MESSAGE), interrupt = true)
        }
    }

    /**
     * 추론이 연달아 실패하고 있다 (분석기가 [INFERENCE_FAILURES_TO_ANNOUNCE] 전후로 알려 준다).
     *
     * 예전에는 실패를 "검출 0건"으로 위장해 넘겼다. 그러면 미탐지 안내("천천히 좌우로 움직여 주세요")가
     * 30초마다 나가며 그게 생존 신호로 세어져, 워치독이 영영 안 울렸다 — 사용자는 고장 난 앱의
     * 지시대로 계속 훑었다. 이제는 조용히 진동을 끄고, 몇 초 넘게 이어지면 오류라고 한 번 분명히 말한다.
     * 생존 신호로는 세지 않는다 — 계속 실패하면 워치독이 다시 짚는다.
     */
    private fun onInferenceFailing(consecutiveFailures: Int) {
        if (!analysisEnabled) return
        lastResultAtMs = SystemClock.elapsedRealtime()
        trafficLightDetector?.clearLastResult()
        aiming.stop()
        setIdleVisual()
        if (consecutiveFailures >= INFERENCE_FAILURES_TO_ANNOUNCE && !inferenceErrorAnnounced) {
            inferenceErrorAnnounced = true
            if (!ttsReady || volumeWarned) vibrateFatal()
            speakTrafficLight(crossingAware(INFERENCE_ERROR_MESSAGE), interrupt = true, countsAsLiveness = false)
        }
    }

    private fun stopCamera() {
        // 가장 먼저 끈다 — 이 뒤에 도착하는 늦은 분석 콜백을 전부 무시하기 위해.
        analysisEnabled = false
        stopLivenessWatchdog()
        stopAimStaleWatch()
        // 카메라가 한 번 멈추면(일시정지·백그라운드) 엔진도 리셋된다. 그 전에 들은 안내는
        // 다시 듣기 때 "방금 것"이 아니라 "조금 전 안내"로 재생돼야 한다.
        announcementFromEarlierSession = true
        // ⚠️ crossingUntilMs 는 지우지 않는다(필드 주석) — 엔진은 리셋돼도 사용자는 아직 차도 위일 수 있다.
        // 진행 중이던 비동기 시작을 무효화하고, 다음 startCamera 가 막히지 않게 푼다.
        cameraGeneration++
        cameraStarting = false
        // 모델이 열리길 기다리던 바인딩도 무효 — 열리면 detector 만 보관하고 바인딩은 하지 않는다.
        pendingProvider = null
        pendingPreviewView = null
        confirmVibrationHandler.removeCallbacksAndMessages(null)
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        cameraProvider = null
        camera = null
        if (::cameraPreviewContainer.isInitialized) cameraPreviewContainer.removeAllViews()
        signalDecisionEngine.reset()
        resetNoDetection()
        resetAimingState()
        // 화면을 떠나는데 진동이 계속 뛰면 안 된다.
        motionGate?.stop()
        if (::aiming.isInitialized) aiming.stop()
    }

    // ==================== 신호 검출 처리 ====================

    private fun onTrafficLightDetected(detections: List<TrafficLightDetection>) {
        // stopCamera 이후 도착한 늦은 콜백 — 진동·발화를 되살리면 안 된다.
        if (!analysisEnabled) return
        lastResultAtMs = SystemClock.elapsedRealtime()
        // 추론이 다시 결과를 낸다 = 오류에서 회복. 플래그가 남으면 나중에 멈추려고 꾹 눌렀을 때
        // 일시정지 대신 "카메라를 다시 켭니다"가 됐다(onVolumeLongPress).
        inferenceErrorAnnounced = false

        val stats = trafficLightDetector?.lastStats

        // 필드 디버깅용 — 매 추론의 peak 신뢰도(R/G)를 logcat 에 남긴다.
        if (BuildConfig.DEBUG && stats != null) {
            Log.d(
                "TL_DIAG",
                "raw=${detections.size} above=${stats.rawCandidatesAboveThreshold} " +
                    "peak=${"%.2f".format(stats.peakConfidence)} " +
                    "(R=${"%.2f".format(stats.peakConfRed)} G=${"%.2f".format(stats.peakConfGreen)}) " +
                    "th=${"%.2f".format(stats.confidenceThresholdUsed)} ms=${stats.inferenceMs}"
            )
        }

        if (detections.isEmpty()) {
            setIdleVisual()
            updateAiming()
            // 약한 후보(임계 근처)가 보이면 미탐지 안내를 잠시 억제 — 곧 잡힐 수 있음.
            //
            // ⚠️ 두 가지 함정을 막아야 한다.
            //  (1) 이 임계가 판정 임계(0.25)와 같으면 "임계 미만의 약한 후보"는 조건을
            //      영영 못 넘어서, 실제로는 횡단보도 무늬 바닥을 비출 때만 발동했다 —
            //      정확히 잘못된 경우에만 억제하고 있었던 셈이다. 그래서 판정 임계보다
            //      확실히 낮게 둔다.
            //  (2) 무기한 억제 금지. 약한 오탐(간판·차양)이 계속 흘러들면 "곧 잡힐 듯"만
            //      반복하며 음성 사다리가 영영 침묵한다. 상한을 넘기면 억제를 풀고
            //      사다리를 돌린다.
            if (stats != null && stats.peakConfidence >= NO_DET_SUPPRESS_PEAK_CONFIDENCE) {
                val nowMs = SystemClock.elapsedRealtime()
                if (noDetSuppressStartedAt == 0L) noDetSuppressStartedAt = nowMs
                if (nowMs - noDetSuppressStartedAt < NO_DET_SUPPRESS_MAX_MS) {
                    resetNoDetection()
                    return
                }
                // 상한 초과 — 억제를 풀고 아래의 handleNoDetection 으로 떨어진다.
            } else {
                noDetSuppressStartedAt = 0L
            }
            handleNoDetection()
            return
        }

        val now = SystemClock.elapsedRealtime()
        lastDetectionAtMs = now
        noDetSuppressStartedAt = 0L   // 진짜 검출이 나왔으니 억제 창을 새로 시작

        val rawSignals = detections.map {
            RawSignalDetection(it.classId, it.confidence, it.bbox.width, it.bbox.height)
        }
        val decision = signalDecisionEngine.decide(rawSignals, now)
        updateOverlay(decision)

        // 조준 진동을 언제 끄는가 — 검출 유무가 아니라 **엔진이 확정했는가**로 가른다.
        // 박스는 있는데 Silent 인 상태(너무 작다 / 확신 부족 / 안정성 프레임 대기)는
        // 사용자가 거의 다 맞춘 순간이다. 여기서 진동을 끊으면 가장 필요한 때 안내가 사라진다.
        when (decision) {
            is SignalDecision.Silent -> {
                resetNoDetection()
                updateAiming()
            }

            is SignalDecision.Repeat -> {
                resetNoDetection()
                lastValidatedAtMs = now
                livenessAlarmCount = 0
                aiming.stop()
                // ⚠️ 다시 듣기 기록을 이 한 단어로 덮어쓰지 않는다. 예전에는 "초록불입니다. 일단 멈춰서
                //    다음 신호를 기다리세요" 뒤 12초 반복이 기록을 "초록불"로 바꿔, 다시 듣기가 초록 진동 +
                //    "초록불"만 들려줬다 — 건너도 된다로 들린다. 같은 색을 다시 확인한 시각만 남긴다.
                if (decision.color == lastAnnouncedColor) lastColorConfirmedAtMs = now
                // 이미 켜져 있던 초록불(기다리세요)의 반복은 "초록불" 한 단어가 아니라 "기다리세요"부터 —
                // 앞부분만 듣고 출발하지 않게. (건너는 중일 수 있는 동안은 차도 위에서 기다리라고 하지 않는다)
                val waitingOnGreen = isWaitingOnGreen(decision.color)
                speakTrafficLight(
                    if (waitingOnGreen) "기다리세요, 초록불입니다." else repeatMessage(decision.color),
                    interrupt = false,
                )
                // 기다리는 중(빨간불·기다릴 초록)이면, 잘렸던 조작법을 이때 짧게 채운다.
                if (decision.color == SignalDecisionEngine.COLOR_RED || waitingOnGreen) speakOwedKeyHint()
            }

            is SignalDecision.Flicker -> {
                resetNoDetection()
                lastValidatedAtMs = now
                livenessAlarmCount = 0
                // 두 가지를 동시에 막는다.
                //  (1) 경고 패턴(약 0.9초)이 조준 진동에 덮어씌워지는 것.
                //  (2) 점멸 락아웃(엔진 6초) 동안 조준 진동이 되살아나는 것 —
                //      엔진은 점멸 시 확정색을 COLOR_NONE 으로 비우므로 updateAiming 의
                //      holdingSignal 이 false 가 되어, 막지 않으면 "멈춰서 기다리세요"를
                //      듣고 서 있는 6초 내내 손이 계속 울린다.
                aiming.holdOff(FLICKER_AIM_HOLD_MS)
                // 건너는 중일 수 있으면 "멈춰서"라고 하지 않는다(crossingUntilMs 주석).
                // ⚠️ 창을 여기서 끝내지 않는다 — 점멸·빨간불이 왔다고 사용자가 차도에서 사라지는 건 아니다.
                //    끝내면 바로 조준 진동·"휴대폰을 왼쪽으로"·"정지하세요"가 되살아났다.
                val flickerMsg = if (inCrossingWindow()) CROSSING_FLICKER_MESSAGE else FLICKER_MESSAGE
                // ⚠️ 다시 듣기용으로 반드시 기록한다. 안 하면 마지막 기록이 직전의
                //    "초록불로 바뀌었습니다… 건너세요" 로 남아, 경고를 놓친 사용자가 볼륨 키를 누르면
                //    점멸 중인데 **건너라는 안내와 초록 진동**이 '방금 것'처럼 재생된다.
                //    전환 종류는 null — 다시 듣기 때도 주의 진동이 나간다.
                rememberAnnouncement(flickerMsg, SignalDecisionEngine.COLOR_NONE, transition = null)
                speakTrafficLight(flickerMsg, interrupt = true)
                vibrateWarning()
            }

            is SignalDecision.Announce -> {
                resetNoDetection()
                lastValidatedAtMs = now
                livenessAlarmCount = 0
                aiming.holdOff(VIBRATE_SHORT_HOLD_MS)   // 판정 진동이 잘리지 않도록
                val crossing = inCrossingWindow()
                val transition = decision.transition
                // 건너기 창이 열린 **뒤에** 카메라가 다시 켜졌다(일시정지·전원 키 후). 아직 차도 위일 수도, 이미 건너
                // 다음 횡단보도 앞일 수도 있다 — 조용히 넘기면 새 횡단보도의 초록이 통째로 무음이 된다. 두 경우를 다 말한다.
                val restartedSinceCrossing = crossing && cameraSessionStartedAtMs > crossingUntilMs - CROSSING_WINDOW_MS
                if (restartedSinceCrossing && transition == SignalTransition.STATIC_GREEN) {
                    rememberAnnouncement(CROSSING_GREEN_REPLAY_MESSAGE, decision.color, transition = null)
                    // 창이 끝난 뒤 반복 안내가 "기다리세요, 초록불입니다"가 되게 — 전환을 못 본 초록이다.
                    lastAnnouncedTransition = SignalTransition.STATIC_GREEN
                    speakTrafficLight(CROSSING_GREEN_REPLAY_MESSAGE, interrupt = decision.interrupt)
                    vibrateConfirmed(null)   // 주의 = "음성을 꼭 들으세요"
                    return
                }
                if (crossing && transition == SignalTransition.STATIC_GREEN) {
                    // 건너는 중에 같은 초록을 다시 잡은 것(검출이 오래 끊겨 엔진이 처음부터 다시 셈).
                    // 차도 위에서 "기다리세요"라고 하면 안 된다 — 조용히 넘어간다.
                    // 다만 "전환을 못 본 초록"이라는 사실은 남긴다 — 창이 끝난 뒤 반복 안내가
                    // "초록불"이 아니라 "기다리세요, 초록불입니다"가 되게.
                    lastAnnouncedColor = decision.color
                    lastAnnouncedTransition = SignalTransition.STATIC_GREEN
                    lastColorConfirmedAtMs = now
                    Log.d(TAG, "TL static green during crossing window — muted")
                    return
                }
                val isRed = transition == SignalTransition.RED_NEW || transition == SignalTransition.GREEN_TO_RED
                val message = when (transition) {
                    // 창 안의 '처음 본 빨간불'(카메라 재시작 뒤 등)은 "바뀌었습니다"라고 하지 않는다 — 본 적 없는 전환이다.
                    SignalTransition.GREEN_TO_RED -> if (crossing) CROSSING_RED_MESSAGE else "빨간불입니다. 정지하세요."
                    SignalTransition.RED_NEW -> if (crossing) CROSSING_RED_NEW_MESSAGE else "빨간불입니다. 정지하세요."
                    SignalTransition.RED_TO_GREEN -> "방금 초록불로 바뀌었습니다. 안전을 확인하고 건너세요."
                    // "기다리세요"를 맨 앞에 — 앞부분 "초록불"만 듣고 출발하지 않게.
                    SignalTransition.STATIC_GREEN -> "기다리세요. 이미 켜져 있던 초록불입니다."
                }
                // 건너는 중의 빨간불은 "기다림"이 아니라 "주의"로 진동한다("멈추지 말고 끝까지").
                val vibrationKind: SignalTransition? = if (crossing && isRed) null else transition
                // 창은 빨강→초록을 봤을 때만 연다. 빨간불이 와도 닫지 않는다(점멸 분기 주석) — 끝은 시간과 stopCamera.
                if (transition == SignalTransition.RED_TO_GREEN) crossingUntilMs = now + CROSSING_WINDOW_MS
                if (transition == SignalTransition.RED_TO_GREEN) aiming.stop()
                rememberAnnouncement(message, decision.color, vibrationKind)
                speakTrafficLight(message, interrupt = decision.interrupt)
                // ⚠️ 전환(decision.vibrate)일 때만이 아니라 **모든 확정에서** 진동한다.
                //    예전에는 RED_NEW(횡단보도 앞에서 처음 만나는 빨간불)에 진동이 없었는데,
                //    음성이 안 들리는 상황에서는 그게 곧 "아무 일도 없었음"과 같다.
                vibrateConfirmed(vibrationKind)
                if (vibrationKind == SignalTransition.RED_NEW || vibrationKind == SignalTransition.GREEN_TO_RED ||
                    vibrationKind == SignalTransition.STATIC_GREEN
                ) {
                    speakOwedKeyHint()
                }
                Log.d(TAG, "TL announce: $message (conf=${decision.confidence})")
            }
        }
    }

    /**
     * 조준 진동을 갱신할지 끌지 **한 곳에서** 결정한다.
     *
     * 기준은 [SignalDecision.Silent] 의 이유 코드가 아니라 **엔진이 색을 확정하고 있는가**다.
     * 이유 코드로 거르면 반드시 샌다 — 엔진은 확정 이후에도 프레임 품질에 따라
     * ALL_LOW_CONFIDENCE / ALL_TOO_SMALL 을 돌려주고, 검출이 0건인 프레임은 아예
     * 이 분기까지 오지도 않는다. 그 틈마다 조준 진동이 되살아나면
     * "빨간불입니다" 를 듣고 서 있는 내내 손이 계속 울린다.
     *
     * 다만 확정색만 보면 반대 문제가 생긴다 — 신호를 다 건넌 뒤 카메라를 돌려도
     * 확정색이 남아 있어 조준 도움이 영영 안 돌아온다. 그래서 "최근에 실제로 검출이
     * 있었는가"([AIM_RESUME_AFTER_MS])를 함께 본다.
     */
    private fun updateAiming() {
        // 건너는 중에는 조준 진동을 내지 않는다 — 처진 카메라가 바닥의 횡단보도 무늬를 겨눠
        // '찾았다' 2연타("멈춰요, 여기예요")가 차도 한가운데서 울렸다.
        // 전화가 울리는 동안도 끈다 — 반복 조준 진동이 벨 진동을 끊어 전화를 놓치거나(안드로이드 12~13),
        // 벨 진동에 묻혀 의미가 없다(14 이상). 색 확정 진동·음성은 그대로 나간다.
        if (inCrossingWindow() || isRinging()) {
            aiming.stop()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val holdingSignal =
            signalDecisionEngine.confirmedColor != SignalDecisionEngine.COLOR_NONE &&
                // 검출이 아예 끊겼나 (카메라를 돌렸다)
                now - lastDetectionAtMs < AIM_RESUME_AFTER_MS &&
                // 박스는 계속 오는데 전부 엔진에 걸러지는 상태(약한 오탐이 흘러드는 경우)에
                // 확정색이 남아 조준 도움이 영영 안 돌아오는 것을 막는 상한.
                now - lastValidatedAtMs < AIM_HOLD_MAX_MS
        if (holdingSignal) {
            aiming.stop()
        } else {
            aiming.update(trafficLightDetector?.lastAimTargetCenterX)
        }
    }

    private fun resetNoDetection() {
        noDetStartedAt = 0L
        noDetLastSpeechAt = 0L
        noDetSafetyDone = false
    }

    /** 카메라 시작·정지 시 조준 판단용 타임스탬프를 비운다. */
    private fun resetAimingState() {
        lastDetectionAtMs = 0L
        lastValidatedAtMs = 0L
        noDetSuppressStartedAt = 0L
    }

    // ==================== 생존 신호 (워치독) ====================

    /**
     * **이 앱의 가장 큰 결함은 생존 신호가 없다는 것이었다.**
     *
     * 시각장애인 사용자는 "정상인데 신호등이 안 보여서 조용한 것"과 "죽어서 조용한 것"을
     * 구분할 방법이 전혀 없었다. 그리고 죽는 경로가 생각보다 많다:
     *
     *   · 카메라 바인딩 실패 — catch 에서 로그만 찍고 끝 (분석기가 아예 안 붙는다)
     *   · 추론이 매 프레임 예외 — Analyzer 가 삼키고 onDetection 을 안 부른다
     *     → 미탐지 안내 타이머 자체가 돌지 않는다
     *   · 엔진이 계속 Silent — resetNoDetection() 이 불려서 미탐지 사다리가 0 으로 유지된다
     *     (박스가 하나라도 잡히는 한 영원히)
     *   · 발열로 추론이 1초를 넘김 — maxObservationGapMs 가 매 프레임 스트릭을 리셋해
     *     확정이 구조적으로 불가능해진다
     *
     * 전부 증상이 같다: **조용하고, 조준 진동은 계속 울린다.** 사용자는 "잘 겨누고 있으니
     * 곧 알려주겠지" 하고 횡단보도 앞에서 몇 분을 기다린다.
     *
     * 그래서 원인을 하나하나 막는 대신 **결과를 감시한다.** 실행 중인데
     * [LIVENESS_TIMEOUT_MS] 동안 들리거나 느껴지는 출력이 하나도 없었다면, 그 사실 자체를 알린다.
     */
    private fun livenessTick() {
        if (!analysisEnabled) return
        checkAudibleVolume()   // 사용자가 걷는 중에 볼륨을 내릴 수도 있다 — 매번 본다
        checkDeviceHealth()
        val now = SystemClock.elapsedRealtime()
        // 건너는 중일 수 있는 동안은 일부러 조용하다(조준·미탐지 안내를 끔). 그 시간을 "멈춤"으로 세면
        // 창이 끝나자마자 "신호를 확인하지 못하고 있습니다"가 울린다 — 창이 끝난 시각부터 센다.
        val lastOutputAt = maxOf(lastAudibleAtMs, crossingUntilMs)
        if (lastAudibleAtMs > 0L && now - lastOutputAt >= LIVENESS_TIMEOUT_MS) {
            lastAudibleAtMs = now
            livenessAlarmCount++
            // 몇 번을 짚어도 회복이 없다(카메라 점유·추론 실패가 이어짐) — 화면 켠 채 45초마다 경고만 끝없이 내지 말고
            // 알리고 멈춘다. 일시정지 재알림과 다시 켜는 법은 그대로다.
            if (livenessAlarmCount > LIVENESS_ALARMS_BEFORE_PAUSE) {
                pauseRecognition(LIVENESS_PAUSE_MESSAGE)
                return
            }
            aiming.holdOff(VIBRATE_WARNING_HOLD_MS)
            // ⚠️ 음성이 없는 상태에서 vibrateWarning 을 쓰면 안 된다 —
            //    그건 "신호가 깜빡입니다. 멈추세요" 와 **똑같은 패턴**이라,
            //    촉각만 남은 사용자는 '앱이 멈췄다'를 '점멸 신호다'로 읽는다.
            //    최악의 경우 건너는 중에 그 진동을 받고 멈춰 선다.
            //    볼륨 0(volumeWarned)도 같다 — 음성이 안 들리면 촉각만 남는다.
            if (ttsUnavailable || volumeWarned) vibrateFatal() else vibrateWarning()
            speakTrafficLight(crossingAware(LIVENESS_MESSAGE), interrupt = false)
        }
        livenessHandler.postDelayed(::livenessTick, LIVENESS_CHECK_INTERVAL_MS)
    }

    /**
     * 접근성 볼륨이 0 이면 알린다 — **음성으로는 알릴 수 없는 문제**라서 진동으로.
     *
     * `tts.speak()` 는 볼륨이 0 이어도 SUCCESS 를 돌려주고 onDone 도 정상적으로 온다.
     * 앱 입장에서는 완벽히 말한 것처럼 보이는데 사용자는 한 마디도 못 듣는다.
     * 그런데 조준 진동과 색 확정 진동은 멀쩡히 동작하므로, 사용자는
     * "인식은 되고 있다"는 신호만 받고 정작 **색은 영영 모른다.**
     *
     * 볼륨 조절도 함정이다 — TalkBack 이 켜져 있으면 볼륨 키가 접근성 스트림을 향하지만,
     * 미디어가 재생 중일 때만 미디어 스트림으로 바뀐다. 우리 발화는 안 들리니
     * 그 창이 언제 열리는지도 알 수 없다. 그래서 "볼륨을 올려 달라"는 말 자체를
     * 들리는 채널로 전달할 방법이 없고, 남는 건 진동뿐이다.
     */
    private fun checkAudibleVolume() {
        val am = audioManager ?: return
        val stream = AudioManager.STREAM_ACCESSIBILITY
        val level = try {
            am.getStreamVolume(stream)
        } catch (_: Exception) {
            return   // 일부 기기에서 접근성 스트림을 노출하지 않는다 — 판단 불가면 조용히 넘어간다
        }
        // 볼륨이 있어도 **수화부로 통화 중**이면 안 들린다 — 통화 중에는 시스템이 접근성 음성을 통화 경로로
        // 보내므로, 가슴 앞에 든 폰의 귀 쪽 스피커에서 작게 나온다. 볼륨 0 과 똑같이 진동으로만 알린다.
        val callOnEarpiece = isCallOnEarpiece(am)
        val muted = level <= 0 || callOnEarpiece
        if (muted && !volumeWarned) {
            volumeWarned = true
            mutedByCall = callOnEarpiece
            Log.w(TAG, if (callOnEarpiece) "수화부로 통화 중 — 음성 안내가 들리지 않는다" else "접근성 볼륨 0 — 음성 안내가 들리지 않는다")
            vibrateFatal()
            // 혹시 다른 스트림으로라도 나갈 수 있으면 나가라고 시도는 해 본다. (통화 중이면 말해도 귀 쪽으로만 간다)
            if (!callOnEarpiece) speakTts("소리가 꺼져 있어 음성 안내를 들을 수 없습니다. 소리를 키워 주세요.")
        } else if (!muted) {
            if (volumeWarned && mutedByCall) {
                speakTrafficLight(crossingAware(CALL_ENDED_MESSAGE), interrupt = false, countsAsLiveness = false)
            }
            volumeWarned = false
            mutedByCall = false
        }
    }

    /**
     * 통화(일반·인터넷 전화) 중이고 소리가 귀 쪽 스피커로 나가는가. 권한이 필요 없는 AudioManager 만 쓴다
     * (전화 상태 API 는 READ_PHONE_STATE 권한이 필요하다). 이어폰·스피커폰이면 들리므로 false.
     */
    private fun isCallOnEarpiece(am: AudioManager): Boolean {
        val mode = try {
            am.mode
        } catch (_: Exception) {
            return false
        }
        if (mode != AudioManager.MODE_IN_CALL && mode != AudioManager.MODE_IN_COMMUNICATION) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            } else {
                @Suppress("DEPRECATION")
                !am.isSpeakerphoneOn && !am.isBluetoothScoOn && !am.isWiredHeadsetOn
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 전화가 울리는 중인가 (권한 불필요). */
    private fun isRinging(): Boolean = try {
        audioManager?.mode == AudioManager.MODE_RINGTONE
    } catch (_: Exception) {
        false
    }

    /**
     * 배터리·발열·절전 모드 — 한 번씩만, 건너는 중이 아닐 때 알린다(덜 급한 말은 미룬다).
     *  - 배터리: 길 한가운데서 폰이 꺼지는 것이 보행 중 가장 큰 도구 상실이다.
     *  - 발열: 추론이 느려지면 확정이 늦어지고, 심하면 기기가 카메라를 닫는다.
     *  - 절전 모드: 켜진 접근성 서비스가 없으면 진동이 꺼질 수 있다(a11yAudioAttributes 주석).
     */
    private fun checkDeviceHealth() {
        if (inCrossingWindow()) return
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (bm != null) {
            val level = try {
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            } catch (_: Exception) {
                -1
            }
            if (level in 1..100 && !bm.isCharging) {
                if (level <= BATTERY_CRITICAL_PERCENT && !batteryCriticalWarned) {
                    batteryCriticalWarned = true
                    batteryLowWarned = true
                    speakTrafficLight("배터리가 ${level}퍼센트 남았습니다. 곧 휴대폰이 꺼질 수 있습니다.", interrupt = false, countsAsLiveness = false)
                    return
                }
                if (level <= BATTERY_LOW_PERCENT && !batteryLowWarned) {
                    batteryLowWarned = true
                    speakTrafficLight("배터리가 ${level}퍼센트 남았습니다.", interrupt = false, countsAsLiveness = false)
                    return
                }
            }
        }
        if (!heatWarned && thermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) {
            heatWarned = true
            speakTrafficLight(HEAT_MESSAGE, interrupt = false, countsAsLiveness = false)
            return
        }
        if (!powerSaveWarned) {
            powerSaveWarned = true   // 한 번만 본다 — 켜져 있지 않으면 이 화면에서는 다시 묻지 않는다
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val a11y = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            if (pm?.isPowerSaveMode == true && a11y?.isEnabled != true) {
                speakTrafficLight(POWER_SAVE_MESSAGE, interrupt = false, countsAsLiveness = false)
            }
        }
    }

    /**
     * 분석 결과가 [AIM_STALE_MS] 넘게 안 오면 조준 진동을 끈다.
     *
     * 조준 진동은 **결과가 올 때만** 바뀐다. 결과가 끊기면(카메라 오류·발열로 분석 정지·분석기 멈춤)
     * 마지막 박자가 워치독(45초)까지 계속 울려 "잘 겨누고 있다"는 거짓 신호가 된다.
     * (빠르게 휘두르는 동안은 MotionGate 가 이미 끈다)
     */
    private val aimStaleTick = object : Runnable {
        override fun run() {
            if (!analysisEnabled) return
            if (SystemClock.elapsedRealtime() - lastResultAtMs > AIM_STALE_MS) aiming.stop()
            aimStaleHandler.postDelayed(this, AIM_STALE_CHECK_MS)
        }
    }

    private fun startAimStaleWatch() {
        aimStaleHandler.removeCallbacks(aimStaleTick)
        aimStaleHandler.postDelayed(aimStaleTick, AIM_STALE_CHECK_MS)
    }

    private fun stopAimStaleWatch() {
        aimStaleHandler.removeCallbacks(aimStaleTick)
    }

    private fun startLivenessWatchdog() {
        lastAudibleAtMs = SystemClock.elapsedRealtime()
        livenessHandler.removeCallbacksAndMessages(null)
        livenessHandler.postDelayed(::livenessTick, LIVENESS_CHECK_INTERVAL_MS)
    }

    private fun stopLivenessWatchdog() {
        livenessHandler.removeCallbacksAndMessages(null)
    }

    /** 신호등을 못 잡을 때 — 조용히 대기했다가 같은 문장을 간격 두고 반복. */
    private fun handleNoDetection() {
        val now = SystemClock.elapsedRealtime()
        // 건너는 중에는 "몸을 왼쪽으로" 같은 말을 하지 않는다 — 차도 위에서 걷는 방향 지시로 들린다.
        // 전화가 울리는 동안에도 벨소리 위로 조준 안내를 얹지 않는다.
        if (now < detectStartHoldUntil || inCrossingWindow() || isRinging()) {
            resetNoDetection()
            return
        }
        if (noDetStartedAt == 0L) noDetStartedAt = now
        val elapsed = now - noDetStartedAt
        if (elapsed < NO_DET_FIRST_MS) return

        // 오래 아무것도 못 봄 = 대개 폰을 화면 켠 채 주머니에 넣었거나 엎어 둔 것. 그대로 두면 카메라·추론이
        // 계속 돌며 30초마다 "몸을 천천히 좌우로"가 끝없이 나오고(진짜 경고까지 흘려듣게 된다), 발열·배터리도 크다.
        // 알리고 멈춘다 — 다시 시작하는 법을 말해 주고, 일시정지 재알림도 그대로 돈다.
        if (elapsed >= NO_DET_AUTO_PAUSE_MS) {
            Log.d(TAG, "no detection for ${elapsed}ms — auto pause")
            pauseRecognition(AUTO_PAUSE_MESSAGE)
            return
        }

        if (elapsed >= NO_DET_SAFETY_MS && !noDetSafetyDone) {
            noDetSafetyDone = true
            noDetLastSpeechAt = now
            speakTrafficLight("신호등이 잘 잡히지 않습니다. 주변 소리에 주의하세요.", interrupt = false)
            return
        }
        if (noDetLastSpeechAt == 0L || now - noDetLastSpeechAt >= NO_DET_REPEAT_MS) {
            noDetLastSpeechAt = now
            speakTrafficLight(crosswalkGuidanceMessage(), interrupt = false)
        }
    }

    /**
     * 신호등이 안 잡힐 때의 방향 안내.
     *
     * 2026-09 — 좌/우/위만 남기고 전부 잘라냈다. "정렬이 얼마나 맞았는가"는 이제
     * [AimingFeedback] 진동이 실시간으로 알려주므로, 음성은 진동이 못 담는 **방향**
     * 한 단어만 담당한다. 진동 모터가 하나뿐이라 좌/우를 촉각으로는 구분할 수 없다.
     */
    private fun crosswalkGuidanceMessage(): String {
        // lastAimTargetCenterX 가 이미 (신호등 후보 → 횡단보도) 폴백을 품고 있다.
        val cx = trafficLightDetector?.lastAimTargetCenterX ?: -1f
        // 동사까지 말한다 — "휴대폰을 왼쪽으로"는 몸을 돌리라는 건지 걸어가라는 건지, "조금 위로"는 들어 올리라는
        // 건지 기울이라는 건지 모호했다. 자세는 첫 안내와 같게 "가슴 앞에 세로로 들고 **몸 전체로** 돌리기".
        // 위쪽: 화면이 내 쪽을 보게 세워 들었으니, 윗부분을 내 쪽으로 기울이면 뒤 카메라가 위를 본다.
        return when {
            cx < 0f -> "몸을 천천히 좌우로 돌려 주세요"
            cx < 0.40f -> "몸을 왼쪽으로 조금 돌려 주세요"
            cx > 0.60f -> "몸을 오른쪽으로 조금 돌려 주세요"
            else -> "휴대폰 윗부분을 내 쪽으로 살짝 기울여 주세요"
        }
    }

    /**
     * heartbeat(같은 색 12초 지속) 재안내 문구.
     * 첫 [SignalDecision.Announce] 는 "정지하세요" 같은 행동까지 온전히 말하지만,
     * 반복은 이미 들은 말이므로 색 한 단어로 줄인다. — 발화량 절감의 핵심.
     */
    private fun repeatMessage(classId: Int): String = when (classId) {
        0 -> "빨간불"
        1 -> "초록불"
        else -> "신호 확인"
    }

    // ==================== 화면 오버레이 ====================

    private fun setIdleVisual() {
        overlayColorView.setBackgroundColor(0x00000000)
        setStatusText("신호등을\n비춰 주세요")
    }

    /**
     * 같은 글씨면 다시 넣지 않는다. 검출 없는 프레임마다(초당 3번) 같은 문구를 넣으면, autoSize 가 걸린
     * 고정 높이 TextView 가 매번 글자 크기를 다시 찾고 레이아웃을 새로 한다.
     */
    private fun setStatusText(text: String) {
        if (overlayStatusText.text.toString() != text) overlayStatusText.text = text
    }

    private fun updateOverlay(decision: SignalDecision) {
        val red = 0x66D50000.toInt()
        val green = 0x6600C853.toInt()
        val crossing = inCrossingWindow()
        // 화면 글씨는 색 + **해야 할 행동**. 예전엔 색 한 단어뿐이라, 이미 켜져 있던 초록불(음성: 기다리세요)이
        // 화면에는 초록 바탕의 "초록불"로만 보여 저시력자에게 "건너도 된다"로 읽혔다.
        // 점멸은 "빨간불"로 보여 줘 없는 사실을 말했다.
        when (decision) {
            is SignalDecision.Announce -> when (decision.transition) {
                SignalTransition.RED_TO_GREEN -> showOverlay(green, "초록불")
                SignalTransition.STATIC_GREEN -> showOverlay(green, if (crossing) "초록불" else "초록불\n기다리세요")
                else -> showOverlay(red, if (crossing) "빨간불" else "빨간불\n정지")
            }
            // 같은 색 반복 — 글씨(행동)는 그대로 두고 색만 맞춘다. "기다리세요"가 사라지지 않게.
            // 단 건너는 중 조용히 넘긴 초록이 창이 끝나 "기다려야 하는 초록"이 됐으면 글씨도 맞춘다.
            is SignalDecision.Repeat -> when {
                isWaitingOnGreen(decision.color) -> showOverlay(green, "초록불\n기다리세요")
                else -> overlayColorView.setBackgroundColor(
                    if (decision.color == SignalDecisionEngine.COLOR_RED) red else green
                )
            }
            is SignalDecision.Flicker -> showOverlay(red, if (crossing) "깜빡임" else "깜빡임\n멈추세요")
            is SignalDecision.Silent -> { /* 확신 부족/안정성 대기 — 현재 화면 유지 */ }
        }
    }

    /** 지금 보이는 초록이 "기다려야 하는 초록"(전환을 못 봄)인가. 건너는 중일 수 있는 동안은 아니라고 본다. */
    private fun isWaitingOnGreen(color: Int): Boolean =
        color == SignalDecisionEngine.COLOR_GREEN &&
            lastAnnouncedTransition == SignalTransition.STATIC_GREEN &&
            !inCrossingWindow()

    private fun showOverlay(color: Int, text: String) {
        overlayColorView.setBackgroundColor(color)
        setStatusText(text)
    }

    // ==================== TTS / 진동 ====================

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            // ⚠️ 예전에는 이 분기가 아예 없었다. TTS 엔진 초기화가 실패하면 ttsReady 가
            //    영원히 false 라서 speakTts/speakTrafficLight 가 매번 조기 반환하고,
            //    사용자는 **처음부터 끝까지 아무 소리도 못 듣는다.** 게다가 실패했다는
            //    사실조차 음성으로 알릴 수 없으니 진동으로 알려야 한다.
            Log.e(TAG, "TTS init 실패 (status=$status)")
            ttsUnavailable = true
            vibrateFatal()
            requestPermissionIfAwaitingTts()
            return
        }

        // ⚠️ setLanguage 의 반환값을 버리면 안 된다. 한국어 음성 데이터가 없는 기기에서는
        //    LANG_MISSING_DATA 를 돌려주고 언어는 바뀌지 않는다. 그 상태로 speak() 를 부르면
        //    한글은 대부분 **소리 없이 지나간다** — 예외도, 오류도 없다.
        val lang = tts.setLanguage(Locale.KOREAN)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "한국어 음성 없음 (setLanguage=$lang)")
            ttsUnavailable = true
            vibrateFatal()
            requestPermissionIfAwaitingTts()
            return
        }

        // ⭐ 이 앱의 음성을 **접근성 채널**로 보낸다.
        //
        // 기본값은 STREAM_MUSIC(USAGE_MEDIA) 인데, 시각장애인 사용자는 미디어 볼륨을 0 으로
        // 두고 TalkBack 이 쓰는 접근성 볼륨만 올려 두는 경우가 흔하다. 그러면 이 앱은
        // **완전히 무음인데 speak() 는 SUCCESS 를 돌려주고 진동은 정상 작동한다.**
        // 사용자는 "인식됐다"는 진동만 받고 색은 영영 못 듣는다. 가장 위험한 실패다.
        // 접근성 채널로 보내면, 사용자가 확실히 켜 둔 볼륨을 타게 된다.
        if (tts.setAudioAttributes(a11yAudioAttributes) != TextToSpeech.SUCCESS) {
            // 실패해도 말은 나온다 — 다만 미디어 볼륨을 타므로 볼륨 0 함정이 되살아난다.
            Log.w(TAG, "TTS 오디오 속성 적용 실패 — 미디어 볼륨을 탈 수 있음")
        }
        // 권한 창·설정 화면은 안내가 끝난 뒤에 띄운다 ([speakThen]).
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                // 조작법을 끝까지 들었다 — 짧은 재안내는 필요 없다. (중단·오류면 남겨 둔다)
                if (utteranceId == UTTERANCE_INTRO) introHintOwed = false
                finished(utteranceId)
            }
            @Suppress("OVERRIDE_DEPRECATION")   // 추상 메서드라 구현은 해야 한다
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
            private fun finished(id: String?) {
                if (id == UTTERANCE_INTRO) introInFlight = false
                if (id == UTTERANCE_SYSTEM_ACTION) runOnUiThread { runPendingSystemAction() }
                if (id != UTTERANCE_BT_WAKE) scheduleFocusRelease()
            }
        })
        ttsReady = true

        if (firstPermissionPromptPending) {
            // 화면을 이미 떠났으면 백그라운드에서 예고를 말하지 않는다 — 돌아오면 onResume 이 한다.
            if (isResumedNow()) promptFirstPermission()
            return
        }

        if (!introSpoken) {
            introSpoken = true
            // TTS 준비 전에 이미 실패했다면, 밝은 인트로 대신 그 사실을 말한다.
            // 이 분기가 없으면 카메라가 안 켜진 상태로 "비춰 주세요"만 듣고 영원히 침묵한다.
            // ⚠️ 카메라 바인딩 실패도 반드시 여기 들어가야 한다. 빠지면 TTS 가 준비된 순간
            //    "신호등을 카메라에 비춰 주세요" 라고 말하고 — 카메라는 죽어 있고 워치독도
            //    멈춰 있으니 **그게 그 세션의 마지막 출력**이 된다. 사용자는 잘 되고 있다고 믿는다.
            introPending = true
            scheduleIntroFlush()
        }
    }

    /**
     * 첫 안내 문장 — **내보내는 순간의 상태로** 고른다.
     *
     * 예전에는 onInit 시점에 문장을 정해 두고 TalkBack 때문에 1.5초 늦춰 내보냈는데, 그 사이에
     * 카메라 바인딩·모델 로드가 실패하면 실패 안내 **다음에** "신호등을 비춰 주세요"가 나가
     * 그게 마지막 말이 됐다(카메라는 죽었는데 잘 되는 줄 앎). 상태로 고르면 그럴 수 없다.
     *
     * TTS 준비 전에 이미 실패했다면 밝은 인트로 대신 그 사실을 말한다.
     */
    private fun introMessage(): String = when {
        // ⚠️ 일시정지 복원(회전·다크모드·프로세스 복원)이 맨 앞이어야 한다.
        //    빠지면 카메라도 엔진도 꺼진 상태에서 "신호등을 카메라에 비춰 주세요"를 말하고,
        //    정정은 30초 뒤에야 온다.
        userPaused -> "일시정지 상태입니다. 소리 줄이는 버튼을 꾹 누르면 다시 시작합니다."
        permissionDenied -> if (cameraPermissionBlocked) PERMISSION_BLOCKED_MESSAGE else PERMISSION_DENIED_MESSAGE
        cameraBindFailed -> cameraFailedMessage()
        modelLoadFailed -> MODEL_LOAD_FAILED_MESSAGE
        // 조작법은 여기서 한 번만 알린다 — 물리 키는 알려주지 않으면 발견할 방법이 없다.
        else -> NORMAL_INTRO_MESSAGE
    }

    /**
     * 첫 안내를 내보낼 시점을 잡는다.
     *
     *  - TalkBack 켜짐: 1.5초 늦춘다. 이 화면에 들어오는 순간 TalkBack 이 창 제목("신호등 카메라")을
     *    읽는데, 앱 음성도 같은 접근성 채널이라 겹치면 둘 다 안 들린다.
     *  - TalkBack 꺼짐: 바로. 다만 화면이 앞에 있을 때만 — 권한 창에 가려졌거나 이미 떠난 화면
     *    (빠르게 두 번 눌러 겹쳐 뜬 화면 등)에서 안내가 새어 나오지 않게 [flushPendingIntro] 가 거른다.
     *
     * 신호 안내(speakTrafficLight)는 늦추지 않는다 — 안전 정보다.
     */
    private fun scheduleIntroFlush() {
        introHandler.removeCallbacksAndMessages(null)
        val delay = if (Speaker.isScreenReaderOn(this)) INTRO_DELAY_WITH_SCREEN_READER_MS else 0L
        introHandler.postDelayed(::flushPendingIntro, delay)
    }

    /**
     * 늦춘 첫 안내를 내보낸다. 화면이 가려져 있으면(첫 실행 권한 창) 남겨 두고 onResume 이 다시 건다 —
     * 그냥 버리면 첫 실행 사용자는 조작법(볼륨 키)을 영영 못 듣는다.
     */
    private fun flushPendingIntro() {
        if (!introPending) return
        if (firstPermissionPromptPending) return   // 권한 창 예고가 먼저 — 조작법은 창이 닫힌 뒤에
        if (isFinishing || isDestroyed) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        introPending = false
        lastAudibleAtMs = SystemClock.elapsedRealtime()
        if (!ttsReady) return
        // 따로 표시해 둔다 — 듣는 도중 화면을 떠나면 onPause 가 알아채고, 돌아왔을 때 다시 들려준다.
        introInFlight = true
        val message = introMessage()
        // 신호등을 이미 향한 채 들어오면 약 1초 뒤 "빨간불입니다"가 끼어들어 조작법이 잘린다 — 그러면
        // 다음에 기다리는 상태가 됐을 때 조작법만 짧게 다시 알린다([speakOwedKeyHint]). 끝까지 들으면 onDone 이 지운다.
        introHintOwed = message == NORMAL_INTRO_MESSAGE
        // TalkBack 은 방금 창 제목("신호등 카메라")을 읽었다 — 같은 말로 시작하지 않는다.
        val spoken = if (message == NORMAL_INTRO_MESSAGE && Speaker.isScreenReaderOn(this)) INTRO_BODY else message
        ttsSay(spoken, TextToSpeech.QUEUE_ADD, UTTERANCE_INTRO)
    }

    /** 잘린 조작법을 짧게 다시. 기다리는 상태(빨간불·기다릴 초록)에서만 — 건너는 중에는 말을 보태지 않는다. */
    private fun speakOwedKeyHint() {
        if (!introHintOwed || introInFlight || inCrossingWindow()) return
        introHintOwed = false
        speakTrafficLight(KEY_HINT, interrupt = false, countsAsLiveness = false)
    }

    /**
     * 모든 발화의 한 창구.
     *  - 오디오 포커스: 말하는 동안 음악·다른 앱 소리를 줄인다(SpeechFocus 주석). 예전엔 블루투스로 음악을
     *    듣는 중이면 "빨간불"이 음악에 묻혔다.
     *  - 블루투스 이어폰: 첫 음절이 잘려 "빨간불"이 "…불"로 들리지 않게 짧은 무음으로 먼저 깨운다.
     */
    private fun ttsSay(text: String, queueMode: Int, utteranceId: String) {
        focusHandler.removeCallbacks(releaseFocusIfIdle)
        speechFocus.acquire()
        var mode = queueMode
        if (AudioRouting.isBluetoothOutput(this)) {
            tts.playSilentUtterance(AudioRouting.BLUETOOTH_WAKE_MS, mode, UTTERANCE_BT_WAKE)
            mode = TextToSpeech.QUEUE_ADD
        }
        // 엔진이 거절하면 완료 콜백이 오지 않는다 — 포커스를 쥔 채 남지 않게 반납을 예약한다.
        if (tts.speak(text, mode, null, utteranceId) == TextToSpeech.ERROR) scheduleFocusRelease()
    }

    /** 말이 끝나고 잠깐 다음 말이 없으면 다른 앱 소리를 돌려준다(문장 사이마다 음악이 출렁이지 않게). */
    private fun scheduleFocusRelease() {
        focusHandler.removeCallbacks(releaseFocusIfIdle)
        focusHandler.postDelayed(releaseFocusIfIdle, FOCUS_RELEASE_DELAY_MS)
    }

    /**
     * 첫 안내를 취소한다 — 그보다 중요한 말(실패·권한·일시정지)이 먼저 나갔을 때.
     * 그 말 뒤에 "비춰 주세요"가 따라붙으면 방금 들은 경고를 덮어 버린다.
     */
    private fun supersedePendingIntro() {
        introPending = false
        introHandler.removeCallbacksAndMessages(null)
    }

    private fun speakTts(message: String) {
        // ⚠️ 스탬프는 ttsReady 가드 **앞**에 찍는다.
        //    워치독이 재는 건 "파이프라인이 살아 있는가"이지 "소리가 났는가"가 아니다.
        //    가드 뒤에 찍으면, TTS 가 없는 기기에서는 인식이 완벽히 돌아가도
        //    스탬프가 영영 갱신되지 않아 45초마다 치명 진동이 울린다 — 멀쩡한데 고장 신호를 준다.
        lastAudibleAtMs = SystemClock.elapsedRealtime()
        if (!ttsReady) return
        ttsSay(message, TextToSpeech.QUEUE_ADD, message.hashCode().toString())
    }

    /** 신호 안내를 기록해 둔다 — 볼륨 다운 짧게 누르면 이걸 다시 들려준다. */
    private fun rememberAnnouncement(message: String, color: Int, transition: SignalTransition?) {
        val now = SystemClock.elapsedRealtime()
        lastAnnouncement = message
        lastAnnouncedColor = color
        lastAnnouncedTransition = transition
        lastAnnouncementAtMs = now
        lastColorConfirmedAtMs = now
        announcementFromEarlierSession = false
    }

    /**
     * @param countsAsLiveness 이 발화가 "인식 파이프라인이 살아 있다"는 증거인가.
     *   기본 true. **센서만으로 나오는 발화는 false 여야 한다** — 예: "천천히 돌려 주세요"는
     *   자이로만 있으면 나오는 말이라, 이걸로 워치독 스탬프를 갱신하면 계속 휘두르는 사용자는
     *   (모션 쿨다운 15초 < 워치독 45초) 스탬프가 15초마다 리필돼 인식이 완전히 죽어 있어도
     *   워치독이 영영 안 운다. 정확히 워치독이 지켜야 할 사용자가 빠져나간다.
     */
    private fun speakTrafficLight(message: String, interrupt: Boolean, countsAsLiveness: Boolean = true) {
        if (countsAsLiveness) lastAudibleAtMs = SystemClock.elapsedRealtime()
        if (!ttsReady) return
        ttsSay(
            message,
            if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            if (interrupt) "tl_urgent" else "tl",
        )
    }

    // ──────────────── 진동 ────────────────
    //
    // ⚠️ 모든 진동에 접근성 용도를 붙인다. 속성이 없으면 USAGE_UNKNOWN 으로 취급돼
    //    **시스템 진동 설정에서 통째로 억제된다.** 위 TTS 문제와 겹치면 사용자에게 남는 채널이 하나도 없다.
    //    단 절전 모드는 접근성 용도도 면제하지 않는다(a11yAudioAttributes 주석) — checkDeviceHealth 가 알린다.

    private fun vibrate(effect: VibrationEffect) {
        // ::vibrator 가드 — onInit 이 생성자에서 동기로 불리는 경로를 이중으로 막는다.
        if (!::vibrator.isInitialized || !vibrator.hasVibrator()) return
        try {
            vibrator.vibrate(effect, a11yAudioAttributes)
        } catch (e: Exception) {
            Log.w(TAG, "진동 실패", e)
        }
    }

    /**
     * 신호 확정 진동 — **색이 아니라 "해도 되는 행동"으로 나눈다.**
     *
     * 소리가 안 들리는 순간(볼륨 0·이어폰 끊김·차 소리)에는 진동이 유일한 채널이다.
     * 예전에는 색 기준이라 "방금 초록불로 바뀜(건너도 됨)"과 "이미 켜져 있던 초록불(기다리세요)"이
     * 똑같이 짧게 세 번이었다 — 진동만 느낀 사람에게 '건너세요' 규칙이 지켜지지 않았다.
     *
     *  - 가도 됨 = 짧게 세 번: **빨강→초록 전환을 직접 봤을 때만.**
     *  - 기다림 = 길게 한 번: 빨간불, 그리고 전환을 못 본 초록불.
     *  - 주의 = 길게·짧게·길게: 점멸, 건너는 중의 빨간불, 오래된 안내 다시 듣기, 오류.
     *
     * 세 번이 아니라 두 번이면 AimingFeedback 의 "찾았다"(45·70·45)와 닮는다. 아래 waveform 과
     * 이 설명은 항상 함께 고칠 것.
     */
    private fun vibrateFor(transition: SignalTransition?) {
        when (transition) {
            SignalTransition.RED_TO_GREEN -> vibrateGo()
            null -> vibrateWarning()
            else -> vibrateWait()
        }
    }

    /**
     * 확정 진동을 조준 진동과 **떼어서** 울린다. 가운데 조준(20ms 톡이 150ms 간격)이 끊기자마자 짧은 펄스
     * 세 번이 이어 붙으면 손에서는 하나로 느껴져, "가도 됨"이 "아직 조준 중"에 묻혔다.
     * 음성은 바로 나가고 진동만 잠깐 늦는다. 그 사이 카메라가 멈추면(일시정지·화면 이탈) 울리지 않는다.
     */
    private fun vibrateConfirmed(kind: SignalTransition?) {
        aiming.holdOff(CONFIRM_VIBRATION_GAP_MS + VIBRATE_SHORT_HOLD_MS)
        confirmVibrationHandler.removeCallbacksAndMessages(null)
        confirmVibrationHandler.postDelayed({
            if (analysisEnabled) vibrateFor(kind)
        }, CONFIRM_VIBRATION_GAP_MS)
    }

    /** 가도 됨 = 같은 길이 세 번. 조준 톡(20ms)과 확실히 다르게 110ms 로 길게 친다. */
    private fun vibrateGo() {
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 110, 100, 110, 100, 110), -1))
    }

    private fun vibrateWait() {
        vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /**
     * 주의 = 길게·짧게·길게. 예전 패턴(150ms 세 번)은 "가도 됨"(70ms 세 번)과 박자가 같아
     * 주머니·장갑 너머로는 거의 구별되지 않았다. 리듬 자체를 다르게 한다.
     */
    private fun vibrateWarning() {
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 120, 80, 120, 300), -1))
    }

    /**
     * 음성을 아예 쓸 수 없을 때의 치명 오류 신호. 길게 세 번.
     * 1.9초짜리라 소나에 덮이기 쉬워서 보호 구간을 스스로 건다.
     */
    private fun vibrateFatal() {
        if (::aiming.isInitialized) aiming.holdOff(VIBRATE_FATAL_HOLD_MS)
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
    }

    // ==================== 볼륨 키 조작 ====================
    //
    // 이 앱에는 조작 수단이 하나도 없었다. 방금 안내를 지나가는 버스 소리에 놓치면
    // heartbeat(12초)를 기다리는 수밖에 없었고, 잠시 멈추려면 전원 버튼을 눌러야 했는데
    // 그건 화면이 꺼지는 것 말고는 아무 확인도 주지 않아서 앱이 죽은 것과 구별되지 않았다.
    //
    // ⚠️ **볼륨 업은 건드리지 않는다.** 접근성 볼륨이 0 이면 앱 음성이 하나도 안 들리는데,
    //    두 키를 다 명령으로 먹으면 그 상태에서 빠져나갈 방법이 사라진다.
    //    볼륨을 못 내리는 것보다 못 올리는 것이 비교할 수 없이 위험하므로,
    //    명령은 볼륨 **다운**에만 싣는다.
    //
    // 터치 대신 물리 키를 쓰는 이유: 화면을 못 보는 사람도 손끝으로 찾을 수 있고,
    // 폰을 어떻게 쥐든 위치가 같으며, TalkBack 이 터치 제스처를 가로채는 것과 무관하다.
    // (TalkBack 은 볼륨 업+다운 **동시** 누름만 자기 단축키로 쓰고 단독 누름은 통과시킨다)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // 롱프레스는 시스템 문턱(0.4~0.5초) 대신 직접 1초를 잰다(volumeKeyHandler 주석).
            if (event.repeatCount == 0) {
                volumeLongPressHandled = false
                volumeDownSeenAt = event.downTime
                volumeKeyHandler.removeCallbacks(volumeLongPressRunnable)
                volumeKeyHandler.postDelayed(volumeLongPressRunnable, VolumeDownRepeat.LONG_PRESS_MS)
            }
            return true   // 소비 — 볼륨은 안 내려간다
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volumeKeyHandler.removeCallbacks(volumeLongPressRunnable)
            // DOWN 을 직접 받은 누름만 — 벨소리를 끄려고 누른 키(시스템이 DOWN 을 가져감)는 무시한다.
            // 취소된 누름(누르는 도중 전화가 옴, TalkBack 의 두 키 동시 누름 단축키)도 다시 듣기로 치지 않는다.
            val ownPress = volumeDownSeenAt != NO_KEY_PRESS && volumeDownSeenAt == event.downTime
            if (ownPress && !volumeLongPressHandled && !event.isCanceled) repeatLastAnnouncement()
            volumeDownSeenAt = NO_KEY_PRESS
            volumeLongPressHandled = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** 1초 동안 꾹 누름. 실패 상태(카메라·모델·추론 오류)면 다시 켜기, 아니면 일시정지 ↔ 재개. */
    private fun onVolumeLongPress() {
        if (volumeDownSeenAt == NO_KEY_PRESS) return
        volumeLongPressHandled = true
        // 워치독이 "꾹 눌러 다시 켜 주세요"라고 한 뒤(livenessAlarmCount > 0)도 다시 켜기다 — 문구와 동작을 맞춘다.
        val failing = cameraBindFailed || modelLoadFailed || inferenceErrorAnnounced || livenessAlarmCount > 0
        if (!userPaused && hasCameraPermission() && failing) {
            restartRecognition()
            return
        }
        togglePause()
    }

    /**
     * 실패한 인식을 다시 켠다. 예전 실패 안내는 "뒤로 가기를 누른 뒤 다시 켜 주세요"였는데,
     * 뒤로 가기가 어디 있는지 모르는 사람은 따라 할 수 없었다 — 이미 손끝에 있는 키로 바로 다시 켠다.
     * 실패 플래그를 내려 두어, 또 실패하면 그 사실을 다시 말한다.
     */
    private fun restartRecognition() {
        supersedePendingIntro()
        speakTrafficLight("카메라를 다시 켭니다.", interrupt = true, countsAsLiveness = false)
        aiming.holdOff(VIBRATE_SHORT_HOLD_MS)
        vibratePauseAck(paused = false)
        stopCamera()
        cameraBindFailed = false
        modelLoadFailed = false
        startCamera()
    }

    /**
     * 인식을 멈춘다 — 사용자의 꾹 누르기, 또는 오래 아무것도 못 본 자동 일시정지.
     * 화면 켜 두기는 재알림이 남아 있는 동안 유지한다([resumePausedReminder]).
     */
    private fun pauseRecognition(message: String) {
        userPaused = true
        stopCamera()          // analysisEnabled=false, 워치독·모션게이트·조준진동 모두 정지
        setIdleVisual()       // 마지막 빨강/초록 화면이 남아 동행자를 오도하지 않게
        // ⚠️ 끼어들어 말한다. QUEUE_ADD 로 붙이면, "건너세요" 가 재생 중일 때
        //    멈췄는데도 건너라는 안내가 몇 초 더 이어진다.
        speakTrafficLight(crossingAware(message), interrupt = true)
        vibratePauseAck(paused = true)
        startPausedReminder()
    }

    /** 일시정지 ↔ 재개. 음성이 죽어 있어도 알 수 있도록 진동 모양을 다르게 준다. */
    private fun togglePause() {
        // 사용자가 직접 조작했다 — 늦춰 둔 첫 안내("비춰 주세요")가 그 뒤에 끼어들면 안 된다.
        supersedePendingIntro()

        // 카메라 권한이 없으면 멈출 것도 다시 시작할 것도 없다. 예전에는 이때도 "일시정지했습니다"
        // 라고 말해서, 아무것도 안 돌고 있는데 멈췄다는 거짓 확인을 줬다. 대신 이 키를
        // **권한을 얻는 길**로 쓴다 (다시 묻기, 또는 "다시 묻지 않음"이면 설정 화면 열기).
        // userPaused 는 건드리지 않는다 — 권한을 얻고 나면 onRequestPermissionsResult/onResume 이 정리한다.
        if (!hasCameraPermission()) {
            requestOrOpenCameraSettings()
            return
        }

        if (!userPaused) {
            pauseRecognition("일시정지했습니다. 소리 줄이는 버튼을 꾹 누르면 다시 시작합니다.")
            return
        }

        // 재개 — (권한이 그 사이 취소된 경우는 위에서 이미 걸렀다)
        userPaused = false
        setKeepScreenOn(true)
        stopPausedReminder()
        speakTrafficLight("다시 시작합니다.", interrupt = true)
        aiming.holdOff(VIBRATE_SHORT_HOLD_MS)
        vibratePauseAck(paused = false)
        startCamera()
    }

    /**
     * 카메라 권한을 얻는 길. 볼륨 아래 길게 누르기로 들어온다.
     *  - 아직 물어볼 수 있으면: 권한 창을 다시 띄운다.
     *  - "다시 묻지 않음"(또는 두 번 거부해 시스템이 자동 거부)이면: 앱 설정 화면을 연다.
     *    예전에는 이 경우 같은 안내만 반복돼 빠져나갈 길이 없었다.
     */
    private fun requestOrOpenCameraSettings() {
        if (pendingSystemAction != null) return   // 안내가 끝나길 기다리는 중 — 연타 무시
        permissionDenied = true
        // 길게 세 번(치명)은 "휴대폰이 말을 못 한다" 전용이다 — 말을 할 수 있으면 "음성을 들으세요"(주의)로.
        if (!ttsReady || volumeWarned) vibrateFatal() else vibrateWarning()
        if (cameraPermissionBlocked) {
            val help = if (Speaker.isScreenReaderOn(this)) "" else SETTINGS_HELP
            speakThen(OPENING_SETTINGS_MESSAGE + help) {
                sentToSettings = AppSettings.open(this)
            }
        } else {
            speakThen("카메라 권한을 다시 묻습니다. ${permissionButtonHint()}") { requestCameraPermission() }
        }
    }

    /**
     * 권한 창에서 눌러야 할 버튼을 **실제 이름과 위치로** 말한다.
     * 예전에는 "허용을 눌러 주세요"였는데, 안드로이드 11 이상의 카메라 권한 창에는 "허용"이라는 버튼이 없고
     * "앱 사용 중에만 허용 / 이번만 허용 / 허용 안함" 세 개가 세로로 있다(맨 위가 우리가 원하는 것).
     * 안드로이드 10 이하는 "거부 / 허용"이 가로로 놓인다.
     */
    private fun permissionButtonHint(): String {
        // 권한 창 버튼은 화면 가운데쯤 떠 있어 손끝으로 찾을 기준점이 없고, 잘못 누르면 '허용 안 함'이 된다 —
        // 설정 화면 안내처럼 도움을 청하는 길도 함께 말한다.
        val button = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "앱 사용 중에만 허용" else "허용"
        val where = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "버튼 중 맨 위의" else "오른쪽 아래의"
        return "$where '$button'을 눌러 주세요. 혼자 찾기 어려우면 주변 분께 '$button'을 눌러 달라고 부탁해 주세요."
    }

    /**
     * 권한 결과·설정 복귀 직후의 안내. TalkBack 이 켜져 있으면 그 순간 TalkBack 이 창 제목을 읽으므로
     * 첫 안내처럼 조금 늦춘다 — 겹치면 "길게 누르면…" 같은 복구 방법이 묻힌다.
     */
    private fun speakSettled(message: String) {
        settledSpeechHandler.removeCallbacksAndMessages(null)
        if (!Speaker.isScreenReaderOn(this)) {
            speakTrafficLight(message, interrupt = true)
            return
        }
        lastAudibleAtMs = SystemClock.elapsedRealtime()
        settledPending = true
        settledSpeechHandler.postDelayed({
            settledPending = false
            // 끊지 않고 이어서 — 그 사이 카메라가 켜져 나온 신호 안내("빨간불입니다…")를 자르지 않게.
            if (!isFinishing && !isDestroyed) speakTrafficLight(message, interrupt = false)
        }, SETTLED_SPEECH_DELAY_MS)
    }

    /** [speakSettled] 가 늦춰 둔 안내가 아직 안 나갔다 — 그 사이 화면을 떠나면 돌아와서 상태 안내로 대신한다. */
    private var settledPending = false

    /**
     * 말을 **끝까지** 들려준 뒤에 [action](권한 창·설정 화면 열기)을 한다.
     *
     * 예전에는 말하자마자 화면을 띄워서, onPause 의 tts.stop() 이 안내를 첫 마디에서 잘랐다 —
     * "설정 화면을 엽니다"까지만 들리고 "허용한 뒤 돌아와 주세요"는 영영 안 들렸다.
     * TTS 가 없거나 완료 콜백이 안 오면 [SYSTEM_ACTION_MAX_WAIT_MS] 뒤에 그냥 한다.
     */
    private fun speakThen(message: String, action: () -> Unit) {
        pendingSystemAction = action
        systemActionHandler.removeCallbacksAndMessages(null)
        lastAudibleAtMs = SystemClock.elapsedRealtime()
        if (!ttsReady) {
            runPendingSystemAction()
            return
        }
        ttsSay(message, TextToSpeech.QUEUE_FLUSH, UTTERANCE_SYSTEM_ACTION)
        // 상한은 문장 길이에 맞춘다 — 고정 10초면 TalkBack 없이 듣는 설정 안내(약 80음절, 13~15초)가
        // 끝나기 전에 설정이 열려, onPause 가 "뒤로 가기를 누르면 돌아옵니다"를 잘랐다.
        val maxWait = (message.length * SYSTEM_ACTION_MS_PER_CHAR)
            .coerceIn(SYSTEM_ACTION_MAX_WAIT_MS, SYSTEM_ACTION_WAIT_CAP_MS)
        systemActionHandler.postDelayed(::runPendingSystemAction, maxWait)
    }

    private fun runPendingSystemAction() {
        systemActionHandler.removeCallbacksAndMessages(null)
        val action = pendingSystemAction ?: return
        pendingSystemAction = null
        // 기다리는 사이 사용자가 화면을 떠났으면 아무것도 띄우지 않는다.
        if (isFinishing || isDestroyed) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        action()
    }

    /**
     * 일시정지 중임을 몇 번 다시 알린다.
     *
     * ⚠️ 롱프레스 문턱을 1초로 늘렸어도, 손으로 더듬다가 **의도치 않게** 멈출 수 있다.
     * 그런데 일시정지 상태에서는 워치독도 볼륨 점검도 멈추므로, 사고로 멈춘 줄 모르면
     * 그대로 영구 침묵이다 — 이 앱이 없애려던 바로 그 상태.
     *
     * 다만 일부러 멈춘 사람에게 영원히 잔소리하면 안 되므로 [PAUSED_REMINDER_MAX] 번만 한다.
     */
    private fun pausedTick() {
        if (!userPaused) return
        if (pausedReminderCount >= PAUSED_REMINDER_MAX) {
            setKeepScreenOn(false)
            return
        }
        pausedReminderCount++
        vibratePauseAck(paused = true)
        speakTts("일시정지 중입니다. 소리 줄이는 버튼을 꾹 누르면 다시 시작합니다.")
        if (pausedReminderCount >= PAUSED_REMINDER_MAX) {
            // 마지막 알림 — 말이 끝날 즈음 화면을 놓아준다(바로 놓으면 화면이 꺼지며 onPause 가 말을 자른다).
            livenessHandler.postDelayed({ if (userPaused) setKeepScreenOn(false) }, PAUSED_SCREEN_RELEASE_MS)
            return
        }
        livenessHandler.postDelayed(::pausedTick, PAUSED_REMINDER_MS)
    }

    /** 새 일시정지 — 알림 횟수를 초기화하고 시작. */
    private fun startPausedReminder() {
        pausedReminderCount = 0
        resumePausedReminder()
    }

    /** 이미 세던 횟수를 **유지한 채** 알림만 다시 건다 (백그라운드 왕복 복구용). */
    private fun resumePausedReminder() {
        livenessHandler.removeCallbacksAndMessages(null)
        if (pausedReminderCount >= PAUSED_REMINDER_MAX) {
            setKeepScreenOn(false)
            return
        }
        // ⚠️ 알림이 남은 동안은 화면을 켜 둔다. 예전에는 멈추자마자 놓아서, 시스템 화면 꺼짐 시간(15~30초)이
        //    첫 알림(30초)보다 먼저 와 onPause 가 알림을 통째로 지웠다 — 안전망이 한 번도 안 울렸다.
        //    첫 알림도 화면 꺼짐보다 이르게 당긴다.
        setKeepScreenOn(true)
        val delay = if (pausedReminderCount == 0) PAUSED_REMINDER_FIRST_MS else PAUSED_REMINDER_MS
        livenessHandler.postDelayed(::pausedTick, delay)
    }

    private fun stopPausedReminder() {
        livenessHandler.removeCallbacksAndMessages(null)
    }

    /**
     * 마지막 안내를 다시 들려준다.
     *
     * ⚠️ 오래된 안내를 그대로 반복하면 위험하다 — 그 사이에 신호가 바뀌었을 수 있다.
     * 그래서 [STALE_ANNOUNCEMENT_MS] 가 지났으면 "조금 전 안내"임을 먼저 밝힌다.
     * 색 진동도 함께 재생해, 음성이 안 들리는 상황에서도 색은 전달되게 한다.
     */
    private fun repeatLastAnnouncement() {
        // 권한 창 예고를 말하는 중이면 무시한다 — 다시 듣기가 예고를 끊으면 그 순간 권한 창이 바로 떴다
        // (예고의 완료 콜백이 끊김으로 와서). 카메라가 돌지도 않는데 "아직 안내한 신호가 없습니다"도 틀린 말이다.
        if (pendingSystemAction != null || firstPermissionPromptPending) return
        val last = lastAnnouncement
        // ⚠️ holdOff 없이 울리면 진행 중인 소나 파형을 덮어쓰는데,
        //    AimingFeedback 의 currentTier 는 그대로라 티어가 바뀌기 전까지 다시 안 걸린다.
        // ⚠️ 다시 듣기는 생존 신호로 세지 않는다(countsAsLiveness=false) — 사용자가 누른 것에 대한
        //    대답일 뿐, 인식이 살아 있다는 증거가 아니다.
        if (last == null) {
            aiming.holdOff(VIBRATE_SHORT_HOLD_MS)
            // ⚠️ 일시정지 확인음을 재사용하면 안 된다 — 첫 확정 전에는 이 분기가 흔한데,
            //    사용자는 그 진동을 "멈췄다"로 배웠기 때문에 멀쩡히 도는 앱을 멈춘 줄 알고
            //    되살리려다 **진짜로** 멈추게 된다.
            vibrateNothingYet()
            // 일시정지·권한 없음·카메라 실패면 그 상태부터 말한다. "아직 안내한 신호가 없습니다"만 하면
            // 인식이 돌고 있다고 오해한다.
            val state = introMessage()
            // 조작법을 놓친 사람이 누르는 것이 대개 이때다 — 사실만 말하지 말고 조작법을 다시 들려준다.
            val message = if (state == NORMAL_INTRO_MESSAGE) "아직 안내한 신호가 없습니다. $INTRO_BODY" else state
            speakTrafficLight(message, interrupt = true, countsAsLiveness = false)
            return
        }

        val now = SystemClock.elapsedRealtime()
        val ageMs = now - lastAnnouncementAtMs
        val sameSession = !userPaused && !announcementFromEarlierSession
        val fresh = sameSession && ageMs < STALE_ANNOUNCEMENT_MS
        // 처음 안내는 오래됐어도, 같은 색을 방금(반복 안내로) 다시 확인했는가.
        val colorStillConfirmed = sameSession &&
            (lastAnnouncedColor == SignalDecisionEngine.COLOR_RED || lastAnnouncedColor == SignalDecisionEngine.COLOR_GREEN) &&
            signalDecisionEngine.confirmedColor == lastAnnouncedColor &&
            now - lastColorConfirmedAtMs < STALE_ANNOUNCEMENT_MS

        aiming.holdOff(VIBRATE_WARNING_HOLD_MS)
        when {
            fresh -> {
                // 처음 안내와 같은 진동 — "가도 됨"은 전환을 직접 본 경우뿐(vibrateFor 주석).
                vibrateFor(lastAnnouncedTransition)
                speakTrafficLight(last, interrupt = true, countsAsLiveness = false)
            }
            colorStillConfirmed -> {
                // 원문을 그대로 읽지 않는다 — "방금 초록불로 바뀌었습니다. 건너세요"는 이제 '방금'이 아니다.
                // 바뀐 시점을 모르는 초록과 똑같이(기다리라고) 안내한다. "기다리세요"를 맨 앞에.
                // 단 "건너세요" 뒤 건너는 중일 수 있는 동안이면, 차도 위에서 기다리라고 하지 않는다.
                val crossing = inCrossingWindow()
                val isRed = lastAnnouncedColor == SignalDecisionEngine.COLOR_RED
                val message = when {
                    isRed && crossing -> CROSSING_RED_REPLAY_MESSAGE
                    isRed -> "지금도 빨간불입니다. 정지하세요."
                    crossing -> CROSSING_GREEN_REPLAY_MESSAGE
                    else -> "기다리세요. 초록불이 켜진 지 시간이 지났습니다."
                }
                if (isRed && crossing) vibrateWarning() else vibrateWait()
                speakTrafficLight(message, interrupt = true, countsAsLiveness = false)
            }
            else -> {
                // ⚠️ 오래된 안내에는 **색 진동도, 행동 지시도 쓰지 않는다.**
                //    음성이 죽은 사용자에게 색 진동은 "지금 막 확정됐다"는 뜻이고,
                //    몇 분 전 "건너세요"를 다시 읽으면 지금 건너라는 말로 들린다.
                vibrateWarning()
                speakTrafficLight(staleReplayMessage(ageMs), interrupt = true, countsAsLiveness = false)
            }
        }
    }

    /** 오래된 안내 다시 듣기 — 언제 무엇을 봤는지만 말하고, 지금 무엇을 하라고는 하지 않는다. */
    private fun staleReplayMessage(ageMs: Long): String {
        val whenText = if (ageMs >= 60_000L) "1분도 더 전에" else "약 ${(ageMs / 1000).coerceAtLeast(1)}초 전에"
        val what = when (lastAnnouncedColor) {
            SignalDecisionEngine.COLOR_RED -> "확인한 신호는 빨간불이었습니다."
            SignalDecisionEngine.COLOR_GREEN -> "확인한 신호는 초록불이었습니다."
            else -> "신호가 깜빡였습니다."
        }
        val now = when {
            userPaused -> "일시정지 중이라 지금은 신호를 보고 있지 않습니다. 소리 줄이는 버튼을 꾹 누르면 다시 시작합니다."
            // 실패 상태면 "아직 확인 못 함"(= 인식이 돌고 있음)이 아니라 실패와 복구 방법을 말한다.
            cameraBindFailed -> cameraFailedMessage()
            modelLoadFailed -> MODEL_LOAD_FAILED_MESSAGE
            else -> "지금 신호는 아직 확인하지 못했습니다."
        }
        return "$whenText $what $now"
    }

    /**
     * 일시정지·재개 확인 진동.
     *
     * ⚠️ 펄스 **개수**로 구분하면 안 된다 — 2연타는 AimingFeedback 의 "찾았다"(45·70·45)와
     * 너무 닮아서, 장갑 끼고 한 손으로 쥔 상태에서는 "재개했다"와 "여기 겨눠라"가 구별되지 않는다.
     * 그래서 길이가 확실히 다른 **한 번**짜리로 나눈다.
     */
    private fun vibratePauseAck(paused: Boolean) {
        val effect = if (paused) {
            // 긴 펄스 두 번. 긴 펄스 계열은 **개수로** 구분한다 —
            // 1번=빨간불, 2번=일시정지, 3번=치명 오류. 앞부분이 같아도 세면 갈린다.
            VibrationEffect.createWaveform(longArrayOf(0, 400, 150, 400), -1)
        } else {
            // 재개는 짧게 한 번. "멈춤=길다 / 감=짧다" 로, 빨강·초록 규칙과 결이 같다.
            VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrate(effect)
    }

    /** "다시 들려줄 게 아직 없다" — 이 어휘에서 가장 가벼운 신호. 다른 어떤 것과도 안 겹친다. */
    private fun vibrateNothingYet() {
        vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ==================== 라이프사이클 ====================

    override fun onResume() {
        super.onResume()
        // 권한 창 예고를 듣다가(또는 TTS 준비 전에) 화면을 떠났다 돌아옴 — 창을 아직 못 띄웠으니 예고부터 다시.
        // TTS 를 쓸 수 없게 됐으면 예고 없이 바로 띄운다.
        if (firstPermissionPromptPending && pendingSystemAction == null) {
            if (ttsUnavailable) {
                requestCameraPermission()
                return
            }
            if (ttsReady) {
                promptFirstPermission()
                return
            }
        }
        // 권한 대화상자 등에 가려 못 나간 첫 안내가 있으면, 창 제목 낭독이 끝날 만큼 기다렸다 다시 건다.
        if (introPending) scheduleIntroFlush()
        // 설정에서 권한을 켜고 돌아오는 경로는 onRequestPermissionsResult 를 거치지 않는다.
        // 예전에는 조용히 카메라만 켜져서 9초 가까이 아무 말이 없었다 — 허용됐다는 것과
        // 할 일을 알려 준다. 권한을 얻으러 일시정지 상태에서 갔다 왔다면 재개로 정리한다.
        val returnedFromSettings = sentToSettings
        sentToSettings = false
        if (permissionDenied && hasCameraPermission()) {
            permissionDenied = false
            cameraPermissionBlocked = false
            if (userPaused) {
                userPaused = false
                setKeepScreenOn(true)
                stopPausedReminder()
            }
            supersedePendingIntro()
            speakSettled(PERMISSION_GRANTED_MESSAGE)
        } else if (returnedFromSettings && !hasCameraPermission()) {
            // 설정에서 허용하지 않고 돌아옴 — 예전에는 아무 분기도 안 타서 완전히 무음이었다.
            supersedePendingIntro()
            speakSettled(STILL_NO_PERMISSION_MESSAGE)
        }
        // ⚠️ 사용자가 직접 멈춰 둔 것이면 되살리지 않는다. 그러지 않으면 알림창을 내렸다
        //    올리는 것만으로 일시정지가 풀려서, 멈춰 둔 줄 알고 주머니에 넣은 폰이
        //    다시 말하기 시작한다.
        if (userPaused) {
            // ⚠️ 여기서 알림을 되살리지 않으면, 일시정지 중 **백그라운드에 한 번만 다녀와도**
            //    안전망이 통째로 사라진다. onPause → stopCamera → stopLivenessWatchdog 가
            //    같은 핸들러를 비우기 때문이다. 하필 영문 모르고 멈춘 사용자가 제일 먼저 하는 행동이
            //    "화면을 확인한다 / 앱을 전환한다" 라서, 정확히 그때 그물이 찢어졌다.
            //    남은 횟수는 유지한다 — 백그라운드 왕복으로 무한 잔소리를 재충전하면 안 된다.
            resumeCueOwed = false
            resumePausedReminder()
            return
        }
        // 전원 키·통화·앱 전환으로 멈췄던 인식이 다시 켜진다 — 예전에는 조용히 켜져, 신호등이 안 보이면
        // 첫 소리까지 9초 가까이 무음이었다(멈춘 건지 도는 건지 알 수 없음). 첫 안내가 다시 나갈 거면 그걸로 충분하다.
        if (resumeCueOwed) {
            resumeCueOwed = false
            if (!introPending && hasCameraPermission() && !permissionDenied) {
                vibratePauseAck(paused = false)
                speakSettled(crossingAware(CAMERA_RESUMED_MESSAGE))
            }
        }
        if (cameraProvider == null && hasCameraPermission()) startCamera()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // ⚠️ 이게 없으면 다크모드 전환·글꼴 크기 변경·프로세스 복원으로 Activity 가 다시 만들어질 때
        //    일시정지가 풀려서, 멈춰 둔 줄 알고 주머니에 넣은 폰이 혼자 말하기 시작한다.
        outState.putBoolean(STATE_USER_PAUSED, userPaused)
        outState.putInt(STATE_PAUSED_REMINDER_COUNT, pausedReminderCount)
        // 설정 화면에 가 있는 동안 프로세스가 정리돼도, 돌아왔을 때 "허용 안 하고 돌아옴"을 알아채고
        // 다음 롱프레스가 (즉시 거부될) 권한 창 대신 설정 화면을 열게.
        outState.putBoolean(STATE_SENT_TO_SETTINGS, sentToSettings)
        outState.putBoolean(STATE_CAMERA_PERMISSION_BLOCKED, cameraPermissionBlocked)
        // 건너는 중에 화면이 다시 만들어져도(다크모드 전환 등) 차도 위에서 "정지하세요"가 나오지 않게.
        outState.putLong(STATE_CROSSING_UNTIL, crossingUntilMs)
    }

    override fun onPause() {
        super.onPause()
        // 키를 뗀 이벤트를 못 받고 백그라운드로 갈 수 있다. 플래그가 남으면 다음 짧은 누름이 먹힌다.
        volumeLongPressHandled = false
        volumeKeyHandler.removeCallbacks(volumeLongPressRunnable)
        volumeDownSeenAt = NO_KEY_PRESS
        // 인식이 돌고 있었나 (모델을 여는 중 포함) — stopCamera 가 내리기 전에 본다.
        val wasRecognizing = analysisEnabled || cameraStarting
        // 모델 실패는 돌아와서 다시 시도한다 — 또 실패하면 다시 알려야 한다(화면이 꺼졌다 켜진 사람은 앞 안내를 모른다).
        // 지금은 실패 후 감시를 다 끄므로, 이 플래그가 남으면 재시도 실패가 완전히 무음이었다.
        modelLoadFailed = false
        // 늦춘 첫 안내가 화면을 떠난 뒤에 나가지 않게 (introPending 은 남겨 onResume 에서 다시 건다)
        introHandler.removeCallbacksAndMessages(null)
        // 안내가 끝나길 기다리던 권한 창·설정 화면 열기는 취소 — 사용자가 이미 다른 데로 갔다.
        pendingSystemAction = null
        systemActionHandler.removeCallbacksAndMessages(null)
        stopCamera()
        // 큐에 남은 발화를 비운다. 그러지 않으면 사용자가 폰을 주머니에 넣은 뒤에
        // "빨간불입니다. 정지하세요." 가 뒤늦게 나갈 수 있다 — 맥락 없는 안전 지시는
        // 아무 안내도 없는 것보다 위험하다.
        // 첫 안내가 도중에 끊기는 거라면 돌아왔을 때 다시 들려준다(onResume 이 introPending 을 본다).
        if (introInFlight) {
            introInFlight = false
            introPending = true
        }
        settledSpeechHandler.removeCallbacksAndMessages(null)
        if (settledPending) {
            // 늦춰 둔 권한 안내가 못 나갔다 — 돌아오면 첫 안내 경로가 지금 상태(권한 없음/허용됨)로 다시 고른다.
            settledPending = false
            introPending = true
        }
        if (::tts.isInitialized) tts.stop()
        // 줄여 두었던 음악·다른 앱 소리를 바로 돌려준다.
        focusHandler.removeCallbacks(releaseFocusIfIdle)
        speechFocus.release()

        if (wasRecognizing && !userPaused && !isFinishing) {
            resumeCueOwed = true
            // 전원 키(볼륨 키 바로 옆이라 스치기 쉽다)로 화면이 꺼졌다 — 말이 중간에 끊기기만 하고 멈췄다는 확인이
            // 없었다. 짧게 사실만 알린다(행동 지시가 아니라 큐에 남은 옛 안전 지시와는 다르다).
            // 진동은 쓰지 않는다 — 화면이 꺼지면 시스템이 진동을 취소한다.
            if (ttsReady && !isScreenInteractive()) {
                ttsSay(crossingAware(SCREEN_OFF_MESSAGE), TextToSpeech.QUEUE_FLUSH, UTTERANCE_SCREEN_OFF)
            }
        }
    }

    private fun isScreenInteractive(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true

    override fun onDestroy() {
        super.onDestroy()
        volumeKeyHandler.removeCallbacksAndMessages(null)
        confirmVibrationHandler.removeCallbacksAndMessages(null)
        // detectorHandler 는 비우지 않는다 — 여는 중이던 모델이 끝나면 onDetectorLoaded 가 그걸 닫아야 한다.
        introHandler.removeCallbacksAndMessages(null)
        systemActionHandler.removeCallbacksAndMessages(null)
        settledSpeechHandler.removeCallbacksAndMessages(null)
        focusHandler.removeCallbacks(releaseFocusIfIdle)
        speechFocus.release()
        pendingSystemAction = null
        stopCamera()          // 여기서 motionGate.stop() / aiming.stop() 이 함께 불린다

        // ⚠️ shutdown() 은 진행 중인 작업을 기다리지 않는다. 분석 스레드가
        // interpreter.run() 안에 있는데 close() 로 네이티브 메모리를 해제하면
        // SIGSEGV 로 죽는다 (Kotlin catch 로는 못 잡는 네이티브 크래시).
        var analysisDrained = true
        analysisExecutor?.let { ex ->
            ex.shutdown()
            // 모델을 여는 중이면 기다리지 않는다 — 그때는 detector 가 없어 추론도 없고(닫을 것도 없고),
            // 다 열리면 onDetectorLoaded 가 isDestroyed 를 보고 닫는다. 기다리면 회전·다크모드 전환 때
            // 메인 스레드가 최대 1.5초 멈췄다.
            if (!detectorLoading) {
                analysisDrained = try {
                    ex.awaitTermination(EXECUTOR_SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
            }
        }
        analysisExecutor = null

        // 타임아웃이면 추론이 아직 돌고 있다는 뜻이다. 그 상태에서 close() 하면 죽는다.
        // 누수(인터프리터 하나)가 네이티브 크래시보다 낫다 — 어차피 프로세스 종료 중이다.
        if (analysisDrained) {
            trafficLightDetector?.close()
        } else {
            Log.w(TAG, "분석 스레드가 ${EXECUTOR_SHUTDOWN_WAIT_MS}ms 안에 안 끝남 — interpreter.close() 생략")
        }
        trafficLightDetector = null
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    companion object {
        private const val TAG = "SafeWalkNav"

        /**
         * TalkBack 이 켜져 있을 때 첫 안내를 늦추는 시간.
         * 창 제목 "신호등 카메라"(약 1초) 낭독이 끝나고 앱 음성이 시작되게 한다.
         */
        private const val INTRO_DELAY_WITH_SCREEN_READER_MS = 1_500L

        // 실패 안내는 발화 지점이 두 곳(즉시 / onInit 지연)이라 상수로 묶는다.
        // "길게 누르면" 대신 "꾹 누르면" — 얼마나 길게인지 모호했다. 문턱은 1초([VolumeDownRepeat.LONG_PRESS_MS]).
        private const val PERMISSION_DENIED_MESSAGE =
            "카메라 권한이 필요합니다. 소리 줄이는 버튼을 꾹 누르면 권한을 다시 요청합니다."
        private const val PERMISSION_BLOCKED_MESSAGE =
            "카메라 권한이 꺼져 있습니다. 소리 줄이는 버튼을 꾹 누르면 설정 화면을 엽니다. 권한에서 카메라를 허용해 주세요."
        private const val OPENING_SETTINGS_MESSAGE =
            "설정 화면을 엽니다. 권한에서 카메라를 허용한 뒤 돌아와 주세요."
        private const val STILL_NO_PERMISSION_MESSAGE =
            "아직 카메라 권한이 없습니다. 소리 줄이는 버튼을 꾹 누르면 설정 화면을 다시 엽니다."
        /** "다른 앱을 닫아 주세요"는 TalkBack 없이 할 수 없는 일이었다 — 저절로 풀린다는 사실과 기다림만 말한다. */
        private const val CAMERA_IN_USE_MESSAGE =
            "다른 앱이 카메라를 쓰고 있어 신호등을 볼 수 없습니다. 주변 소리에 주의하세요. 카메라가 풀리면 저절로 다시 시작합니다."
        private const val CAMERA_RECOVERED_MESSAGE = "카메라가 다시 켜졌습니다. 신호등을 비춰 주세요."
        // 실패 안내는 "뒤로 가기 → 다시 켜기" 대신 손끝에 있는 키로 바로 다시 켜게 한다(onVolumeLongPress).
        private const val INFERENCE_ERROR_MESSAGE =
            "신호등 인식에 오류가 생겼습니다. 주변 소리에 주의하세요. 소리 줄이는 버튼을 꾹 누르면 다시 시작합니다."
        private const val LIVENESS_MESSAGE =
            "신호를 확인하지 못하고 있습니다. 주변 소리에 주의하세요. 계속 이러면 소리 줄이는 버튼을 꾹 눌러 카메라를 다시 켜 주세요."
        private const val LIVENESS_PAUSE_MESSAGE =
            "신호를 계속 확인하지 못해 일시정지했습니다. 소리 줄이는 버튼을 꾹 누르면 다시 시작합니다."

        /** 워치독이 이만큼 연달아 울린 다음 번에 자동 일시정지 (45초 × 3 ≈ 2분 남짓). */
        private const val LIVENESS_ALARMS_BEFORE_PAUSE = 3
        private const val CAMERA_RECOVERED_SHORT = "카메라가 다시 켜졌습니다."

        /** 건너는 중일 수 있을 때 오류·상태 안내 **앞에** 붙인다 ([crossingAware]). */
        private const val CROSSING_KEEP_GOING = "건너는 중이면 멈추지 말고 끝까지 건너세요. "

        private const val CAMERA_RESUMED_MESSAGE = "신호등 카메라를 다시 켰습니다."
        private const val SCREEN_OFF_MESSAGE = "화면이 꺼져 신호등 안내를 멈췄습니다."
        private const val UTTERANCE_SCREEN_OFF = "screen_off"
        private const val CALL_ENDED_MESSAGE = "통화가 끝나 음성 안내를 다시 들을 수 있습니다."

        /** 첫 안내가 잘렸을 때 기다리는 동안 짧게 채우는 조작법 ([speakOwedKeyHint]). */
        private const val KEY_HINT = "소리 줄이는 버튼을 짧게 누르면 다시 듣기, 꾹 누르면 일시정지입니다."

        private const val AUTO_PAUSE_MESSAGE =
            "2분 동안 신호등을 찾지 못해 일시정지했습니다. 소리 줄이는 버튼을 꾹 누르면 다시 시작합니다."

        /** 오래 아무것도 못 보면(대개 주머니 속) 자동 일시정지. 미탐지 안내가 4번 나간 뒤다. */
        private const val NO_DET_AUTO_PAUSE_MS = 120_000L

        private const val HEAT_MESSAGE = "휴대폰이 뜨거워져 신호 인식이 느려질 수 있습니다. 주변 소리에 더 주의하세요."
        private const val POWER_SAVE_MESSAGE = "절전 모드라 진동 안내가 꺼질 수 있습니다."
        private const val BATTERY_LOW_PERCENT = 15
        private const val BATTERY_CRITICAL_PERCENT = 5

        /** 조준 진동이 끊긴 뒤 확정 진동까지의 틈 — 손이 "다른 신호"로 느낄 만큼. */
        private const val CONFIRM_VIBRATION_GAP_MS = 300L

        /** 이 앱이 DOWN 을 받지 않은 볼륨 키 ([volumeDownSeenAt]). */
        private const val NO_KEY_PRESS = Long.MIN_VALUE

        /** [speakThen] 의 발화 ID — 완료 콜백에서 이 발화만 골라낸다. */
        private const val UTTERANCE_SYSTEM_ACTION = "system_action"

        /** 첫 안내의 발화 ID — 끊겼는지 알아내는 데 쓴다([introInFlight]). */
        private const val UTTERANCE_INTRO = "intro"

        /** 블루투스 이어폰을 깨우는 무음의 발화 ID — 포커스 반납 판단에서 뺀다. */
        private const val UTTERANCE_BT_WAKE = "bt_wake"

        /** 말이 끝나고 이만큼 다음 말이 없으면 다른 앱 소리를 돌려준다. */
        private const val FOCUS_RELEASE_DELAY_MS = 400L

        /**
         * "건너세요" 뒤 건너는 중일 수 있는 시간. 왕복 6~8차로(약 25~30m)를 천천히(0.8m/s) 건너는 시간 + 여유.
         * 빨간불·점멸이 와도, 카메라가 멈췄다 켜져도 끝나지 않는다 — 시간이 지나야 끝난다
         * (그 사이 빨간불·점멸 문구는 "멈추지 말고 끝까지 건너세요" 쪽으로 바뀐다).
         */
        private const val CROSSING_WINDOW_MS = 40_000L

        /** TalkBack 이 창 제목("신호등 카메라")을 읽는 동안 기다리는 시간 — 첫 안내 지연과 같다. */
        private const val SETTLED_SPEECH_DELAY_MS = 1_500L

        /**
         * 완료 콜백이 안 와도 이만큼 지나면 권한 창·설정 화면을 연다. 최소 10초, 글자당 0.22초(띄어쓰기 포함,
         * 기본 속도 한국어 TTS 기준 넉넉히), 최대 30초.
         */
        private const val SYSTEM_ACTION_MAX_WAIT_MS = 10_000L
        private const val SYSTEM_ACTION_MS_PER_CHAR = 220L
        private const val SYSTEM_ACTION_WAIT_CAP_MS = 30_000L

        /** 추론이 이만큼 연속 실패하면(약 5초) 오류를 알린다. */
        private const val INFERENCE_FAILURES_TO_ANNOUNCE = 15
        private const val PERMISSION_GRANTED_MESSAGE =
            "카메라 권한이 허용되었습니다. 카메라로 신호등을 비춰 주세요."

        /**
         * 조작법 — 물리 키는 알려주지 않으면 발견할 방법이 없다. 폰을 드는 법도 함께.
         * ("비춰 주세요"만으로는 후면 카메라가 어디인지, 세워야 하는지 알 수 없었다)
         * TalkBack 이 켜져 있으면 TalkBack 이 창 제목("신호등 카메라")을 이미 읽으므로 첫 문장은 뺀다.
         * "볼륨 아래 버튼"은 "볼륨 버튼 밑의 버튼"으로도 들려서 "소리 줄이는 버튼"이라고 부른다.
         *
         * 2026-11 — 두 가지를 더 말한다.
         *  (1) 어느 쪽이 앞인가: "화면이 내 가슴 쪽" — 뒤 카메라가 어디인지 몰라 거꾸로 드는 일이 있었다.
         *  (2) "톡톡 두 번이면 멈추기": 색은 멈춰야 확정된다(AimingFeedback 주석). 이 규칙을 말한 적이 없어,
         *      처음 쓰는 사람은 계속 훑다가 확정에 실패했다.
         * 몸 전체로 돌린다 — 주변 장소 화면의 방향 찾기와 같은 자세.
         */
        private const val INTRO_TITLE = "신호등 카메라입니다. "
        private const val INTRO_BODY =
            "휴대폰 화면이 내 가슴 쪽을 보게 세로로 세워 드세요. " +
                "몸을 건너편 신호등 쪽으로 천천히 돌리다가, 톡톡 두 번 진동이 오면 멈추고 기다리세요. " +
                "소리 줄이는 버튼을 짧게 누르면 다시 듣기, 꾹 누르면 일시정지입니다."
        private const val NORMAL_INTRO_MESSAGE = INTRO_TITLE + INTRO_BODY

        private const val FLICKER_MESSAGE = "신호가 깜빡입니다. 멈춰서 다음 신호를 기다리세요."

        /**
         * "건너세요" 뒤 건너는 중일 수 있는 동안의 문구. 보행교육 원칙: 이미 건너는 중이면 차도 위에서
         * 멈추지 말고 끝까지 간다. 아직 출발 전일 수도 있으니 두 경우를 다 말한다.
         */
        private const val CROSSING_RED_MESSAGE =
            "빨간불로 바뀌었습니다. 아직 출발하지 않았다면 기다리세요. 건너는 중이면 멈추지 말고 끝까지 건너세요."
        private const val CROSSING_RED_NEW_MESSAGE =
            "빨간불입니다. 아직 출발하지 않았다면 기다리세요. 건너는 중이면 멈추지 말고 끝까지 건너세요."
        private const val CROSSING_FLICKER_MESSAGE =
            "신호가 깜빡입니다. 아직 출발하지 않았다면 기다리세요. 건너는 중이면 멈추지 말고 끝까지 건너세요."
        private const val CROSSING_RED_REPLAY_MESSAGE =
            "지금도 빨간불입니다. 아직 출발하지 않았다면 기다리세요. 건너는 중이면 멈추지 말고 끝까지 건너세요."
        private const val CROSSING_GREEN_REPLAY_MESSAGE =
            "초록불이 켜져 있습니다. 아직 출발하지 않았다면 다음 신호를 기다리세요. 건너는 중이면 멈추지 말고 끝까지 건너세요."

        /** TalkBack 없이 쓰는 사람에게 설정 화면은 막다른 곳이다 — 솔직하게 도움을 청하는 길과 돌아오는 길을 알린다. */
        private const val SETTINGS_HELP =
            " 혼자 하기 어려우면 주변 분께 SafeWalk의 카메라 권한을 켜 달라고 부탁하세요. 뒤로 가기를 누르면 이 화면으로 돌아옵니다."

        /** 분석 결과가 이만큼 끊기면 조준 진동을 끈다. 정상 프레임 간격(0.33~0.5초)의 4배 이상. */
        private const val AIM_STALE_MS = 2_000L
        private const val AIM_STALE_CHECK_MS = 1_000L
        private const val CAMERA_FAILED_MESSAGE =
            "카메라를 열 수 없습니다. 주변 소리에 주의하세요. 소리 줄이는 버튼을 꾹 누르면 카메라를 다시 켭니다. 계속 안 되면 휴대폰을 껐다 켜 주세요."
        private const val CAMERA_HOT_MESSAGE =
            "휴대폰이 뜨거워 카메라를 쓸 수 없습니다. 주변 소리에 주의하세요. 그늘에서 잠시 식힌 뒤 소리 줄이는 버튼을 꾹 눌러 다시 켜 주세요."
        private const val MODEL_LOAD_FAILED_MESSAGE =
            "신호등 인식을 시작할 수 없습니다. 소리 줄이는 버튼을 꾹 누르면 다시 시도합니다."

        /** 색 확정 진동(기다림 400ms / 가도 됨 110·100·110·100·110 = 530ms) 보호 구간. */
        private const val VIBRATE_SHORT_HOLD_MS = 600L

        /** vibrateWarning(300·120·80·120·300 = 920ms) 보호 구간. */
        private const val VIBRATE_WARNING_HOLD_MS = 1_300L

        /**
         * vibrateFatal(500·200·500·200·500 = 1,900ms) 보호 구간.
         * 이 앱에서 가장 중요한 신호("폰이 말을 못 한다")인데 가장 길어서,
         * 보호 구간이 짧으면 소나가 되살아나 끝을 잘라 먹는다.
         */
        private const val VIBRATE_FATAL_HOLD_MS = 2_200L

        /**
         * 실행 중인데 이만큼 아무 출력도 없으면 "멈췄다"고 본다.
         *
         * 정상 동작에서는 미탐지 안내가 최대 30초 간격([NO_DET_REPEAT_MS])으로 나가므로,
         * 그보다 넉넉히 길게 잡아야 정상 상태를 오탐하지 않는다.
         */
        private const val LIVENESS_TIMEOUT_MS = 45_000L

        /** 워치독 점검 주기. */
        private const val LIVENESS_CHECK_INTERVAL_MS = 5_000L

        /**
         * 다시 듣기에서 원문 대신 "약 N초 전에 확인한 신호는 …"(행동 지시 없음)으로 바꾸는 기준.
         * 보행 신호 한 주기가 보통 15~30초라, 10초를 넘겼으면 이미 바뀌었을 수 있다.
         */
        private const val STALE_ANNOUNCEMENT_MS = 10_000L

        /**
         * 일시정지 재알림 간격과 횟수. 사고로 멈춘 경우를 잡되, 일부러 멈춘 사람은 곧 놓아준다.
         * 첫 알림은 시스템 화면 꺼짐(보통 15~30초)보다 먼저 — 8·23·38초, 그 뒤 화면을 놓아준다.
         */
        private const val PAUSED_REMINDER_FIRST_MS = 8_000L
        private const val PAUSED_REMINDER_MS = 15_000L
        private const val PAUSED_REMINDER_MAX = 3

        /** 마지막 재알림("일시정지 중입니다…", 약 4초)이 끝날 즈음 화면 켜 두기를 푼다. */
        private const val PAUSED_SCREEN_RELEASE_MS = 6_000L

        /** 사용자가 직접 멈춘 상태인지 — 화면 회전·프로세스 복원 후에도 유지한다. */
        private const val STATE_USER_PAUSED = "userPaused"
        private const val STATE_CROSSING_UNTIL = "crossingUntilMs"
        private const val STATE_PAUSED_REMINDER_COUNT = "pausedReminderCount"
        private const val STATE_SENT_TO_SETTINGS = "sentToSettings"
        private const val STATE_CAMERA_PERMISSION_BLOCKED = "cameraPermissionBlocked"

        /**
         * 점멸 감지 후 조준 진동 차단 구간.
         * 경고 패턴(약 0.9초) 보호 + 엔진의 점멸 락아웃(6초) + 재확정 몇 프레임을 모두 덮는다.
         */
        private const val FLICKER_AIM_HOLD_MS = 7_000L

        /** 확정색을 붙들고 있어도 이만큼 유효 판정이 없으면 조준 도움을 되살린다. */
        private const val AIM_HOLD_MAX_MS = 20_000L

        /** 분석 스레드가 추론 중일 수 있어 종료를 기다린다 (interpreter.close() 전). */
        private const val EXECUTOR_SHUTDOWN_WAIT_MS = 1_500L

        /**
         * 검출이 이만큼 끊기면 "더 이상 신호를 보고 있지 않다" 로 보고 조준 진동을 재개한다.
         * 추론이 3fps(≈333ms) 이므로 2초는 6프레임 — 순간 놓침으로는 발동하지 않는다.
         */
        private const val AIM_RESUME_AFTER_MS = 2_000L
    }
}
