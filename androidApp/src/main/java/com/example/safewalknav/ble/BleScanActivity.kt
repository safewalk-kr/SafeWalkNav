package com.example.safewalknav.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.view.ViewCompat
import com.example.safewalknav.R
import com.example.safewalknav.a11y.AppSettings
import com.example.safewalknav.a11y.AudioRouting
import com.example.safewalknav.a11y.PermissionGate
import com.example.safewalknav.a11y.Speaker
import com.example.safewalknav.a11y.VolumeDownRepeat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 기능3: 음향신호기 (준비 중) — **현재는 현장 조사 모드.**
 *
 * 이름에 "켜기"를 쓰지 않는다: 아직 켤 수 없는데 켜 준다고 약속하면, 교차로에서 신호기가 울리기를
 * 믿고 기다리게 된다. 화면 제목·홈 버튼·안내 모두 "음향신호기 (준비 중)"으로 맞춘다.
 *
 * 왜 아직 켤 수 없나:
 *  - 휴대폰으로 켤 수 있는 건 블루투스(BLE)를 단 '스마트 음향신호기'뿐이다. 옛 기기는 전용
 *    무선 리모컨 신호만 받아 휴대폰으로는 원리적으로 켤 수 없다.
 *  - 스마트 음향신호기의 명령 형식은 경찰청 「시각장애인용 음향신호기 규격서」의 공용 프로토콜에
 *    정해져 있는데, 아직 확보하지 못했다. 공공 교통시설에 추측한 명령을 보내지 않는다.
 *
 * 그래서 지금은:
 *  - [다시 찾기] 를 누르면 10초 동안 주변 BLE 광고를 모아 이름·주소·신호 세기·서비스 UUID·제조사 ID·원본 바이트를 보여 주고
 *  - [기록 공유] 로 팀에 보낸다. 교차로마다 모으면 "서울에 스마트 음향신호기가 실제로 있는지",
 *    "어떤 이름으로 광고하는지"를 규격서 도착 전에 확인할 수 있다.
 *
 * 화면에 들어오기만 해서는 **권한을 묻지도, 찾지도 않는다**(2026-11). 홈 맨 아래 버튼이라 손끝이 가장 먼저
 * 닿아 잘못 들어오는 사람이 많은데, 예전엔 들어오자마자 권한 창이 두 번 떠서 쓸 수도 없는 기능의 창에 갇혔다.
 * 들어오면 "준비 중이라 켤 수 없다 + 뒤로 가는 법"만 말하고, 권한은 [다시 찾기] 를 누를 때 묻는다.
 *
 * ⚠️ 안드로이드는 앱당 30초에 startScan 5회까지만 허용한다(넘기면 조용히 결과가 안 온다).
 *    그래서 스캔 중에는 [다시 찾기] 가 새 스캔을 시작하지 않는다 — 버튼은 "찾는 중"으로 바뀌고,
 *    누르면 "아직 찾는 중입니다"라고만 말한다(비활성으로 두면 눌러도 아무 반응이 없어 멈춘 줄 안다).
 */
class BleScanActivity : AppCompatActivity() {

    /** 한 기기에 대해 모은 정보. */
    private class Seen(
        val address: String,
        var name: String?,
        var bestRssi: Int,
        var lastRssi: Int,
        var count: Int,
        val serviceUuids: MutableSet<String>,
        val manufacturerIds: MutableSet<Int>,
        var rawHex: String,
    )

    private lateinit var tvStatus: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var btnScan: Button
    private lateinit var btnShare: Button
    private lateinit var speaker: Speaker

    private val seen = LinkedHashMap<String, Seen>()
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var lastScanAt: Long = 0L

    private val stopRunnable = Runnable { stopScan(announce = true) }
    // 목록은 스캔이 끝났을 때 한 번만 그린다. 스캔 중 매초 다시 그리면 행이 통째로 바뀌어
    // TalkBack 포커스가 날아간다(읽던 줄을 잃음).

    /**
     * 볼륨 아래 짧게 = 마지막 안내 다시 듣기 (TalkBack 꺼진 사용자용 — 켜져 있으면 손대지 않음).
     * 아직 아무 말도 안 했으면 지금 화면 상태를 말한다.
     */
    private val volumeRepeat = VolumeDownRepeat(this) {
        if (!speaker.repeatLast()) speaker.speak(stateMessage())
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runOnUiThread { record(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            runOnUiThread { results.forEach { record(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                stopScan(announce = false)
                speaker.speak("블루투스 검색에 실패했습니다. 잠시 후 다시 찾기를 눌러 주세요.")
            }
        }
    }

    /**
     * 권한을 "다시 묻지 않음"으로 거부해 시스템이 더는 창을 띄우지 않는 상태.
     * 이때 버튼은 "설정 열기"가 되어 앱 설정 화면을 연다 — 같은 안내만 반복하면 빠져나갈 길이 없다.
     */
    private var permissionBlocked = false

    /** 설정 화면으로 보냈다 — 돌아왔을 때 권한이 생겼으면 바로 찾기 시작, 아니면 무엇이 빠졌는지 말한다. */
    private var sentToSettings = false

    /** 마지막 권한 요청 시각 — 창이 뜨지도 않고 즉시 거부됐는지 판단용([PermissionGate]). */
    private var permissionRequestedAt = 0L

    /** 우리가 띄운 권한 창이 떠 있다 — 그동안의 onPause 는 "떠남"이 아니다. */
    private var permissionDialogShowing = false

    /** 안내를 말하던 중에 화면을 떠났다 — 돌아오면 지금 상태를 다시 말해 준다. */
    private var speechCutOff = false

    /** 이 화면에서 [다시 찾기] 를 한 번이라도 눌렀다 — 그 전에는 권한 얘기를 꺼내지 않는다([stateMessage]). */
    private var scanRequested = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionDialogShowing = false
            if (hasScanPermissions()) {
                permissionBlocked = false
                // 권한 창을 닫은 직후 10초간 조용하면 멈춘 줄 안다 — 찾기 시작을 알린다.
                startScan(announceStart = true)
                return@registerForActivityResult
            }
            // 영구 거부 판정 — 창을 그냥 닫은 것은 영구 거부가 아니다(PermissionGate 주석).
            val missing = missingPermissions()
            // map 먼저 — any{}/all{} 로 바로 돌리면 중간에 멈춰, 나머지 권한의 거부 이력이 기록되지 않는다.
            // 하나라도 다시 물을 수 없으면 그 권한은 설정 화면에서만 켤 수 있다 → 설정으로 보낸다.
            val blocked = missing.map { PermissionGate.isBlocked(this, it, permissionRequestedAt) }
            permissionBlocked = blocked.any { it }
            updateScanButton()
            speaker.speak(permissionMessage())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ble_scan)
        title = "음향신호기 준비 중"

        // 볼륨 올리기가 벨소리가 아니라 안내 음성 볼륨을 움직이게.
        AudioRouting.bindVolumeKeys(this)

        tvStatus = findViewById(R.id.tvBleStatus)
        listContainer = findViewById(R.id.bleList)
        btnScan = findViewById(R.id.btnBleScan)
        btnShare = findViewById(R.id.btnBleShare)
        speaker = Speaker(this, tvStatus)

        // XML 의 accessibilityHeading 은 API 28+ 에서만 먹는다 — minSdk 26 기기에서도 제목으로 읽히게.
        ViewCompat.setAccessibilityHeading(findViewById<TextView>(R.id.tvBleTitle), true)

        btnScan.setOnClickListener {
            // 찾는 중에 또 시작하면 시스템 제한(30초에 5회)을 깎아 먹는다 — 지금 상태만 알려 준다.
            if (scanning) {
                speaker.speak(STILL_SCANNING)
                return@setOnClickListener
            }
            scanRequested = true
            ensurePermissionsThenScan()
        }
        btnShare.setOnClickListener { shareLog() }

        if (savedInstanceState == null) {
            // 들어오기만 해서는 권한 창도, 블루투스 조사도 시작하지 않는다(클래스 주석) — 할 수 없는 것과 나가는 길만.
            // 권한 창 예고는 [다시 찾기] 를 누를 때 한다(ensurePermissionsThenScan).
            speaker.speak(INTRO)
        } else {
            // 재생성(프로세스 복원 등) 때는 권한 창을 또 띄우지 않는다 — 떠 있던 창의 결과는 새 인스턴스로 온다.
            // 대신 상태를 되살리고 지금 어떤 상태인지 한 번 말한다(글씨는 저장되지 않아 빈 화면이 된다).
            sentToSettings = savedInstanceState.getBoolean(STATE_SENT_TO_SETTINGS, false)
            permissionBlocked = savedInstanceState.getBoolean(STATE_PERMISSION_BLOCKED, false)
            scanRequested = savedInstanceState.getBoolean(STATE_SCAN_REQUESTED, false)
            updateScanButton()
            if (!sentToSettings) {
                speaker.speak(stateMessage())
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SENT_TO_SETTINGS, sentToSettings)
        outState.putBoolean(STATE_PERMISSION_BLOCKED, permissionBlocked)
        outState.putBoolean(STATE_SCAN_REQUESTED, scanRequested)
    }

    /**
     * 지금 상태 한 마디(돌아왔을 때·재생성·다시 듣기용).
     * 아직 [다시 찾기] 를 누르지 않았으면 첫 안내 그대로 — 잘못 들어온 사람에게 묻지도 않은 권한 얘기를 하면
     * 무엇을 해야 할지 더 헷갈린다. 빠져나가는 길이 먼저다.
     */
    private fun stateMessage(): String = when {
        !scanRequested -> INTRO
        hasScanPermissions() -> RESTORED
        else -> permissionMessage()
    }

    override fun onResume() {
        super.onResume()
        // 시스템 설정에서 직접 권한을 켜고 돌아왔을 수도 있다 — 버튼 글씨("설정 열기")를 지금 상태로.
        updateScanButton()
        if (sentToSettings) {
            sentToSettings = false
            speechCutOff = false
            if (hasScanPermissions()) {
                permissionBlocked = false
                // 설정에서 돌아오자마자 조용히 10초가 흐르면 멈춘 줄 안다 — 찾기 시작을 알린다.
                startScan(announceStart = true)
            } else {
                // 설정에서 허용하지 않고(또는 하나만 허용하고) 돌아옴 — 예전엔 아무 말도 없었다.
                speaker.speak(permissionMessage())
            }
            return
        }
        // 안내를 듣다가 나갔다 돌아옴 — 조용하면 어느 화면인지 모른다. 지금 상태를 한 마디로.
        if (speechCutOff) {
            speechCutOff = false
            speaker.speak(stateMessage())
        }
    }

    /**
     * 홈 버튼·최근 앱·화면 끄기 → **즉시** 스캔과 말을 멈춘다(예전엔 말은 onStop 에서야 멈춰 앱 밖에서도 조금 더 들렸다).
     * 예외: 우리가 띄운 권한 창이 이 화면을 가린 경우(창을 설명하는 안내를 끊지 않게),
     *       우리가 연 설정 화면으로 가는 경우("허용한 뒤 돌아오세요"를 끝까지).
     */
    override fun onPause() {
        super.onPause()
        // 화면을 떠나면 스캔을 멈춘다 — 배터리, 그리고 백그라운드 스캔 제한.
        stopScan(announce = false)
        setKeepScreenOn(false)   // stopScan 이 이미 풀지만, 스캔 전 실패 경로까지 확실히
        if (!permissionDialogShowing && !sentToSettings) {
            if (speaker.isBusy()) speechCutOff = true
            speaker.stop()
        }
    }

    /** 안전망 — 권한 창이 떠 있는 동안 홈 버튼을 누른 경우(onPause 에서는 일부러 안 멈춤)가 여기서 걸린다. */
    override fun onStop() {
        super.onStop()
        if (!sentToSettings) {
            if (speaker.isBusy()) speechCutOff = true
            speaker.stop()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        speaker.shutdown()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        volumeRepeat.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        volumeRepeat.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)

    // ==================== 권한 ====================

    /**
     * 요청할 권한. targetSdk 31+ 에서는 FINE 만 따로 요청하면 시스템이 무시하므로 COARSE 를 함께 넣는다.
     * 스캔에 실제로 필요한 건 BLUETOOTH_SCAN(12+) 과 FINE 위치다 — [hasScanPermissions] 는 그것만 본다.
     */
    private fun requestedPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    private fun neededPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun granted(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun missingPermissions(): List<String> = neededPermissions().filterNot { granted(it) }

    private fun hasScanPermissions(): Boolean = missingPermissions().isEmpty()

    private fun onlyApproximateLocation(): Boolean =
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)

    /** 지금 빠진 권한에 맞는 안내. 무엇을 켜야 하는지 구체적으로 말한다. */
    private fun permissionMessage(): String {
        val missing = missingPermissions()
        val needsPrecise = onlyApproximateLocation()
        val needsNearby = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            Manifest.permission.BLUETOOTH_SCAN in missing
        return when {
            missing.isEmpty() -> RESTORED
            // 안드로이드 12+: 위치를 "대략적"으로만 허용함. 블루투스 검색에는 정확한 위치가 필요하다.
            // 영구 거부 상태면 설정의 위치 항목에서 "정확한 위치 사용"을 켜야 한다 — 그냥 "위치를 허용하세요"라고
            // 하면, 설정에는 이미 '허용'으로 보여서 무엇을 바꿔야 하는지 모른다.
            permissionBlocked && needsPrecise && !needsNearby -> PERMISSION_BLOCKED_APPROXIMATE
            permissionBlocked -> PERMISSION_BLOCKED
            needsPrecise && !needsNearby -> PERMISSION_APPROXIMATE
            else -> PERMISSION_DENIED
        }
    }

    /**
     * 곧 뜰 권한 창 예고(TalkBack 꺼진 사용자용). "허용을 누르세요"만으로는 어디가 허용인지 모른다 —
     * 버튼 위치로 말한다. 안드로이드 10+ 권한 창은 버튼이 세로로 쌓이고 맨 위가 허용 쪽이다.
     *  - 12+: 근처 기기 창과 위치 창이 **따로** 뜬다. 하나만 빠졌으면 창도 하나.
     *  - 10~11: 위치 창 하나, 맨 위가 '앱 사용 중에만 허용'.
     *  - 8~9: 버튼이 가로로 놓이고 '허용'이 오른쪽.
     *
     * 끝에 도움을 청하는 길을 붙인다 — 위치로 말해 줘도 손끝으로 창 버튼을 찾지 못하는 사람이 있다.
     * 부탁할 때 부를 버튼 이름은 실제 이름으로. 12+ 에서 근처 기기 창의 맨 위는 '허용'이지만, 위치 창의 맨 위는
     * '앱 사용 중에만 허용'이다 — 위치가 빠졌으면 그 이름을 말한다('허용'만 말하면 '허용 안함'과도 헷갈린다).
     */
    private fun permissionDialogHint(): String {
        val (where, allowLabel) = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val missing = missingPermissions()
                val where = if (missing.size >= 2) "곧 권한 창이 두 번 뜹니다. 두 번 모두 버튼 중 맨 위를 눌러 주세요."
                else "곧 권한 창이 뜹니다. 버튼 중 맨 위를 눌러 주세요."
                val needsLocation = missing.any {
                    it == Manifest.permission.ACCESS_FINE_LOCATION || it == Manifest.permission.ACCESS_COARSE_LOCATION
                }
                val needsNearby = missing.any { !it.startsWith("android.permission.ACCESS_") }
                val label = when {
                    needsLocation && needsNearby -> "허용'과 '앱 사용 중에만 허용"
                    needsLocation -> "앱 사용 중에만 허용"
                    else -> "허용"
                }
                where to label
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                "곧 위치 권한 창이 뜹니다. 버튼 중 맨 위의 '앱 사용 중에만 허용'을 눌러 주세요." to "앱 사용 중에만 허용"
            else -> "곧 위치 권한 창이 뜹니다. 오른쪽 아래의 허용을 눌러 주세요." to "허용"
        }
        return "$where 혼자 찾기 어려우면 주변 분께 '$allowLabel'을 눌러 달라고 부탁해 주세요."
    }

    /** [다시 찾기] 를 눌렀을 때만 부른다 — 화면에 들어오는 것만으로는 권한을 묻지 않는다(클래스 주석). */
    private fun ensurePermissionsThenScan() {
        when {
            hasScanPermissions() -> {
                permissionBlocked = false
                startScan(announceStart = true)
            }
            permissionBlocked -> {
                // TalkBack 없이 설정 화면의 권한 항목을 찾기는 매우 어렵다 — 도움을 청하는 길과 돌아오는 법을 함께.
                val help = if (speaker.isScreenReaderOn()) "" else OPENING_SETTINGS_HELP
                speaker.speak(OPENING_SETTINGS + help)
                sentToSettings = AppSettings.open(this)
            }
            else -> {
                // TalkBack 없이 쓰는 사람은 권한 창이 떴다는 것도 알 수 없다 — 미리 말해 둔다.
                // (TalkBack 사용자는 TalkBack 이 창을 읽는다)
                if (!speaker.isScreenReaderOn()) speaker.speak(permissionDialogHint())
                permissionRequestedAt = PermissionGate.markRequested()
                permissionDialogShowing = true   // 권한 창이 이 화면을 가려도(onPause) 위 안내는 끝까지 들려준다
                permissionLauncher.launch(requestedPermissions())
            }
        }
    }

    // ==================== 스캔 ====================

    /**
     * @param announceStart 사용자가 무언가를 눌러 시작한 경우 "찾고 있습니다"를 말한다.
     *   10초 동안 아무 말이 없으면 버튼이 안 눌린 줄 알고 또 누른다.
     */
    @SuppressLint("MissingPermission")
    private fun startScan(announceStart: Boolean = false) {
        if (scanning) return
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            speaker.speak("이 휴대폰은 블루투스를 지원하지 않습니다.")
            return
        }
        if (!adapter.isEnabled) {
            speaker.speak("블루투스가 꺼져 있습니다. 블루투스를 켠 뒤 다시 찾기를 눌러 주세요.")
            return
        }
        // neverForLocation 을 안 쓰므로, 휴대폰 위치 기능이 꺼져 있으면 시스템이 스캔 결과를 전부 버린다.
        // 그대로 두면 "찾지 못했습니다"라는 거짓 결과가 나간다.
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null || !LocationManagerCompat.isLocationEnabled(lm)) {
            speaker.speak("휴대폰의 위치 기능이 꺼져 있으면 주변 장치를 찾을 수 없습니다. 위치를 켠 뒤 다시 찾기를 눌러 주세요.")
            return
        }
        val s = adapter.bluetoothLeScanner
        if (s == null) {
            speaker.speak("블루투스 검색을 시작할 수 없습니다.")
            return
        }
        // 시스템 제한(앱당 30초에 5회)을 넘기면 오류 없이 결과만 안 온다 → "찾지 못했습니다"라는 거짓 결과.
        // 화면을 빠르게 들락날락해도 넘지 않게 프로세스 전체에서 센다.
        val waitMs = ScanBudget.waitBeforeNextStart(SystemClock.elapsedRealtime())
        if (waitMs > 0) {
            val sec = ((waitMs + 999) / 1000).coerceAtLeast(1)
            speaker.speak("블루투스 검색을 너무 자주 했습니다. ${sec}초 뒤에 다시 찾기를 눌러 주세요.")
            return
        }
        scanner = s
        seen.clear()
        listContainer.removeAllViews()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            s.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            speaker.speak("주변 장치를 찾으려면 근처 기기 권한이 필요합니다.")
            return
        } catch (e: IllegalStateException) {
            // 확인 직후 블루투스가 꺼짐(BT adapter is not turned ON)
            speaker.speak("블루투스가 꺼져 있습니다. 블루투스를 켠 뒤 다시 찾기를 눌러 주세요.")
            return
        }
        ScanBudget.recordStart(SystemClock.elapsedRealtime())
        scanning = true
        lastScanAt = System.currentTimeMillis()
        // 시작을 알리지 않는 경우엔 상태 글씨도 바꾸지 않는다 — 라이브 리전이라, 바꾸면 TalkBack 이
        // 읽던 안내를 끊는다. 버튼 글씨 "찾는 중"이 스캔 중이라는 표시가 된다.
        if (announceStart) speaker.speak(SCANNING)
        updateScanButton()
        // 10초 중에 화면이 꺼지면 onPause 로 스캔이 반쯤에서 멈춘다(짧은 화면 꺼짐 시간을 쓰는 사람이 많다).
        setKeepScreenOn(true)
        handler.postDelayed(stopRunnable, SCAN_DURATION_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(announce: Boolean) {
        handler.removeCallbacks(stopRunnable)
        if (!scanning) return
        scanning = false
        try {
            scanner?.stopScan(callback)
        } catch (_: Exception) {
            // 블루투스가 도중에 꺼졌거나 권한이 회수됨 — 멈추는 중이므로 무시
        }
        updateScanButton()
        setKeepScreenOn(false)   // 배터리 — 찾는 동안만 켜 둔다
        render()
        if (announce) {
            val n = seen.size
            speaker.speak(
                if (n == 0) "주변에서 블루투스 장치를 찾지 못했습니다."
                else "주변 블루투스 장치 ${n}개를 찾았습니다. 기록 공유를 누르면 팀에 보낼 수 있습니다."
            )
        }
    }

    /**
     * [다시 찾기] 버튼을 지금 상태에 맞춘다. 스캔 중에도 **끄지 않는다** — 비활성 버튼은 TalkBack 이
     * "사용할 수 없음"이라고만 하고, TalkBack 없이 누르면 아무 반응이 없어 앱이 멈춘 줄 안다.
     * 대신 글씨("찾는 중")와 흐림으로 보여 주고, 누르면 "아직 찾는 중입니다"라고 말한다.
     */
    private fun updateScanButton() {
        btnScan.text = when {
            scanning -> "찾는 중"
            // 누르면 권한 창이 아니라 설정 화면이 열린다 — 글씨가 그 사실을 말해야 놀라지 않는다.
            permissionBlocked && !hasScanPermissions() -> "설정 열기"
            else -> "다시 찾기"
        }
        btnScan.alpha = if (scanning) 0.6f else 1f
    }

    private fun setKeepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun record(result: ScanResult) {
        if (!scanning) return
        val rec = result.scanRecord
        val address = result.device.address ?: return
        val s = seen.getOrPut(address) {
            Seen(
                address = address,
                name = null,
                bestRssi = result.rssi,
                lastRssi = result.rssi,
                count = 0,
                serviceUuids = mutableSetOf(),
                manufacturerIds = mutableSetOf(),
                rawHex = "",
            )
        }
        s.count++
        s.lastRssi = result.rssi
        if (result.rssi > s.bestRssi) s.bestRssi = result.rssi
        // 광고에 실린 이름 — BluetoothDevice.getName() 과 달리 BLUETOOTH_CONNECT 권한이 필요 없다.
        rec?.deviceName?.takeIf { it.isNotBlank() }?.let { s.name = it }
        rec?.serviceUuids?.forEach { s.serviceUuids += it.uuid.toString() }
        rec?.manufacturerSpecificData?.let { sa ->
            for (i in 0 until sa.size()) s.manufacturerIds += sa.keyAt(i)
        }
        rec?.bytes?.let { s.rawHex = toHexTrimmed(it) }
    }

    // ==================== 표시 / 공유 ====================

    private fun sorted(): List<Seen> = seen.values.sortedByDescending { it.bestRssi }

    /**
     * 화면에는 신호가 센 [SCREEN_MAX_DEVICES] 개만 그린다. 번화가에서는 수백 대가 잡히는데, 전부 그리면
     * TalkBack 으로 끝없이 넘겨야 하고 화면도 버벅인다. 음향신호기는 가까이 있으니 앞쪽에 온다.
     * 기록 공유는 따로 [SHARE_MAX_DEVICES] 개까지 담는다.
     */
    private fun render() {
        listContainer.removeAllViews()
        val all = sorted()
        val shown = all.take(SCREEN_MAX_DEVICES)
        if (all.size > shown.size) {
            listContainer.addView(TextView(this).apply {
                text = if (all.size <= SHARE_MAX_DEVICES) {
                    "신호가 센 ${shown.size}개만 보여 줍니다. 전체 ${all.size}개는 기록 공유에 들어갑니다."
                } else {
                    // 공유도 상한이 있다 — "전체가 들어간다"고 하면 거짓이 된다.
                    "신호가 센 ${shown.size}개만 보여 줍니다. 전체 ${all.size}개 중 ${SHARE_MAX_DEVICES}개가 기록 공유에 들어갑니다."
                }
                setTextColor(Color.rgb(0xFF, 0xD7, 0x00))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(0, dp(4), 0, dp(10))
                // 누를 것이 아니다 — 클릭·키보드 포커스 대상에서 뺀다(TalkBack 은 글씨로 읽는다).
                isFocusable = false
                isClickable = false
            })
        }
        for (s in shown) {
            listContainer.addView(TextView(this).apply {
                text = buildString {
                    append(s.name ?: "(이름 없음)")
                    append("\n").append(s.address)
                    append("  ").append(s.bestRssi).append(" dBm  (").append(s.count).append("회)")
                    if (s.serviceUuids.isNotEmpty()) append("\nUUID: ").append(s.serviceUuids.joinToString())
                    if (s.manufacturerIds.isNotEmpty()) {
                        append("\n제조사 ID: ").append(s.manufacturerIds.joinToString { "0x%04X".format(it) })
                    }
                }
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(0, dp(10), 0, dp(10))
                isFocusable = true
                // TTS 는 "-67 dBm" 을 "마이너스 육십칠"로 읽어 뜻이 전해지지 않는다 — 세기를 말로.
                // 주소는 끝 5자리만, 한 글자씩(같은 이름의 기기를 구별할 만큼만).
                contentDescription =
                    "${s.name ?: "이름 없음"}, 주소 끝 ${addressTail(s.address)}, 신호 ${strengthWord(s.bestRssi)}"
            })
        }
    }

    /** "AA:BB:CC:DD:EE:FF" → "D E E F F" (띄어 써야 TTS 가 한 글자씩 읽는다). */
    private fun addressTail(address: String): String =
        address.replace(":", "").takeLast(5).toCharArray().joinToString(" ")

    private fun strengthWord(rssi: Int): String = when {
        rssi >= -60 -> "강함"
        rssi >= -80 -> "보통"
        else -> "약함"
    }

    private fun shareLog() {
        if (seen.isEmpty()) {
            speaker.speak("공유할 기록이 없습니다. 먼저 다시 찾기를 눌러 주세요.")
            return
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date(lastScanAt))
        // 번화가에서는 수백 대가 잡힌다. 전부 넣으면 인텐트가 커져(약 1MB 제한) 공유 창이 안 뜨고 앱이 죽는다.
        // 음향신호기는 가까이 있으니 신호가 센 순서로 앞쪽만 보낸다.
        val all = sorted()
        val shown = all.take(SHARE_MAX_DEVICES)
        val text = buildString {
            append("SafeWalk 음향신호기 BLE 조사\n")
            append("시각: ").append(stamp).append('\n')
            append("기기: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (Android ").append(Build.VERSION.RELEASE).append(")\n")
            append("장소: (직접 적어 주세요 — 교차로 이름·모퉁이)\n")
            if (all.size > shown.size) {
                append("(전체 ").append(all.size).append("대 중 신호가 센 ").append(shown.size).append("대)\n")
            }
            append('\n')
            for (s in shown) {
                append("- ").append(s.name ?: "(이름 없음)").append(" | ").append(s.address)
                append(" | best ").append(s.bestRssi).append(" dBm | n=").append(s.count).append('\n')
                if (s.serviceUuids.isNotEmpty()) append("  uuid: ").append(s.serviceUuids.joinToString()).append('\n')
                if (s.manufacturerIds.isNotEmpty()) {
                    append("  mfr: ").append(s.manufacturerIds.joinToString { "0x%04X".format(it) }).append('\n')
                }
                if (s.rawHex.isNotEmpty()) append("  raw: ").append(s.rawHex).append('\n')
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "SafeWalk BLE 조사 $stamp")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(send, "기록 공유"))
        } catch (_: Exception) {
            // 공유할 앱이 없음(ActivityNotFound) 등
            speaker.speak("기록을 공유할 수 없습니다. 메시지나 메일 앱이 있는지 확인해 주세요.")
        }
    }

    /** 광고 바이트를 16진수로. 뒤쪽 0 패딩은 잘라 읽기 쉽게. */
    private fun toHexTrimmed(bytes: ByteArray): String {
        var end = bytes.size
        while (end > 0 && bytes[end - 1].toInt() == 0) end--
        return (0 until end).joinToString("") { "%02X".format(bytes[it]) }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val SCAN_DURATION_MS = 10_000L
        private const val SHARE_MAX_DEVICES = 100
        private const val SCREEN_MAX_DEVICES = 30

        // 잘못 들어온 사람이 많은 화면 — 할 수 없다는 것과 나가는 길을 먼저, 조사는 버튼을 눌러야만 한다는 것을 끝에.
        // 버튼 이름은 [다시 찾기] 의 실제 글씨와 같게.
        private const val INTRO =
            "음향신호기는 아직 준비 중이라 켤 수 없습니다. 홈으로 돌아가려면 뒤로 가기를 누르세요. " +
                "블루투스 조사는 다시 찾기 버튼을 누를 때만 합니다."
        private const val SCANNING = "주변 장치를 찾고 있습니다. 약 10초 걸립니다."
        private const val STILL_SCANNING = "아직 찾는 중입니다."
        private const val PERMISSION_DENIED =
            "주변 장치를 찾으려면 근처 기기와 위치 권한이 필요합니다. 다시 찾기를 누르면 권한을 다시 요청합니다."
        private const val PERMISSION_APPROXIMATE =
            "위치가 대략적으로만 허용되어 있습니다. 블루투스 장치를 찾으려면 정확한 위치가 필요합니다. 다시 찾기를 눌러 정확한 위치를 허용해 주세요."
        // 이 상태에서는 버튼 글씨가 "설정 열기"다 — 안내도 그 이름으로 부른다.
        private const val PERMISSION_BLOCKED =
            "권한이 거부되어 있어 다시 물을 수 없습니다. 설정 열기를 누르면 설정 화면이 열립니다. 권한에서 근처 기기와 위치를 허용해 주세요."
        private const val PERMISSION_BLOCKED_APPROXIMATE =
            "위치가 대략적으로만 허용되어 있습니다. 설정 열기를 누르면 설정 화면이 열립니다. 권한의 위치 항목에서 정확한 위치 사용을 켜 주세요."
        private const val OPENING_SETTINGS =
            "설정 화면을 엽니다. 권한에서 근처 기기와 위치를 허용한 뒤 돌아오세요."
        /** TalkBack 꺼짐 전용 덧붙임 — 화면을 못 보면 설정의 권한 항목을 혼자 찾기 어렵다. */
        private const val OPENING_SETTINGS_HELP =
            " 혼자 하기 어려우면 주변 분께 SafeWalk의 근처 기기와 위치 권한을 켜 달라고 부탁하세요. 뒤로 가기를 누르면 이 화면으로 돌아옵니다."
        private const val RESTORED =
            "음향신호기 준비 중 화면입니다. 다시 찾기를 누르면 주변 블루투스 장치를 찾습니다."

        private const val STATE_SENT_TO_SETTINGS = "sentToSettings"
        private const val STATE_PERMISSION_BLOCKED = "permissionBlocked"
        private const val STATE_SCAN_REQUESTED = "scanRequested"
    }
}

/**
 * 안드로이드의 BLE 스캔 시작 제한(앱당 30초에 5회) 회계. 프로세스 전체에서 공유한다 —
 * 시스템은 앱 전체로 세므로, 화면을 들락날락하며 [다시 찾기] 를 누르면 화면 하나 안에서 세는 걸로는 부족하다.
 * 여유를 두고 4회로 센다(시스템이 세는 시작에는 우리가 모르는 재시도가 섞일 수 있다).
 */
internal object ScanBudget {
    private const val WINDOW_MS = 30_000L
    private const val MAX_STARTS = 4
    private val starts = ArrayDeque<Long>()

    /** 지금 시작해도 되면 0, 아니면 기다려야 할 ms. */
    fun waitBeforeNextStart(nowMs: Long): Long {
        while (starts.isNotEmpty() && nowMs - starts.first() >= WINDOW_MS) starts.removeFirst()
        if (starts.size < MAX_STARTS) return 0L
        return WINDOW_MS - (nowMs - starts.first())
    }

    fun recordStart(nowMs: Long) {
        starts.addLast(nowMs)
    }
}
