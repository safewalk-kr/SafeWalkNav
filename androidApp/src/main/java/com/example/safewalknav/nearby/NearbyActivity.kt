package com.example.safewalknav.nearby

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.example.safewalknav.BuildConfig
import com.example.safewalknav.MainActivity
import com.example.safewalknav.R
import com.example.safewalknav.a11y.AppSettings
import com.example.safewalknav.a11y.AudioRouting
import com.example.safewalknav.a11y.PermissionGate
import com.example.safewalknav.a11y.Speaker
import com.example.safewalknav.a11y.VolumeDownRepeat
import com.example.safewalknav.a11y.WindowFocus
import com.example.safewalknav.navigation.geo.clockHour
import com.example.safewalknav.navigation.geo.distanceBetween
import com.example.safewalknav.navigation.geo.initialBearingDeg
import com.example.safewalknav.navigation.geo.relativeBearingDeg
import com.example.safewalknav.navigation.nearby.Anchor
import com.example.safewalknav.navigation.nearby.FusedFix
import com.example.safewalknav.navigation.nearby.GeoPoint
import com.example.safewalknav.navigation.nearby.LocationAnchors
import com.example.safewalknav.navigation.nearby.LocationFusion
import com.example.safewalknav.navigation.nearby.NearbyAssembler
import com.example.safewalknav.navigation.nearby.NearbyPhrases
import com.example.safewalknav.navigation.nearby.NearbyPlace
import com.example.safewalknav.navigation.nearby.NearbyResult
import com.example.safewalknav.navigation.nearby.PointGridIndex
import com.example.safewalknav.navigation.tmap.AddressInfo
import com.example.safewalknav.navigation.tmap.POIResult
import com.google.android.gms.common.api.ResolvableApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.IdentityHashMap
import kotlin.math.max

/**
 * 기능1: 내 위치 알려주기 + 주변 장소 박스.
 *
 * 흐름
 *  1) 위치 권한 확인 → 위치 기능 켜짐 확인
 *  2) 최대 8초 동안 픽스를 모아 보정([LocationSampler] → shared [LocationFusion])
 *  3) 주소(역지오코딩) + 카테고리별 거리순 검색을 **동시에** 요청
 *  4) "현재 위치는 ○○ 근처입니다. 주변 장소 N곳을 찾았습니다." — 장소 이름은 말하지 않는다
 *  5) 박스를 누르면 **그 순간의** 정면 방향 기준 "○시 방향, 약 N미터"
 *
 * 방향을 누르는 순간 계산하는 이유: 사용자는 목록을 듣는 동안 몸을 돌린다.
 * 검색 시점 방향을 박스에 박아 두면 몇 초 뒤엔 틀린 말이 된다.
 */
class NearbyActivity : AppCompatActivity() {

    private lateinit var tvAddress: TextView
    private lateinit var tvStatus: TextView
    private lateinit var placesContainer: LinearLayout
    private lateinit var btnRefresh: Button
    private lateinit var btnCamera: Button

    private lateinit var speaker: Speaker
    private lateinit var heading: HeadingTracker
    private lateinit var sampler: LocationSampler
    private lateinit var beacon: DirectionBeacon
    private val tmap get() = TMapProvider.client

    /** 볼륨 아래 키 = 다시 듣기 (TalkBack 꺼짐일 때만 — 판단은 VolumeDownRepeat 가 한다). */
    private val volumeRepeat = VolumeDownRepeat(this) { repeatForVolumeKey() }

    private var searchJob: Job? = null
    private var fix: FusedFix? = null

    /**
     * 찾는 중인가 — [다시 찾기] 글자("찾는 중")와 화면 켜 두기의 기준.
     * searchJob.isActive 대신 따로 둔다: 검색의 finally 안에서는 잡이 아직 active 라 "끝났다"를 판단할 수 없다.
     * 취소된 옛 검색의 finally 가 새 검색의 상태를 지우지 않게 세대 번호로 확인한다.
     */
    private var searching = false
    private var searchGeneration = 0

    /** [fix] 를 얻은 시각(단조 시계). 오래된 위치로 방향을 말하지 않기 위해. */
    private var fixAtMs = 0L

    /** 화면을 떠나며 검색을 취소했다 — 돌아오면 다시 찾는다. */
    private var refreshOnReturn = false

    /**
     * 마지막 위치 설명에 쓰인 재료. 위치 제목에 TalkBack 포커스가 갈 때마다
     * **그 순간의 방향으로** 문장을 다시 만든다(몇 분 전 방향을 그대로 읽으면 틀린 말이 된다).
     */
    private var locationContext: LocationContext? = null

    private class LocationContext(val address: AddressInfo?, val anchors: List<Anchor>, val fix: FusedFix)

    /** 방향 찾기 진동이 가리키고 있는 장소. 같은 박스를 다시 누르면 멈춘다. */
    private var beaconPlace: NearbyPlace? = null

    /**
     * 장소 → 박스. 진동 중인 박스를 굵은 노란 테두리·상태 설명으로 표시하려고.
     * 이름이 같은 장소가 둘일 수 있어(체인점·보행 신호등) 값이 아니라 **객체 동일성**으로 찾는다 —
     * [beaconPlace] 비교(===)와 같은 기준.
     */
    private val cardByPlace = IdentityHashMap<NearbyPlace, View>()

    /**
     * 마지막 안내를 **지금 방향으로** 다시 만들어 말하는 방법(볼륨 아래 키 다시 듣기용).
     * 방향이 든 안내(장소 안내·위치 설명)만 해당한다 — 그새 몸을 돌렸으면 옛 "2시 방향"은 틀린 말이다.
     * 다른 말을 하면 비운다([say]). 비어 있으면 마지막 말을 그대로 되풀이한다.
     */
    private var freshRepeat: (() -> Boolean)? = null

    /** 8자 보정 안내는 화면당 한 번만 — 계속 말하면 잔소리가 된다. */
    private var calibratePrompted = false

    /** 화면 배치 설명(TalkBack 꺼짐용)은 이 화면을 열 때마다 한 번만. */
    private var layoutDescribed = false

    /** 우리 [신호등 카메라] 버튼으로 나갔다 — 돌아와서 다시 찾지 않으면 "돌아왔습니다"라고 알린다. */
    private var leftForCamera = false

    /**
     * 위치 권한이 영구 거부(다시 묻지 않음) 상태 — 시스템이 창을 더는 안 띄운다.
     * 예전에는 [다시 찾기] 가 즉시 거부되는 창만 다시 불러 같은 안내가 반복됐다(빠져나갈 길 없음).
     * 이제 그 버튼이 앱 설정 화면을 연다 — 글자도 "설정 열기"로 바꾼다([updateRefreshButton]).
     */
    private var locationPermissionBlocked = false
    private var locationPermissionRequestedAt = 0L

    /** 설정 화면으로 보냈다 — 돌아오면 권한을 다시 보고 찾기 시작하거나 무엇이 빠졌는지 말한다. */
    private var sentToSettings = false

    /** 우리가 띄운 권한 창이 떠 있다 — 그동안의 onPause 는 "떠남"이 아니다. */
    private var permissionDialogShowing = false

    /** 안내를 말하던 중에 화면을 떠났다 — 돌아오면 다시 들려준다. */
    private var speechCutOff = false

    /**
     * 화면을 떠나며(전화 받음·화면 꺼짐) onPause 가 방향 찾기 진동을 멈췄다 — 돌아오면 한 번 알린다.
     * 예전에는 말없이 멈춰서, 사용자는 아직 그쪽이 아닌 줄 알고 계속 몸을 돌렸다.
     */
    private var beaconInterrupted = false

    /**
     * 마지막 검색이 "위치 기능 꺼짐"으로 멈췄다. 빠른 설정에서 켜고 돌아오면(onResume·창 포커스)
     * [다시 찾기]를 누르라고 시키지 않고 한 번 알린 뒤 바로 다시 찾는다.
     */
    private var locationOffFailed = false

    /** 우리가 띄운 위치 켜기 창이 떠 있다 — 그동안의 onPause 는 "떠남"이 아니다(앞의 안내를 끊지 않는다). */
    private var locationDialogShowing = false

    /**
     * 위치 설정 확인(비동기) 번호. 새 검색·창 결과가 오면 올린다 —
     * 그 사이 끝난 옛 확인이 창을 또 띄우거나 엉뚱한 안내를 하지 않게.
     */
    private var locationCheckId = 0

    /**
     * 위치 켜기 창의 결과. 켰으면 곧바로 다시 찾는다(버튼을 또 누르게 하지 않는다).
     * 닫았으면(아니요·뒤로) 켜는 다른 방법을 말한다 — 창을 다시 띄우지는 않는다(빠져나갈 수 없는 반복이 된다).
     * 결과는 onResume 보다 먼저 온다 — 켰으면 여기서 [refresh] 가 [locationOffFailed] 를 지워,
     * 뒤이은 onResume 의 "위치가 켜졌습니다"와 겹치지 않는다.
     */
    private val locationSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            locationDialogShowing = false
            locationCheckId++
            // 창이 떠 있는 사이 미뤄진 확인이 있었어도 버린다 — 남으면 닫은 창이 onResume 에서 또 떴다.
            refreshOnReturn = false
            // 아래에서 새로 말한다 — (창이 이 화면을 완전히 가려 onStop 이 끊은 경우) 옛 안내를 되살리지 않게.
            speechCutOff = false
            when {
                // 자동으로 다시 찾는 검색에서는 창을 다시 띄우지 않는다 — 켰다고 했는데 아직 꺼짐으로
                // 보이는 드문 경우에도 창이 되풀이되지 않게.
                result.resultCode == Activity.RESULT_OK ->
                    refresh(offerLocationDialog = false, announce = LOCATION_TURNED_ON)
                // 창은 닫았지만 그 사이 빠른 설정으로 켰다 — "꺼져 있습니다" 뒤 곧바로 "켜졌습니다"가 이어지지 않게.
                sampler.isLocationEnabled() ->
                    refresh(offerLocationDialog = false, announce = LOCATION_TURNED_ON_ELSEWHERE)
                else -> say(NearbyPhrases.LOCATION_OFF)
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionDialogShowing = false
            if (hasFineLocation()) {
                locationPermissionBlocked = false
                refresh()
            } else {
                locationPermissionBlocked = PermissionGate.isBlocked(
                    this, Manifest.permission.ACCESS_FINE_LOCATION, locationPermissionRequestedAt
                )
                updateRefreshButton()   // 안내가 말하는 버튼 이름("설정 열기")과 글자를 맞춘다
                tvAddress.text = "위치 권한 필요"
                say(locationPermissionMessage())
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nearby)
        title = "내 위치와 주변 장소"
        AudioRouting.bindVolumeKeys(this)

        tvAddress = findViewById(R.id.tvAddress)
        tvStatus = findViewById(R.id.tvStatus)
        placesContainer = findViewById(R.id.placesContainer)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnCamera = findViewById(R.id.btnCamera)

        speaker = Speaker(this, tvStatus)
        heading = HeadingTracker(this)
        sampler = LocationSampler(this)
        beacon = DirectionBeacon(this, heading).apply {
            onTimeout = {
                onBeaconEnded()
                say(NearbyPhrases.BEACON_TIMEOUT)
            }
            onCompassLost = {
                onBeaconEnded()
                say("${NearbyPhrases.BEACON_STOPPED} ${compassAdvice()}")
            }
        }

        // 위치 제목: TalkBack 이 포커스할 때마다 지금 방향으로 위치 설명을 새로 만든다.
        ViewCompat.setAccessibilityDelegate(tvAddress, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                currentLocationMessage()?.let { info.contentDescription = it }
            }
        })
        // 제목(heading)으로 알린다 — XML 의 accessibilityHeading 은 API 28+ 에서만 먹는다(minSdk 26).
        ViewCompat.setAccessibilityHeading(tvAddress, true)

        // 상태 줄: 200% 글꼴에서 넘치면 "…"로 자르지 않고 안에서 스크롤한다(최대 높이는 XML).
        tvStatus.movementMethod = ScrollingMovementMethod()
        // setMovementMethod 는 TextView 를 클릭 가능하게 바꾼다 — TalkBack 이 상태 줄을
        // "두 번 탭하여 활성화"로 읽지 않게 되돌린다(터치 스크롤은 클릭 가능 여부와 상관없이 된다).
        tvStatus.isClickable = false
        tvStatus.isLongClickable = false
        // 새 안내는 첫 줄부터 보이게 — 앞 문장을 내려 둔 스크롤 위치에 머물지 않게.
        tvStatus.doAfterTextChanged { tvStatus.scrollTo(0, 0) }

        // 박스 묶음을 "목록"으로 알린다 — TalkBack: 목록에 들어갈 때 "항목 N개", 박스마다 몇 번째인지.
        // 장소가 몇 곳인지, 지금 몇 번째를 듣는지 알아야 끝까지 넘길지 정할 수 있다.
        placesContainer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(placesContainer, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                val count = placesContainer.childCount
                if (count > 0) {
                    info.setCollectionInfo(
                        AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(count, 1, false)
                    )
                }
            }
        })

        btnRefresh.setOnClickListener {
            // 찾는 동안에도 버튼은 살려 둔다 — 비활성 버튼은 눌러도 반응이 없어 고장 난 줄 안다.
            // 두 번째 검색은 시작하지 않고 기다리라고만 말한다.
            if (searching) say(STILL_SEARCHING_TAP) else refresh()
        }
        // 보행 신호등이 가까우면 곧바로 신호등 카메라로 — 홈으로 돌아갈 필요 없이.
        btnCamera.setOnClickListener {
            // 진행 중인 검색도 멈춘다. 안 그러면 몇 초 뒤 이 화면의 음성이 신호등 카메라 안내 위로
            // "주변 장소 N곳을 찾았습니다"를 덮어 말한다. 돌아오면 다시 찾는다(onResume).
            cancelSearchForLeaving()
            stopBeacon()
            speaker.stop()
            // 1분 안에 돌아오면 다시 찾지 않아 아무 말이 없었다 — 돌아온 것을 알린다(onResume).
            leftForCamera = true
            startActivity(Intent(this, MainActivity::class.java))
        }

        if (BuildConfig.TMAP_APP_KEY.isBlank()) {
            tvAddress.text = "설정 문제"
            btnRefresh.isEnabled = false
            say(NearbyPhrases.NOT_CONFIGURED)
            return
        }

        if (savedInstanceState != null) {
            sentToSettings = savedInstanceState.getBoolean(STATE_SENT_TO_SETTINGS, false)
            locationPermissionBlocked = savedInstanceState.getBoolean(STATE_PERMISSION_BLOCKED, false)
            updateRefreshButton()
        }
        if (hasFineLocation()) {
            refresh()
        } else if (savedInstanceState == null) {
            // 재생성(프로세스 복원) 때 다시 띄우면 권한 창이 겹쳐 뜬다 — 처음 열 때만 묻는다.
            requestLocationPermission()
        } else if (!sentToSettings) {
            tvAddress.text = "위치 권한 필요"
            say(locationPermissionMessage())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SENT_TO_SETTINGS, sentToSettings)
        outState.putBoolean(STATE_PERMISSION_BLOCKED, locationPermissionBlocked)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        volumeRepeat.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        volumeRepeat.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)

    override fun onResume() {
        super.onResume()
        heading.start()
        updateKeepScreenOn()   // onPause 에서 풀었다 — 아직 찾는 중이면 다시 켠다
        // 우리가 보낸 게 아니어도 시스템 설정에서 권한을 켜고 돌아올 수 있다 — 버튼이 "설정 열기"로 남지 않게.
        if (locationPermissionBlocked && hasFineLocation()) {
            locationPermissionBlocked = false
            updateRefreshButton()
        }
        val backFromCamera = leftForCamera
        leftForCamera = false
        // 떠나 있는 사이 방향 찾기 진동이 멈췄다 — 돌아와서 하는 첫 안내에 **한 번에** 붙인다
        // (따로 말하면 앞의 말을 끊거나 두 말이 겹친다).
        val beaconCue = if (beaconInterrupted) NearbyPhrases.BEACON_INTERRUPTED else null
        beaconInterrupted = false
        if (sentToSettings) {
            sentToSettings = false
            if (hasFineLocation()) {
                locationPermissionBlocked = false
                refresh()
            } else {
                // 설정에서 허용하지 않고 돌아옴 — 무엇이 빠졌는지 말한다(아무 말 없으면 멈춘 줄 안다).
                tvAddress.text = "위치 권한 필요"
                say(locationPermissionMessage())
            }
            return
        }
        // 위치 기능이 꺼져 멈췄는데 그새 켜고 돌아왔다(빠른 설정 등) — 알리고 바로 다시 찾는다.
        if (refreshIfLocationTurnedOn()) return
        // 돌아왔을 때 다시 찾는다 — ① 떠나며 검색을 끊었거나 ② 위치가 오래됐을 때.
        // 신호등 카메라로 길을 건너고 돌아오면 예전 박스는 수십 m 전 위치 기준이라, 누르면 엉뚱한 방향을 말한다.
        // (같은 자리면 장소 목록은 캐시에서 나와 API 비용은 거의 없다)
        val stale = fix != null && SystemClock.elapsedRealtime() - fixAtMs > STALE_FIX_ON_RETURN_MS
        if (refreshOnReturn || stale) {
            // 위치 켜기 창은 사용자가 시작한 검색(끊겼거나 확인이 미뤄진 것)에서만 — 위치가 오래돼 알아서
            // 다시 찾는 경우에는 아무것도 안 눌렀는데 창이 뜨지 않게 한다.
            val offerDialog = refreshOnReturn
            refreshOnReturn = false
            speechCutOff = false   // 다시 찾으면서 새로 안내한다
            refresh(
                offerLocationDialog = offerDialog,
                announce = listOfNotNull(beaconCue, NearbyPhrases.SEARCHING).joinToString(" "),
            )
            return
        }
        // 신호등 카메라에서 1분 안에 돌아옴 — 다시 찾지 않으니 예전엔 아무 말이 없어 어느 화면인지 몰랐다.
        // (카메라로 갈 때 일부러 끊은 말이라 그 말을 되살리지는 않는다)
        if (backFromCamera) {
            speechCutOff = false
            say(listOfNotNull(RETURNED_FROM_CAMERA, beaconCue).joinToString(" "))
            return
        }
        // 진동이 멈췄다는 말이 끊긴 안내를 대신한다. 진동 중에 끊긴 말은 거의 늘 그 장소 안내
        // ("그 장소 쪽을 향하면 진동이 울립니다")라, 되살리면 오지 않을 진동을 기다리게 한다.
        // 장소를 다시 누르면 지금 방향과 진동이 함께 다시 나온다.
        if (beaconCue != null) {
            speechCutOff = false
            say(beaconCue)
            return
        }
        // 안내(위치 설명·요약)를 듣다가 나갔다 돌아옴 — 끊긴 안내를 처음부터 다시 들려준다.
        // 상태 줄에는 마지막으로 한 말이 그대로 있다(라이브 리전 토글용 보이지 않는 문자만 뗀다).
        if (speechCutOff) {
            speechCutOff = false
            // 방향이 든 안내(장소·위치 설명)는 옛 문장을 그대로 읽지 않고 **지금 방향으로** 새로 만든다 —
            // 예전엔 떠나 있던 사이 몸을 돌렸는데도 옛 "2시 방향"과, onPause 가 이미 끈 진동의 안내
            // ("그 장소 쪽을 향하면 진동이 울립니다")를 다시 읽어, 오지 않을 진동을 기다리게 했다.
            if (freshRepeat?.invoke() == true) return
            val last = tvStatus.text.toString().replace("​", "").trim()
            // say() 가 아니라 speaker 로 — 같은 말을 되살리는 것이라 [freshRepeat] 를 지우지 않는다.
            if (last.isNotEmpty()) speaker.speak(last)
        }
    }

    /**
     * 빠른 설정(위에서 끌어내리는 창)에서 위치를 켜면 대부분의 휴대폰에서 onPause/onResume 없이
     * 창 포커스만 잃었다 돌아온다 — onResume 만 보면 바로 그 경우를 놓친다.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refreshIfLocationTurnedOn()
    }

    /**
     * 위치 기능이 꺼져 검색이 멈췄는데 지금은 켜져 있다 — 한 번 알리고 바로 다시 찾는다.
     * [다시 찾기] 버튼을 찾아 누르게 시키지 않는다. 한 번만: [refresh] 가 [locationOffFailed] 를 지운다.
     * 위치 켜기 창이 떠 있는 동안은 그 결과([locationSettingsLauncher])가 처리한다.
     * @return 다시 찾기를 시작했으면 true
     */
    private fun refreshIfLocationTurnedOn(): Boolean {
        if (!locationOffFailed || locationDialogShowing || searching) return false
        if (!sampler.isLocationEnabled()) return false
        speechCutOff = false   // 다시 찾으면서 새로 안내한다
        refresh(offerLocationDialog = false, announce = LOCATION_TURNED_ON_ELSEWHERE)
        return true
    }

    /**
     * 위치 권한을 얻는 길. 영구 거부면 앱 설정 화면을, 아니면 권한 창을 연다.
     * TalkBack 없이 쓰는 사람은 권한 창이 떴다는 것도 모르므로 미리 말해 둔다.
     */
    private fun requestLocationPermission() {
        if (locationPermissionBlocked) {
            // TalkBack 없이는 설정 화면의 항목을 혼자 찾기 어렵다 — 도움을 청하는 법과 돌아오는 법까지 말한다.
            say(if (speaker.isScreenReaderOn()) OPENING_SETTINGS else OPENING_SETTINGS + OPENING_SETTINGS_HELP)
            sentToSettings = AppSettings.open(this)
            return
        }
        if (!speaker.isScreenReaderOn()) say(permissionDialogHint())
        locationPermissionRequestedAt = PermissionGate.markRequested()
        permissionDialogShowing = true   // 권한 창이 이 화면을 가려도(onPause) 위 안내는 끝까지 들려준다
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    /** 지금 상태에 맞는 위치 권한 안내 — 무엇을 눌러/켜야 하는지까지. */
    private fun locationPermissionMessage(): String {
        // 안드로이드 12+: "대략적 위치"만 허용 — 오차가 수 km 라 주변 안내가 성립하지 않는다.
        val approximateOnly = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return when {
            locationPermissionBlocked && approximateOnly -> LOCATION_BLOCKED_APPROXIMATE
            locationPermissionBlocked -> LOCATION_BLOCKED
            approximateOnly -> LOCATION_APPROXIMATE
            else -> LOCATION_DENIED
        }
    }

    /**
     * 화면이 완전히 가려졌다 — 안전망. 보통은 onPause 에서 이미 멈췄다.
     * 권한 창이 떠 있는 동안 홈 버튼을 누른 경우(onPause 에서는 일부러 안 멈춤)가 여기서 걸린다.
     */
    override fun onStop() {
        super.onStop()
        cancelSearchForLeaving()
        // 우리가 설정 화면을 연 경우만 "허용한 뒤 돌아오세요"를 끝까지 들려준다.
        if (!sentToSettings) {
            if (speaker.isBusy()) speechCutOff = true
            speaker.stop()
        }
    }

    private fun cancelSearchForLeaving() {
        if (searchJob?.isActive == true) {
            searchJob?.cancel()
            refreshOnReturn = true
        }
    }

    /** 지금 방향 기준의 위치 설명 (아직 검색 전이면 null). */
    private fun currentLocationMessage(): String? {
        val c = locationContext ?: return null
        // 박스 탭과 같은 기준 — 위치가 오래됐으면 방향을 빼고 그 사실을 붙인다.
        // (화면을 켠 채 걸은 뒤 제목을 들으면 옛 위치 기준 "○시 방향"이 나오던 문제)
        val stale = SystemClock.elapsedRealtime() - fixAtMs > STALE_FIX_ON_TAP_MS
        val message = NearbyPhrases.currentLocation(c.address, c.anchors, c.fix.accuracyM) { a ->
            if (stale) null else clockHourTo(c.fix, a.lat, a.lon, a.distanceM)
        }
        return if (stale) "$message ${NearbyPhrases.STALE_LOCATION_HINT}" else message
    }

    /**
     * 홈 버튼·최근 앱·화면 끄기 → **즉시** 말과 검색을 멈춘다.
     * 예전에는 onStop 에서만 멈춰서, 앱 밖으로 나간 뒤에도 안내가 조금 더 이어졌다.
     * 예외: 우리가 띄운 권한 창·위치 켜기 창이 이 화면을 가린 경우(그 창을 설명하는 안내를 끊지 않게),
     *       우리가 연 설정 화면으로 가는 경우("허용한 뒤 돌아오세요"를 끝까지).
     */
    override fun onPause() {
        super.onPause()
        // 화면을 떠나면 방향 진동도 끝 — 주머니 속에서 계속 떨면 안 된다.
        // 켜져 있었는지 적어 두고 돌아오면 멈췄다고 알린다(onResume). 우리가 일부러 끄고 나가는
        // 경우(신호등 카메라 버튼)는 여기 오기 전에 이미 꺼져 있어 해당하지 않는다.
        if (::beacon.isInitialized && beacon.isRunning) beaconInterrupted = true
        stopBeacon()
        heading.stop()
        if (!permissionDialogShowing && !sentToSettings && !locationDialogShowing) {
            cancelSearchForLeaving()   // 남의 화면 위로 "주변 장소 N곳을 찾았습니다"가 나오지 않게
            if (speaker.isBusy()) speechCutOff = true
            speaker.stop()
        }
        // 화면 켜 두기는 이 화면이 보이는 동안만 — 떠나면 무조건 푼다(돌아오면 onResume 에서 다시 판단).
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        searchJob?.cancel()
        stopBeacon()
        speaker.shutdown()
        super.onDestroy()
    }

    /**
     * 나침반이 불량일 때의 조언 — 원인에 맞게. 주변 쇠붙이 간섭이면 8자 보정은 소용없고 자리를 옮겨야 한다
     * (보정 안내를 하면 아무리 그려도 안 고쳐져서 앱이 고장 난 줄 안다).
     */
    private fun compassAdvice(): String =
        if (!heading.needsCalibration && heading.isMagneticallyDisturbed) {
            NearbyPhrases.MAGNETIC_INTERFERENCE
        } else {
            NearbyPhrases.CALIBRATE_COMPASS
        }

    private fun stopBeacon() {
        if (::beacon.isInitialized) beacon.stop()
        onBeaconEnded()
    }

    /** 진동이 (누가 멈췄든 스스로 끝났든) 끝났다 — 박스 표시와 화면 켜 두기를 맞춘다. */
    private fun onBeaconEnded() {
        beaconPlace = null
        updateCardStates()
        updateKeepScreenOn()
    }

    private fun isBeaconOn(place: NearbyPlace): Boolean =
        ::beacon.isInitialized && beacon.isRunning && beaconPlace === place

    /**
     * 찾는 중이거나 방향 찾기 진동 중에는 화면을 켜 둔다.
     * 화면 꺼짐 시간이 15~30초인 휴대폰은 검색·진동 도중 화면이 꺼졌고, 그때의 onPause 가 진동을
     * 소리 없이 멈췄다(사용자는 아직 그쪽이 아닌 줄 알고 계속 몸을 돌렸다). 둘 다 끝나면 바로 푼다(배터리).
     */
    private fun updateKeepScreenOn() {
        val keep = searching || (::beacon.isInitialized && beacon.isRunning)
        if (keep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * 이 화면의 안내 창구. 방향이 들지 않은 말이라 다시 듣기는 이 말을 그대로 되풀이한다
     * ([freshRepeat] 를 비운다).
     */
    private fun say(text: String, interrupt: Boolean = true) {
        freshRepeat = null
        speaker.speak(text, interrupt)
    }

    /**
     * 볼륨 아래 키(TalkBack 꺼짐) = 다시 듣기.
     *  - 마지막 말이 장소 안내·위치 설명 → **지금 방향으로** 다시 만들어 말한다(진동은 켜고 끄지 않는다).
     *  - 그 밖 → 마지막 말 그대로. 들려줄 것이 아직 없으면 화면 설명.
     */
    private fun repeatForVolumeKey() {
        if (freshRepeat?.invoke() == true) return
        if (!speaker.repeatLast()) say(SCREEN_DESCRIPTION)
    }

    /**
     * 정밀 위치 권한. Android 12+ 에서 "대략적 위치"만 허용하면 오차가 수 km 라
     * 10m 주변 안내가 성립하지 않으므로 정밀 권한을 요구한다.
     */
    private fun hasFineLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ==================== 검색 ====================

    /**
     * @param offerLocationDialog 위치 기능이 꺼져 있으면 켜는 창을 띄운다. 사용자가 시작한 검색만 true —
     *   창 결과·"위치가 켜졌습니다"로 **자동으로** 다시 찾는 검색은 false(창이 되풀이되지 않게).
     * @param announce 시작할 때 하는 말. 돌아온 이유 등을 앞에 붙여 **한 번에** 말하려고 받는다.
     */
    private fun refresh(offerLocationDialog: Boolean = true, announce: String = NearbyPhrases.SEARCHING) {
        if (searchJob?.isActive == true) return
        // 위치 켜기 창이 뜨는 중·떠 있는 중 — 그 결과가 다음 일을 정한다. 창을 띄운 직후(화면이 가려지기 전)
        // 버튼이 한 번 더 눌리면 두 번째 확인이 창을 또 띄우거나, 닫은 창이 돌아와서 다시 떴다.
        if (locationDialogShowing) return
        // 새 검색이 상태를 다시 판단한다 — 진행 중이던 위치 설정 확인은 무효, "위치 꺼짐" 기록도 지운다.
        locationCheckId++
        locationOffFailed = false
        if (!hasFineLocation()) {
            // 권한 창을 그냥 닫았던 경우 — [다시 찾기] 로 다시 물을 수 있어야 한다.
            // 영구 거부면 설정 화면을 연다.
            requestLocationPermission()
            return
        }
        // 권한이 (설정 등에서) 이미 허용됐다 — 버튼을 "설정 열기"로 남겨 두지 않는다.
        // 아래에서 일찍 끝나는 경우(위치 기능 꺼짐)에도 글자가 맞도록 여기서 바로 맞춘다.
        locationPermissionBlocked = false
        updateRefreshButton()
        if (!sampler.isLocationEnabled()) {
            onLocationOff(offerLocationDialog)
            return
        }

        stopBeacon()
        refreshOnReturn = false
        clearPlaces()
        tvAddress.text = "내 위치 확인 중"
        locationContext = null   // 지난 위치 설명이 남아 읽히지 않게
        say(announce)
        val generation = ++searchGeneration
        setSearching(true)

        searchJob = lifecycleScope.launch {
            // 7초가 지나도 안 끝나면 한 번 "아직 찾고 있습니다" — 약전계에서 10초 넘게 조용하면 멈춘 줄 안다.
            // 말하던 것을 끊지 않는다(interrupt=false). 검색이 끝나거나 취소되면 같이 취소된다.
            val slowNotice = launch {
                delay(SLOW_SEARCH_NOTICE_MS)
                say(STILL_SEARCHING, interrupt = false)
            }
            try {
                // 보행등 데이터는 위치를 모으는 동안 미리 읽어 둔다(첫 회만 수십 ms).
                val signalIndexJob = async { PedSignalRepository.get(this@NearbyActivity) }
                val samples = sampler.sample()
                val f = LocationFusion.fuse(samples)
                if (f == null) {
                    // 찾는 도중에 위치 기능이 꺼졌다 — "확인하지 못했습니다"(건물 밖으로 나가라)가 아니라
                    // 꺼졌다고 말해야 켤 생각을 한다.
                    if (!sampler.isLocationEnabled()) {
                        onLocationOff(offerLocationDialog)
                        return@launch
                    }
                    tvAddress.text = "위치를 찾지 못함"
                    say(NearbyPhrases.LOCATION_FAILED)
                    return@launch
                }
                fix = f
                fixAtMs = SystemClock.elapsedRealtime()
                heading.setLocation(f.lat, f.lon)

                // 주소(건물명·동)와 키워드별 검색을 동시에. 하나가 실패해도 나머지는 쓴다.
                // 같은 자리(25m)·2분 안이면 직전 장소 목록을 재사용한다 — 거리·방향은 아래에서 새로 계산.
                // (재사용한 결과는 다시 저장하지 않는다 — 저장할 때마다 2분이 새로 시작돼 영영 안 바뀐다)
                // 주소는 매번 새로 묻는다(TMapProvider.kt 의 NearbySearchCache 주석).
                //
                // ⚠️ 동시에 [MAX_CONCURRENT_REQUESTS] 건까지만 보낸다. HTTP 엔진(OkHttp)은 같은 서버에
                //    5건까지만 동시에 보내고 나머지는 줄을 세우는데, 10초 제한 시간은 줄 서 있는 동안에도 흐른다.
                //    약전계에서 세 번째 묶음이 줄만 서다 시간 초과로 "실패" 처리됐다. 여기서 세면 시계는
                //    실제로 보낼 때부터 간다.
                //    대신 줄 서는 시간까지 합친 전체 마감([SEARCH_DEADLINE_MS])을 둔다 — 약전계에서 요청마다
                //    10초씩 4·4·3 세 번 기다려 30초 넘게 무음이 되지 않게. 마감을 넘긴 건 실패(null)로 친다.
                val (address, groups) = coroutineScope {
                    val gate = Semaphore(MAX_CONCURRENT_REQUESTS)
                    val addr = async(Dispatchers.IO) {
                        withTimeoutOrNull(SEARCH_DEADLINE_MS) { gate.withPermit { tmap.reverseGeocodeDetail(f.lat, f.lon) } }
                    }
                    val g = NearbySearchCache.get(f.lat, f.lon) ?: (SEARCH_CATEGORIES + ANCHOR_ONLY_KEYWORDS)
                        .map { c ->
                            async(Dispatchers.IO) {
                                c to withTimeoutOrNull(SEARCH_DEADLINE_MS) {
                                    gate.withPermit { tmap.searchNearbyByKeyword(c, f.lat, f.lon) }
                                }
                            }
                        }
                        .awaitAll()
                        .also { NearbySearchCache.put(f.lat, f.lon, it) }
                    addr.await() to g
                }
                logSearchResults(f, groups)

                val byKeyword: Map<String, List<POIResult>> =
                    groups.mapNotNull { (k, pois) -> pois?.let { k to it } }.toMap()
                // 박스용 업종 검색이 전부 실패 = 검색 실패. (지하철 출구·교차로 같은 기준점 검색만 성공해도
                // 예전에는 "일부만 실패"로 쳐서 "찾은 장소가 없습니다"라고 단정했다)
                // 인터넷 검색이 실패해도 멈추지 않는다 — 보행 신호등은 앱 안 데이터라 여전히 알려 줄 수 있다.
                val searchFailed = SEARCH_CATEGORIES.none { byKeyword.containsKey(it) }
                // 일부만 실패 — 안 나온 종류(예: 버스정류장)가 "없는 것"으로 오해되지 않게 알린다.
                val searchPartial = !searchFailed && byKeyword.size < groups.size

                // ── 1) 지금 어디인가 — 도로명 대신 기준점(지하철 출구·정류장·교차로)으로 ──
                val exits = byKeyword[LocationAnchors.KEYWORD_SUBWAY_EXIT].orEmpty()
                    .filter { LocationAnchors.isSubwayExit(it.name) }
                val stops = byKeyword[LocationAnchors.KEYWORD_BUS_STOP].orEmpty()
                    .filter { LocationAnchors.isBusStop(it) }
                val crossings = byKeyword[LocationAnchors.KEYWORD_INTERSECTION].orEmpty()
                    .filter { LocationAnchors.isIntersection(it.name) }
                val signals = nearbyPedSignals(signalIndexJob.await(), f)
                val anchors = LocationAnchors.pick(
                    f.lat, f.lon, exits, stops, crossings,
                    nearestPedSignal = signals.firstOrNull()?.let { s ->
                        GeoPoint(s.lat, s.lon) to distanceBetween(f.lat, f.lon, s.lat, s.lon)
                    },
                )
                tvAddress.text = NearbyPhrases.locationHeadline(address, anchors)
                // TalkBack 사용자가 제목에 포커스하면 **전체 위치 설명**을 (그 순간의 방향으로) 다시 듣는다.
                // 아래 상태 줄은 박스를 누를 때마다 그 장소 안내로 덮여서, 위치 설명을 다시 들을 곳이 없어진다.
                locationContext = LocationContext(address, anchors, f)
                val locationMsg = currentLocationMessage().orEmpty()

                // ── 2) 주변 장소 박스 — 업종 검색 + 지하철 출구·교차로(거른 것만) ──
                val boxGroups = SEARCH_CATEGORIES.mapNotNull { k ->
                    val pois = if (k == LocationAnchors.KEYWORD_BUS_STOP) stops else byKeyword[k]
                    pois?.let { k to it }
                } + listOf("지하철 출구" to exits, "교차로" to crossings, SIGNAL_CATEGORY to signals)
                val result = NearbyAssembler.assemble(f.lat, f.lon, f.accuracyM, boxGroups)
                renderPlaces(result)

                var summary = when {
                    // 하나도 못 찾았는데 검색이 (일부라도) 실패했다 — "없다"고 단정하지 않는다.
                    (searchFailed || searchPartial) && result.places.isEmpty() -> NearbyPhrases.SEARCH_FAILED
                    // 검색은 실패했지만 보행 신호등(앱 안 데이터)·기준점은 찾았다
                    searchFailed -> "${NearbyPhrases.SEARCH_FAILED} ${NearbyPhrases.resultSummary(result)}"
                    searchPartial -> "${NearbyPhrases.resultSummary(result)} ${NearbyPhrases.SEARCH_PARTIAL}"
                    else -> NearbyPhrases.resultSummary(result)
                }
                // TalkBack 없이 쓰는 사람에게 화면 배치를 한 번 알려 준다 — 박스·버튼이 어디 있는지 볼 수 없다.
                // (TalkBack 사용자는 스와이프로 순서대로 듣고, 목록 정보로 몇 곳인지도 듣는다)
                if (!layoutDescribed && !speaker.isScreenReaderOn()) {
                    layoutDescribed = true
                    summary += " ${layoutHint(result.places.size)}"
                }
                if (heading.hasSensor && heading.isUnreliable && !calibratePrompted) {
                    calibratePrompted = true
                    summary += " ${compassAdvice()}"
                }
                announceResults(locationMsg, summary)
            } finally {
                slowNotice.cancel()
                if (generation == searchGeneration) setSearching(false)
            }
        }
    }

    /**
     * 휴대폰의 위치 기능이 꺼져 있다(찾기 전에 확인했거나, 찾는 도중에 꺼졌다).
     *
     * 예전에는 "위치를 켠 뒤 다시 시도해 주세요"만 말했다 — 화면을 볼 수 없으면 빠른 설정의 위치 아이콘을
     * 혼자 찾기 어려워 막다른 길이었다. 이제 위치를 켜는 시스템 창을 띄워 버튼 하나로 켜게 한다.
     *  - 창은 사용자가 시작한 검색마다 한 번만([offerDialog]). 닫았는데 또 뜨면 빠져나갈 수 없다.
     *  - 창을 띄울 수 없으면(Play 서비스 없음·해결할 수 없는 실패) 예전처럼 말로만 안내한다.
     *  - 확인은 비동기다. 그 사이 화면을 떠났으면 남의 화면 위로 창·안내를 띄우지 않고, 돌아오면 처음부터
     *    다시 확인한다([refreshOnReturn]).
     */
    private fun onLocationOff(offerDialog: Boolean) {
        // 지난 결과를 비운다 — 남겨 두면 TalkBack 이 제목에서 옛 위치 설명을 읽고,
        // 옛 박스를 누르면 옛 위치 기준 방향을 말한다.
        stopBeacon()
        clearPlaces()
        locationContext = null
        fix = null
        tvAddress.text = "위치 기능 꺼짐"
        // 빠른 설정에서 켜고 돌아오면 알아서 다시 찾는다([refreshIfLocationTurnedOn]).
        locationOffFailed = true
        if (!offerDialog) {
            say(NearbyPhrases.LOCATION_OFF)
            return
        }
        val checkId = locationCheckId
        // 활동 범위 리스너(addOnCompleteListener(this))는 쓰지 않는다 — onStop 에서 떼어져, 그 사이
        // 떠났다 돌아오면 결과가 영영 안 와 아무 말도 없다. 대신 아래에서 직접 확인한다.
        sampler.checkLocationSettings().addOnCompleteListener { task ->
            // 그 사이 새로 찾기 시작했거나 창 결과가 왔다 — 옛 확인으로 창을 띄우지 않는다.
            if (checkId != locationCheckId || isFinishing || isDestroyed) return@addOnCompleteListener
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                refreshOnReturn = true
                return@addOnCompleteListener
            }
            val resolvable = task.exception as? ResolvableApiException
            when {
                // 확인하는 사이 켜졌다
                task.isSuccessful && sampler.isLocationEnabled() ->
                    refresh(offerLocationDialog = false, announce = LOCATION_TURNED_ON_ELSEWHERE)
                resolvable != null -> showLocationDialog(resolvable)
                else -> say(NearbyPhrases.LOCATION_OFF)
            }
        }
    }

    /**
     * 위치 켜기 창을 띄운다. 먼저 무엇이 뜨는지·어느 버튼인지 말한다 — 화면을 볼 수 없으면 창이 떴다는 것도
     * 모른다. 권한 창([requestLocationPermission])과 같은 방식: 안내를 시작하고 창을 띄우되, 창이 이 화면을
     * 가려도(onPause) 안내를 끊지 않는다([locationDialogShowing]).
     * TalkBack 사용자도 같은 말을 듣는다 — 어느 버튼이 켜기인지 미리 알면 창 안에서 덜 헤맨다.
     */
    private fun showLocationDialog(e: ResolvableApiException) {
        if (locationDialogShowing) return   // 이미 떠 있다 — 두 겹으로 띄우지 않는다
        say(LOCATION_DIALOG_HINT)
        locationDialogShowing = true
        try {
            locationSettingsLauncher.launch(IntentSenderRequest.Builder(e.resolution).build())
        } catch (ex: Exception) {
            // 창을 띄우지 못했다 — 말로만 안내한다.
            Log.w(DIAG_TAG, "location settings dialog failed", ex)
            locationDialogShowing = false
            say(NearbyPhrases.LOCATION_OFF)
        }
    }

    private fun setSearching(on: Boolean) {
        searching = on
        updateRefreshButton()
        updateKeepScreenOn()
    }

    /**
     * [다시 찾기] 버튼의 글자 — 지금 누르면 무슨 일이 일어나는지 그대로 보이고 읽히게.
     *  - 찾는 중: "찾는 중"(흐리게). 눌러도 새로 찾지 않고 기다리라고 말한다.
     *  - 권한 영구 거부: "설정 열기" — 실제로 설정 화면이 열린다. "다시 찾기"를 눌렀더니 설정이 열리면 당황한다.
     */
    private fun updateRefreshButton() {
        btnRefresh.text = when {
            searching -> BUTTON_SEARCHING
            locationPermissionBlocked -> BUTTON_OPEN_SETTINGS
            else -> BUTTON_REFRESH
        }
        btnRefresh.alpha = if (searching) SEARCHING_ALPHA else 1f
    }

    /** 화면 배치 설명 (TalkBack 꺼짐, 첫 결과 때 한 번). 장소가 없으면 버튼 위치만. */
    private fun layoutHint(placeCount: Int): String = buildString {
        if (placeCount > 0) {
            append(LAYOUT_HINT_PLACES)
            // 한 화면에 3곳 넘게는 잘 안 들어온다 — 밀어야 보인다는 것까지.
            if (placeCount > LAYOUT_HINT_MORE_THRESHOLD) append(" ").append(LAYOUT_HINT_MORE)
            append(" ")
        }
        append(LAYOUT_HINT_BUTTONS)
        // 볼륨 아래 키 다시 듣기는 TalkBack 꺼짐에서만 먹는다(VolumeDownRepeat) — 이 설명도 TalkBack 꺼짐에서만
        // 나오므로 조건이 같다. 예전엔 이 화면 어디에서도 알려 주지 않아, 놓친 안내를 다시 들을 길을 몰랐다.
        append(" ").append(LAYOUT_HINT_REPEAT_KEY)
    }

    /**
     * 검색 결과 안내.
     *
     *  - TalkBack 꺼짐: 앱 음성으로 "위치 설명 + 요약" 한 번에.
     *  - TalkBack 켜짐: **위치 제목으로 접근성 포커스를 옮겨** 위치 설명을 읽히고(제목의 설명 = 전체 문장),
     *    요약은 상태 줄(라이브 리전)로 그 뒤에 이어 읽힌다.
     *    왜: [다시 찾기]를 눌렀으니 포커스는 맨 아래 버튼에 남아 있다. 그대로 두면 박스로 가려면
     *    거꾸로 스와이프해야 하고, 위치 설명을 상태 줄에도 넣으면 같은 말을 두 번 듣는다.
     *    포커스를 맨 위로 올리면 다음 스와이프가 곧 첫 장소 박스다(상태 줄은 목록 아래에 있다).
     *    (포커스 낭독이 먼저 시작되고, 라이브 리전은 순서를 기다렸다 읽힌다 — 그래서 포커스를 먼저 건다)
     */
    private fun announceResults(locationMsg: String, summary: String) {
        if (!speaker.isScreenReaderOn()) {
            say("$locationMsg $summary")
            // 다시 듣기(볼륨 아래 키): 위치 설명의 기준점 "○시 방향"은 그 순간의 방향으로 새로 만든다.
            freshRepeat = { sayResultsAgain(summary) }
            return
        }
        WindowFocus.runWhenFocused(tvAddress) {
            // 이미 제목에 포커스가 있으면 TalkBack 이 다시 읽지 않는다 — 한 번 풀었다가 다시 건다.
            tvAddress.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
            tvAddress.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            say(summary)
        }
    }

    /** 결과 안내를 지금 방향으로 다시. 위치 설명이 없어졌으면(다시 찾는 중 등) false. */
    private fun sayResultsAgain(summary: String): Boolean {
        val locationMsg = currentLocationMessage() ?: return false
        say("$locationMsg $summary")
        freshRepeat = { sayResultsAgain(summary) }
        return true
    }

    // ==================== 박스 ====================

    private fun clearPlaces() {
        placesContainer.removeAllViews()
        cardByPlace.clear()
    }

    private fun renderPlaces(result: NearbyResult) {
        clearPlaces()
        result.places.forEachIndexed { index, place ->
            placesContainer.addView(makeCard(place, index))
        }
    }

    private fun makeCard(place: NearbyPlace, index: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(112)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            // 테두리는 [applyCardState] 가 그린다 — 평소 회색, 진동 중이면 굵은 노랑.
            background = GradientDrawable().apply {
                setColor(Color.rgb(0x1F, 0x1F, 0x1F))
                cornerRadius = dp(16).toFloat()
            }
            // 눌림 표시(물결) — 저시력자가 눌렸는지 눈으로 확인할 수 있게. 둥근 모서리 밖으로 번지지 않게 자른다.
            foreground = pressedFeedback()
            clipToOutline = true
            isClickable = true
            isFocusable = true
            // 박스 전체를 한 덩어리로 읽힌다: "CU 필동점, 편의점, 약 8미터"
            contentDescription = NearbyPhrases.placeLabel(place)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
            setOnClickListener { onPlaceTapped(place) }
        }
        // TalkBack: "버튼, 두 번 탭하면 방향과 거리 듣기" (진동 중이면 "… 방향 찾기 진동 끄기")
        ViewCompat.setAccessibilityDelegate(card, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
                // 두 번 탭이 지금 무엇을 하는지 그대로 — 같은 박스를 다시 누르면 진동이 꺼진다.
                info.addAction(
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        AccessibilityNodeInfoCompat.ACTION_CLICK,
                        if (isBeaconOn(place)) ACTION_LABEL_BEACON_OFF else ACTION_LABEL_DIRECTION,
                    )
                )
                // 목록 안 위치 — "N개 중 M번째"
                info.setCollectionItemInfo(
                    AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(index, 1, 0, 1, false)
                )
            }
        })
        // TalkBack 동작 메뉴: 진동을 켜고 끄지 않고 지금 방향만 다시 듣는다.
        // (진동 중에 방향을 다시 들으려고 박스를 누르면 진동이 꺼져 버렸다)
        ViewCompat.addAccessibilityAction(card, ACTION_LABEL_REPEAT_DIRECTION) { _, _ ->
            describePlaceNow(place, withBeaconHint = false)
            true
        }

        card.addView(TextView(this).apply {
            text = place.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        card.addView(TextView(this).apply {
            text = "${place.category} · ${NearbyPhrases.distanceText(place.distanceM)}"
            setTextColor(Color.rgb(0xFF, 0xD7, 0x00))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        cardByPlace[place] = card
        applyCardState(card, isBeaconOn(place))
        return card
    }

    /** 테마의 눌림 물결(selectableItemBackground). 못 찾으면 null — 눌림 표시만 빠진다. */
    private fun pressedFeedback(): Drawable? {
        val tv = TypedValue()
        if (!theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true) || tv.resourceId == 0) {
            return null
        }
        return ContextCompat.getDrawable(this, tv.resourceId)
    }

    /** 모든 박스의 표시를 지금 진동 상태에 맞춘다 (진동 시작·끝·시간 종료·나침반 불량·다시 그리기). */
    private fun updateCardStates() {
        for ((place, card) in cardByPlace) applyCardState(card, isBeaconOn(place))
    }

    /**
     * 박스 하나의 진동 상태 표시.
     *  - 눈: 평소 3dp 회색 테두리, 진동 중이면 6dp 노랑 — 어느 장소를 가리키는지 저시력자도 보이게.
     *  - TalkBack: 상태 설명 "방향 찾기 진동 중" (바뀌면 TalkBack 이 박스 정보를 새로 읽어 두 번 탭 설명도 바뀐다).
     */
    private fun applyCardState(card: View, beaconOn: Boolean) {
        (card.background as? GradientDrawable)?.setStroke(
            dp(if (beaconOn) CARD_STROKE_ACTIVE_DP else CARD_STROKE_DP),
            if (beaconOn) CARD_STROKE_ACTIVE_COLOR else CARD_STROKE_COLOR,
        )
        ViewCompat.setStateDescription(card, if (beaconOn) STATE_BEACON_ON else null)
    }

    /**
     * 지금 정면 기준의 장소 방향 계산 결과.
     * @property clockHour null 이면 방향을 말하지 않는다 — 이유는 [reason]
     * @property bearingDeg 진동용 방위각. 방향을 말할 수 있을 때만 있다
     * @property compassUnreliable 나침반 불량 — 8자 보정 안내 대상(첫 값이 아직 안 온 경우는 아니다)
     */
    private class PlaceDirection(
        val clockHour: Int?,
        val reason: NearbyPhrases.NoDirectionReason?,
        val withinError: Boolean,
        val bearingDeg: Double?,
        val compassUnreliable: Boolean,
        /** 오차 원 안인 이유가 "보고된 오차가 커서"가 아니라 "가까워서"(바닥값 때문)인가 — 단서 문구가 다르다. */
        val closeRange: Boolean = false,
    )

    /**
     * 누른(또는 다시 듣는) 순간의 정면 방향으로 장소 방향을 계산한다.
     * **부수 효과 없음** — 진동을 켜고 끄지 않는다. 박스 탭·다시 듣기·"방향 다시 듣기"가 같이 쓴다.
     */
    private fun placeDirectionNow(place: NearbyPlace, f: FusedFix): PlaceDirection {
        // 거리가 위치 오차의 2배는 돼야 방향 오차가 ±30°(시계 한 칸) 안으로 들어온다.
        // (거리 = 오차면 최대 ±90° — "2시"가 실제로는 11시일 수도 있다)
        val withinError = place.distanceM < directionErrorM(f) * DIRECTION_MIN_DISTANCE_RATIO
        // 보고된 오차만으로는 오차 원 밖인데 바닥값 때문에 걸렸다 = 가까워서. (GPS 3m 인데 "오차가 커서"는 틀린 말)
        val closeRange = withinError && place.distanceM >= f.accuracyM * DIRECTION_MIN_DISTANCE_RATIO

        fun noDirection(reason: NearbyPhrases.NoDirectionReason, compassUnreliable: Boolean = false) =
            PlaceDirection(null, reason, withinError, null, compassUnreliable, closeRange)

        // 화면을 켠 채 걸어 다녔다면 박스의 기준 위치가 이미 멀어졌을 수 있다 — 방향을 지어내지 않는다.
        if (SystemClock.elapsedRealtime() - fixAtMs > STALE_FIX_ON_TAP_MS) {
            return noDirection(NearbyPhrases.NoDirectionReason.STALE_LOCATION)
        }
        if (!heading.hasSensor) return noDirection(NearbyPhrases.NoDirectionReason.NO_SENSOR)
        if (heading.isUnreliable) return noDirection(NearbyPhrases.NoDirectionReason.UNRELIABLE, compassUnreliable = true)
        // 센서는 있는데 아직 첫 값이 안 왔다 — 방향을 지어내지 않는다.
        val h = heading.currentTrueHeadingDeg() ?: return noDirection(NearbyPhrases.NoDirectionReason.UNRELIABLE)
        val bearing = initialBearingDeg(f.lat, f.lon, place.lat, place.lon)
        return PlaceDirection(clockHour(relativeBearingDeg(bearing, h)), null, withinError, bearing, false, closeRange)
    }

    /**
     * 계산한 방향으로 장소 안내 문장을 만든다. 8자 보정 안내는 화면당 한 번만 붙인다.
     * @param beaconWithheld 진동을 쓸 수 있는데 방향이 불확실해 일부러 안 켰다 — 그 사실을 말한다
     *   (말없이 안 켜면 진동을 써 본 사람은 고장 난 줄 안다).
     */
    private fun placeDetailMessage(
        place: NearbyPlace,
        d: PlaceDirection,
        beaconHint: Boolean,
        beaconWithheld: Boolean = false,
    ): String {
        var message = NearbyPhrases.placeDetail(
            place, d.clockHour, d.reason, d.withinError,
            beaconStarted = beaconHint, closeRange = d.closeRange, beaconWithheld = beaconWithheld,
        )
        if (d.compassUnreliable && !calibratePrompted) {
            calibratePrompted = true
            message += " ${compassAdvice()}"
        }
        return message
    }

    /** 장소 안내를 말한다. 다시 듣기는 이 장소를 **그때의 방향으로** 새로 계산한다. */
    private fun sayPlaceDetail(place: NearbyPlace, message: String) {
        speaker.speak(message)
        freshRepeat = { describePlaceNow(place, withBeaconHint = true) }
    }

    /**
     * 장소 안내를 지금 방향으로 다시 말한다. **진동은 켜거나 끄지 않는다.**
     * @param withBeaconHint 이 장소로 진동 중이면 진동 안내(자세·끄는 법)도 다시 붙인다(볼륨 다시 듣기).
     *   TalkBack "방향 다시 듣기"는 붙이지 않는다 — 박스의 상태 설명·두 번 탭 설명이 이미 알려 준다.
     * @return 말했으면 true (위치가 없어졌으면 false)
     */
    private fun describePlaceNow(place: NearbyPlace, withBeaconHint: Boolean): Boolean {
        val f = fix ?: return false
        val d = placeDirectionNow(place, f)
        sayPlaceDetail(place, placeDetailMessage(place, d, beaconHint = withBeaconHint && isBeaconOn(place)))
        return true
    }

    /** 박스를 눌렀다 — 진동을 켜거나(같은 박스면 끄고) 누른 순간의 정면 방향으로 시계 방향을 말한다. */
    private fun onPlaceTapped(place: NearbyPlace) {
        val f = fix ?: return

        // 같은 장소를 다시 누르면 방향 찾기 진동을 멈춘다 (켜고 끄는 방법이 하나라 외우기 쉽다).
        if (isBeaconOn(place)) {
            stopBeacon()
            say(NearbyPhrases.BEACON_STOPPED)
            return
        }
        // 다른 장소를 누르면 이전 진동은 끝 — 두 곳을 동시에 가리키면 헷갈린다.
        stopBeacon()

        val d = placeDirectionNow(place, f)
        // 방향 찾기 진동은 방향을 믿을 수 있을 때만 — 오차 원 안(아주 가까움)이면 방향이 사실상
        // 무작위라 진동이 엉뚱한 쪽을 가리킨다.
        // 진동 모터가 없으면 "진동이 울립니다"라고 약속하지 않는다.
        val beaconBearing = d.bearingDeg?.takeIf { !d.withinError && beacon.canVibrate }
        if (beaconBearing != null) {
            beacon.start(beaconBearing)
            beaconPlace = place
            updateCardStates()
            updateKeepScreenOn()
        }
        val withheld = beaconBearing == null && d.clockHour != null && d.withinError && beacon.canVibrate
        sayPlaceDetail(place, placeDetailMessage(place, d, beaconHint = beaconBearing != null, beaconWithheld = withheld))
    }

    /**
     * 가까운 보행 신호등(서울시 데이터) → 박스용 장소.
     *
     * 한 횡단보도 모퉁이에 신호등이 여러 개 서 있으므로 [SIGNAL_GROUP_M] 안은 하나로 묶고,
     * 최대 [SIGNAL_MAX_BOXES] 개만. 대개 "내 쪽 신호등"과 "건너편 신호등"이 남는다 —
     * 건너편 것이 곧 신호등 카메라로 비춰야 할 방향이다.
     *
     * ⚠️ 데이터가 닿지 않는 곳(서울 밖·데이터 공백)이면 **빈 목록**이다. "신호등이 없다"는 뜻이 아니므로
     *    그렇게 말하지 않는다 (iOS PedestrianSignalIndex 와 같은 원칙).
     */
    private fun nearbyPedSignals(index: PointGridIndex?, f: FusedFix): List<POIResult> {
        if (index == null || !index.isInsideCoverage(f.lat, f.lon)) return emptyList()
        val kept = mutableListOf<GeoPoint>()
        for ((p, _) in index.nearest(f.lat, f.lon, SIGNAL_SEARCH_RADIUS_M)) {
            if (kept.any { distanceBetween(it.lat, it.lon, p.lat, p.lon) <= SIGNAL_GROUP_M }) continue
            kept += p
            if (kept.size >= SIGNAL_MAX_BOXES) break
        }
        return kept.map { POIResult(SIGNAL_NAME, it.lat, it.lon, "", category = SIGNAL_CATEGORY) }
    }

    /** 방향 판단("오차 원 안인가")에 쓰는 위치 오차 — 보고 오차를 믿되 [DIRECTION_ACCURACY_FLOOR_M] 밑으로는 보지 않는다. */
    private fun directionErrorM(f: FusedFix): Float = max(f.accuracyM, DIRECTION_ACCURACY_FLOOR_M)

    /**
     * 지금 정면 기준 시계 방향. 나침반이 없거나 불량이거나, 대상이 위치 오차 원 안이면 null —
     * 그때는 방향을 지어내지 않고 거리만 말한다.
     */
    private fun clockHourTo(f: FusedFix, lat: Double, lon: Double, distanceM: Float): Int? {
        if (!heading.hasSensor || heading.isUnreliable) return null
        if (distanceM < directionErrorM(f) * DIRECTION_MIN_DISTANCE_RATIO) return null
        val h = heading.currentTrueHeadingDeg() ?: return null
        return clockHour(relativeBearingDeg(initialBearingDeg(f.lat, f.lon, lat, lon), h))
    }

    /**
     * 실기 진단용 — 키워드마다 가장 가까운 3곳과 거리를 logcat 에 남긴다 (디버그 빌드만).
     * "주변 장소가 없다"가 또 나오면 `adb logcat -s NEARBY_DIAG` 로 원인을 바로 본다:
     *   n=0 이면 검색 자체가 비었고, n>0 인데 거리가 수백 m 면 TMap 정렬/반경 문제다.
     */
    private fun logSearchResults(f: FusedFix, groups: List<Pair<String, List<POIResult>?>>) {
        if (!BuildConfig.DEBUG) return
        Log.d(DIAG_TAG, "fix=${f.lat},${f.lon} acc=${f.accuracyM}m samples=${f.sampleCount}")
        for ((keyword, pois) in groups) {
            if (pois == null) {
                Log.d(DIAG_TAG, "keyword=$keyword FAILED")
                continue
            }
            val nearest = pois
                .map { it to distanceBetween(f.lat, f.lon, it.lat, it.lon) }
                .sortedBy { it.second }
                .take(3)
                .joinToString { (p, d) -> "${p.name}(${d.toInt()}m,${p.category ?: "-"})" }
            Log.d(DIAG_TAG, "keyword=$keyword n=${pois.size} nearest=$nearest")
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        /**
         * 카테고리별로 따로 검색한다 — 한 번에 묶으면 하나가 0건일 때 나머지까지 안 나온다는 보고가 있다.
         * 시각장애인 보행에서 기준점이 되는 곳 위주. 늘리면 호출 수가 그만큼 는다.
         */
        private val SEARCH_CATEGORIES = listOf(
            "편의점", "카페", "음식점", "약국", "병원", "은행", LocationAnchors.KEYWORD_BUS_STOP, "지하철역",
        )

        /** 위치 설명(기준점)용으로만 검색하는 키워드. 결과는 걸러서 박스에도 넣는다. */
        private val ANCHOR_ONLY_KEYWORDS = listOf(
            LocationAnchors.KEYWORD_SUBWAY_EXIT, LocationAnchors.KEYWORD_INTERSECTION,
        )

        private const val DIAG_TAG = "NEARBY_DIAG"

        /**
         * 방향을 믿고 말할 수 있는 최소 거리 = 위치 오차 × 이 값.
         * 방향 오차 ≈ asin(오차 / 거리) — 2배면 30°(시계 한 칸), 1배면 90°.
         * 이보다 가까우면 기준점은 거리만, 박스는 단서를 붙여 말하고 방향 진동은 켜지 않는다.
         */
        private const val DIRECTION_MIN_DISTANCE_RATIO = 2f

        /**
         * 방향 판단에 쓰는 위치 오차의 바닥값(m).
         * 도심 빌딩 사이에서는 OS 가 "오차 5m"라고 해도 실제로는 15~30m 틀리는 일이 흔하다 — 멀티패스 오차는
         * 픽스끼리 같은 쪽으로 틀려 흩어짐이 작아 보이기 때문. 5m 를 믿으면 10m 앞 장소에도 단서 없이 방향을
         * 말하고 진동까지 켠다. (검색 반경·오차 경고는 보고 오차 그대로 — 여기는 "방향을 믿을까"만)
         */
        private const val DIRECTION_ACCURACY_FLOOR_M = 10f

        // 박스 테두리 — 평소 3dp 회색(#9E9E9E: 검은 바탕 대비 3:1 이상. 예전 2dp #555555 는 거의 안 보였다),
        // 진동 중이면 6dp 노랑(이 앱의 강조색).
        private const val CARD_STROKE_DP = 3
        private const val CARD_STROKE_COLOR = 0xFF9E9E9E.toInt()
        private const val CARD_STROKE_ACTIVE_DP = 6
        private const val CARD_STROKE_ACTIVE_COLOR = 0xFFFFD700.toInt()

        // 박스 접근성 문구
        private const val STATE_BEACON_ON = "방향 찾기 진동 중"
        private const val ACTION_LABEL_DIRECTION = "방향과 거리 듣기"
        private const val ACTION_LABEL_BEACON_OFF = "방향 찾기 진동 끄기"
        private const val ACTION_LABEL_REPEAT_DIRECTION = "방향 다시 듣기"

        // [다시 찾기] 버튼 글자 — updateRefreshButton() 참조
        private const val BUTTON_REFRESH = "다시 찾기"
        private const val BUTTON_SEARCHING = "찾는 중"
        private const val BUTTON_OPEN_SETTINGS = "설정 열기"
        private const val SEARCHING_ALPHA = 0.6f

        /** 검색이 이만큼 길어지면 한 번 "아직 찾고 있습니다". */
        private const val SLOW_SEARCH_NOTICE_MS = 7_000L
        private const val STILL_SEARCHING = "아직 찾고 있습니다."
        private const val STILL_SEARCHING_TAP = "아직 찾는 중입니다. 잠시만 기다려 주세요."

        /** 화면 이름은 제목·화면 설명과 같게 "내 위치와 주변 장소" — 이름이 다르면 다른 화면인 줄 안다. */
        private const val RETURNED_FROM_CAMERA = "내 위치와 주변 장소 화면으로 돌아왔습니다."

        /** 다시 듣기로 들려줄 말이 아직 없을 때. */
        private const val SCREEN_DESCRIPTION =
            "내 위치와 주변 장소 화면입니다. 다시 찾기를 누르면 위치를 다시 확인합니다."

        // 화면 배치 설명 (TalkBack 꺼짐, 첫 결과 때 한 번) — layoutHint()
        private const val LAYOUT_HINT_PLACES = "장소는 화면 가운데에 가까운 순서로 있습니다."
        private const val LAYOUT_HINT_MORE = "위로 밀면 더 있습니다."
        private const val LAYOUT_HINT_BUTTONS = "맨 아래 왼쪽은 다시 찾기, 오른쪽은 신호등 카메라 버튼입니다."
        private const val LAYOUT_HINT_REPEAT_KEY = "소리 줄이는 버튼을 짧게 누르면 다시 들을 수 있습니다."
        private const val LAYOUT_HINT_MORE_THRESHOLD = 3

        // 위치 기능 켜기 창 (Google 위치 설정 창 — 안드로이드 전용이라 shared 의 NearbyPhrases 가 아니라 여기에 둔다)
        /** 창의 버튼 배치(두 개, 켜기가 오른쪽)는 LocationSampler.checkLocationSettings 의 setAlwaysShow(true) 창 기준. */
        private const val LOCATION_DIALOG_HINT =
            "휴대폰 위치가 꺼져 있습니다. 위치를 켜는 창을 띄웁니다. 두 버튼 중 오른쪽 버튼을 누르면 위치가 켜집니다. 혼자 찾기 어려우면 주변 분께 부탁해 주세요."

        /** 창에서 켰다 — 버튼을 또 누르게 하지 않고 바로 다시 찾는다. */
        private const val LOCATION_TURNED_ON = "위치를 켰습니다. 다시 찾습니다."

        /** 창 밖(빠른 설정 등)에서 켜고 돌아왔다. */
        private const val LOCATION_TURNED_ON_ELSEWHERE = "위치가 켜졌습니다. 다시 찾습니다."

        // 위치 권한 안내 (안드로이드 권한 흐름 전용이라 shared 의 NearbyPhrases 가 아니라 여기에 둔다)
        // 영구 거부일 때는 버튼 글자가 "설정 열기"다 — 안내도 그 이름으로 부른다.
        private const val LOCATION_DENIED =
            "위치 권한이 필요합니다. 다시 찾기를 누르면 권한을 다시 요청합니다."
        private const val LOCATION_APPROXIMATE =
            "위치가 대략적으로만 허용되어 있어 주변 안내를 할 수 없습니다. 다시 찾기를 눌러 정확한 위치를 허용해 주세요."
        private const val LOCATION_BLOCKED =
            "위치 권한이 거부되어 있어 다시 물을 수 없습니다. 설정 열기를 누르면 설정 화면이 열립니다. 권한에서 위치를 허용해 주세요."
        private const val LOCATION_BLOCKED_APPROXIMATE =
            "위치가 대략적으로만 허용되어 있습니다. 설정 열기를 누르면 설정 화면이 열립니다. 권한의 위치 항목에서 정확한 위치 사용을 켜 주세요."
        private const val OPENING_SETTINGS =
            "설정 화면을 엽니다. 권한에서 위치를 허용한 뒤 돌아오세요."

        /** TalkBack 없이 설정 화면을 다루기 어려운 사람용 — 도움 청하기와 돌아오는 법. */
        private const val OPENING_SETTINGS_HELP =
            " 혼자 하기 어려우면 주변 분께 SafeWalk의 위치 권한을 켜 달라고 부탁하세요. 뒤로 가기를 누르면 이 화면으로 돌아옵니다."

        /**
         * 위치 권한 창의 실제 버튼 이름·위치. 안드로이드 10 이상은 "앱 사용 중에만 허용"이 맨 위에 세로로,
         * 9 이하는 "거부 / 허용"이 가로로 놓인다(minSdk 26 이라 둘 다 만난다).
         * 혼자 못 찾을 때 도움을 청하는 법도 붙인다 — 부탁받은 사람이 누를 버튼 이름을 그대로 말할 수 있게.
         * (버튼 이름이 바뀌는 경계는 앞 문장과 같은 안드로이드 10 — 두 문장이 다른 이름을 말하지 않게)
         */
        private fun permissionDialogHint(): String =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                "곧 위치 권한을 묻는 창이 뜹니다. 버튼 중 맨 위의 '앱 사용 중에만 허용'을 눌러 주세요. " +
                    "혼자 찾기 어려우면 주변 분께 '앱 사용 중에만 허용'을 눌러 달라고 부탁해 주세요."
            } else {
                "곧 위치 권한을 묻는 창이 뜹니다. 오른쪽 아래의 '허용'을 눌러 주세요. " +
                    "혼자 찾기 어려우면 주변 분께 '허용'을 눌러 달라고 부탁해 주세요."
            }
        private const val STATE_SENT_TO_SETTINGS = "sentToSettings"
        private const val STATE_PERMISSION_BLOCKED = "locationPermissionBlocked"

        /** TMap 동시 요청 상한 — OkHttp 의 서버당 동시 요청(5)보다 작게. refresh() 의 주석 참조. */
        private const val MAX_CONCURRENT_REQUESTS = 4

        /** 검색 전체 마감(줄 서는 시간 포함). 요청 하나의 제한(10초)보다 조금 길게. */
        private const val SEARCH_DEADLINE_MS = 12_000L

        /** 다른 화면에 다녀왔을 때 이보다 오래된 위치면 다시 찾는다 (보통 걸음으로 약 70m). */
        private const val STALE_FIX_ON_RETURN_MS = 60_000L

        /** 박스를 눌렀을 때 이보다 오래된 위치면 방향을 말하지 않는다. 제자리에서 목록을 천천히 듣는 시간은 허용. */
        private const val STALE_FIX_ON_TAP_MS = 180_000L

        // 보행 신호등 박스
        private const val SIGNAL_NAME = "보행 신호등"

        /** 박스에서 "보행 신호등, 횡단보도, 약 N미터"로 읽힌다 (예전 "횡단보도 신호등"은 "신호등"이 겹쳤다). */
        private const val SIGNAL_CATEGORY = "횡단보도"
        private const val SIGNAL_SEARCH_RADIUS_M = 100.0
        private const val SIGNAL_GROUP_M = 15f
        private const val SIGNAL_MAX_BOXES = 3
    }
}
