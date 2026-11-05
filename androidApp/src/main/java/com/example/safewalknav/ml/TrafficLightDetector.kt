package com.example.safewalknav.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * 보행자 신호등 색깔 검출기. ACTIVE_MODEL 상수 하나로 모델을 전환한다.
 *
 * KAIRESS_YOLOV5 : kairess/crosswalk-traffic-light-detection-yolov5 (GPL-3.0)
 *   한국 6,593장 학습, YOLOv5s6(P6) @640, 3 classes, 출력 [1,25500,8] (objectness 있음)
 * HYBRID_P2      : 자체 YOLOv11n + P2 head, 939장, 2 classes, 출력 [1,6,34000] (objectness 없음)
 *
 * 앱 계약: detect() 의 classId 는 항상 0=빨강, 1=초록. 모델이 무엇이든 불변.
 *
 * 스레드: 생성자는 메인 스레드 전용 API(Looper·View·Toast)를 쓰지 않는다 — assets 매핑과
 * Interpreter 생성뿐이라 백그라운드 executor 에서 만들어도 된다. [context] 는 생성 중에만 쓰고
 * 보관하지 않는다(Activity 누수 없음). 단, 생성한 스레드와 분석 스레드가 다르면 executor 제출·
 * Handler post 같은 happens-before 경로로 넘길 것 (`interpreter` 등은 final 이 아니다).
 */
class TrafficLightDetector(context: Context) {

    enum class ModelKind { KAIRESS_YOLOV5, HYBRID_P2 }

    // var: 추론 중 NNAPI 가 계속 실패하면 CPU 인터프리터로 갈아 끼운다([fallbackToCpu]).
    // 분석 스레드(단일)에서만 바뀌고 쓰이며, close() 는 그 스레드가 끝난 뒤에만 불린다.
    private var interpreter: Interpreter
    private val modelBuffer: ByteBuffer
    private var usingNnapi: Boolean
    private val spec: ModelSpec

    var confidenceThreshold: Float
    var iouThreshold: Float = 0.45f
    var diagnosticMode: Boolean = false

    @Volatile
    var lastStats: DetectionStats? = null
        private set

    /** 최근 프레임에 횡단보도(Zebra_Cross)가 보였는지. kairess 모델만 제공. */
    @Volatile
    var lastCrosswalkVisible: Boolean = false
        private set

    /** 최근 프레임에서 검출된 횡단보도의 화면 가로 중심(0~1). 안 보이면 -1. 정렬 안내용. */
    @Volatile
    var lastCrosswalkCenterX: Float = -1f
        private set

    /**
     * 조준 진동([com.example.safewalknav.AimingFeedback])이 겨눌 대상의 화면 가로 중심(0~1).
     * 없으면 -1.
     *
     * 우선순위: **약한 신호등 후보 > 횡단보도**.
     * 판정용 임계([confidenceThreshold], 0.25)보다 훨씬 낮은 [AIM_CONFIDENCE] 를 쓴다.
     * 조준은 "저기 신호등 비슷한 게 있다" 정도만 알면 충분하고, 틀려도 진동 박자가
     * 잠깐 어긋날 뿐 **안내는 나가지 않기** 때문이다. 안전 판정은 이 값을 절대 쓰지 않는다.
     *
     * 횡단보도 폴백을 뒤에 두는 이유: 횡단보도는 발 앞 바닥이라 거의 항상 화면 중앙에
     * 잡힌다. 그것만 보면 "이미 잘 맞췄다"는 잘못된 신호를 주게 된다. 신호등은 위·건너편에
     * 있어서 실제로 겨눠야 할 방향을 알려준다.
     */
    @Volatile
    var lastAimTargetCenterX: Float = -1f
        private set

    // ── 전처리 크롭 → 원본 프레임 환원 계수 ──
    // preprocess() 가 쓰고 decodeBbox() 가 읽는다. 둘 다 같은 detect() 호출 안에서,
    // 단일 분석 스레드에서만 실행되므로 동기화가 필요 없다.
    private var cropLeftNorm = 0f
    private var cropTopNorm = 0f
    private var cropWidthNorm = 1f
    private var cropHeightNorm = 1f

    // ── 재사용 버퍼 (입력 크기가 모델당 고정이라 한 번만 잡으면 된다) ──
    private var inputBuffer: ByteBuffer? = null
    private var pixelCache: IntArray? = null

    /** 입력 텐서를 bulk put 한 번에 채우기 위한 float 스테이징 (640×640×3 = 4.9MB, 한 번만 할당). */
    private var inputFloats: FloatArray? = null

    /**
     * 출력 텐서 수신 버퍼. **인터프리터에 딸린 것으로 취급한다** — [fallbackToCpu] 가 인터프리터를
     * 갈아 끼우면 null 로 비워, 다음 프레임에 새 인터프리터의 출력 텐서를 다시 검사하고 만든다.
     * 분석 스레드(단일)에서만 접근하므로 동기화 불필요.
     */
    private var outputBuffers: OutputBuffers? = null

    init {
        fun options(useNnapi: Boolean) = Interpreter.Options().apply {
            setNumThreads(4)
            @Suppress("DEPRECATION")
            setUseNNAPI(useNnapi)
        }

        // ACTIVE_MODEL 의 tflite 가 없으면 다른 모델로 폴백.
        // ⚠️ 2026-07 정리로 FALLBACK_MODEL 파일(safewalknav_tl.tflite)은 assets 에서 빠졌다.
        //    그래서 주 모델이 없으면 여기서 예외가 나고, MainActivity 가 "인식을 시작할 수 없습니다"를 말한다.
        //    (조용히 죽지는 않는다 — 폴백을 되살리려면 파일을 다시 넣을 것)
        var loadedSpec = SPECS.getValue(ACTIVE_MODEL)
        val buffer = try {
            loadMappedAsset(context, loadedSpec.assetFilename)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ $ACTIVE_MODEL 모델(assets/${loadedSpec.assetFilename}) 없음: ${e.message}")
            Log.w(TAG, "   → $FALLBACK_MODEL 로 폴백. export 후 assets/ 에 넣으십시오.")
            loadedSpec = SPECS.getValue(FALLBACK_MODEL)
            loadMappedAsset(context, loadedSpec.assetFilename)
        }

        spec = loadedSpec
        modelBuffer = buffer
        confidenceThreshold = spec.defaultConfidence
        // NNAPI 가속기 초기화가 실패하는 기기(드라이버 문제·일부 저가형)가 있다. 그때 예외로 기능
        // 전체가 죽지 않도록 CPU 로 한 번 더 시도한다 — 느려도 인식은 된다.
        var nnapi = true
        interpreter = try {
            Interpreter(buffer, options(useNnapi = true))
        } catch (e: Throwable) {
            Log.w(TAG, "NNAPI 로 인터프리터 생성 실패 → CPU 로 재시도: ${e.message}")
            nnapi = false
            buffer.rewind()
            Interpreter(buffer, options(useNnapi = false))
        }
        usingNnapi = nnapi

        val outShape = interpreter.getOutputTensor(0).shape().toList()
        Log.d(TAG, "Model=${spec.kind} in=${interpreter.getInputTensor(0).shape().toList()} out=$outShape conf=$confidenceThreshold")
        verifyOutputShape(outShape)
    }

    /** 코드 상수와 실제 텐서가 어긋나면 조용히 오작동한다 (빨강↔초록 뒤바뀜). 크게 경고. */
    private fun verifyOutputShape(outShape: List<Int>) {
        val expected = when (spec.kind) {
            ModelKind.KAIRESS_YOLOV5 -> listOf(spec.numAnchors, spec.numOutputChannels)
            ModelKind.HYBRID_P2 -> listOf(spec.numOutputChannels, spec.numAnchors)
        }
        val actual = listOf(outShape.getOrNull(1) ?: -1, outShape.getOrNull(2) ?: -1)
        if (actual != expected) {
            Log.e(TAG, "⚠️ 출력 shape 불일치! 기대=[1,${expected[0]},${expected[1]}] 실제=$outShape")
            Log.e(TAG, "   → numAnchors / numClasses 를 실제 모델에 맞추십시오.")
        } else {
            Log.d(TAG, "✅ 출력 shape 일치")
        }
    }

    /**
     * 추론이 예외로 끝났을 때 분석기가 부른다 — 지난 프레임의 조준 좌표·통계를 비운다.
     * 안 비우면 조준 진동이 옛 목표를 따라가고, "왼쪽으로"가 옛 위치로 반복되며,
     * 옛 신뢰도가 미탐지 안내를 최대 10초 막는다.
     * (detect() 시작에서 비우지 않는 이유: UI 스레드가 직전 결과를 아직 읽는 중일 수 있다)
     */
    fun clearLastResult() {
        lastAimTargetCenterX = -1f
        lastStats = null
    }

    /**
     * 추론(run)이 계속 실패할 때 분석기가 부른다 — NNAPI 를 쓰고 있었다면 CPU 인터프리터로 바꾼다.
     * 생성 때만 폴백하면, 만들어지긴 했는데 run() 에서 터지는 드라이버(실기에서 있다)는 영영 못 살린다.
     * @return 바꿨으면 true (이미 CPU 였거나 실패하면 false). **분석 스레드에서만** 부를 것.
     */
    fun fallbackToCpu(): Boolean {
        if (!usingNnapi) return false
        return try {
            modelBuffer.rewind()
            val cpu = Interpreter(modelBuffer, Interpreter.Options().apply {
                setNumThreads(4)
                @Suppress("DEPRECATION")
                setUseNNAPI(false)
            })
            try { interpreter.close() } catch (_: Exception) {}
            interpreter = cpu
            // 출력 버퍼는 옛 인터프리터의 출력 텐서를 기준으로 검사·할당한 것이다. 같은 모델이라
            // shape 는 같겠지만, 새 인터프리터 기준으로 다시 검사하도록 비운다 (1회 0.8MB 재할당).
            outputBuffers = null
            usingNnapi = false
            Log.w(TAG, "추론이 계속 실패해 NNAPI → CPU 로 전환")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "CPU 인터프리터 전환 실패", e)
            false
        }
    }

    /**
     * [bitmap] 은 **호출자 소유**다. detect() 는 이걸 recycle 하지 않고, 반환 뒤에 참조도 남기지 않는다
     * (픽셀은 [preprocess] 안에서 입력 버퍼로 복사가 끝난다). 그래서 호출자는 반환 직후 recycle 해도 된다.
     */
    fun detect(bitmap: Bitmap): List<TrafficLightDetection> {
        val t0 = System.nanoTime()
        val input = preprocess(bitmap)
        val th = if (diagnosticMode) DIAGNOSTIC_CONFIDENCE_THRESHOLD else confidenceThreshold

        val r = when (spec.kind) {
            ModelKind.KAIRESS_YOLOV5 -> runYolov5(input, th)
            ModelKind.HYBRID_P2 -> runYolov11(input, th)
        }

        lastStats = DetectionStats(
            inferenceMs = (System.nanoTime() - t0) / 1_000_000,
            rawCandidatesAboveThreshold = r.rawAboveThreshold,
            finalDetections = r.detections.size,
            peakConfidence = r.peakAll,
            peakConfRed = r.peakRed,
            peakConfGreen = r.peakGreen,
            confidenceThresholdUsed = th,
            diagnosticMode = diagnosticMode,
        )
        return r.detections
    }

    /**
     * 추론 1회 → 출력 텐서 전체를 **평탄(row-major) FloatArray** 로 돌려준다.
     * 반환 배열은 재사용되며 다음 호출에서 덮어써진다 (분석 스레드 단일 사용이라 안전).
     *
     * 예전에는 `Array(1) { Array(N) { FloatArray(C) } }` 를 매 프레임 새로 만들었다.
     * YOLOv5 [1,25500,8] 이면 프레임마다 객체 25,502개·약 1.3MB 가 GC 로 갔고, LiteRT 는
     * 행마다 JNI 복사(SetFloatArrayRegion)를 25,500번 했다. 직접 버퍼로 받으면
     * 네이티브 memcpy 한 번 + bulk get 한 번이다.
     *
     * 값이 비트 단위로 같은 이유: 두 경로 모두 텐서 메모리의 float32 비트를 row-major 순서 그대로
     * 옮길 뿐, 연산·형 변환이 없다. 단 바이트 순서는 LiteRT 의 텐서 버퍼와 같은 nativeOrder 여야 한다
     * (다르면 float 이 바이트 단위로 뒤섞인다).
     */
    private fun runInference(input: ByteBuffer): FloatArray {
        val ob = outputBuffers ?: createOutputBuffers().also { outputBuffers = it }
        // LiteRT 는 출력 ByteBuffer 에 상대 put 을 한다(position 이 끝으로 간다).
        // 지난 프레임의 position 이 남아 있으면 overflow 이므로 매번 처음으로 되돌린다.
        ob.bytes.rewind()
        interpreter.run(input, ob.bytes)
        ob.bytes.rewind()
        ob.bytes.asFloatBuffer().get(ob.floats)
        return ob.floats
    }

    /**
     * 현재 [interpreter] 의 출력 텐서에 맞는 수신 버퍼를 만든다.
     *
     * ⚠️ shape·dtype 검사를 **여기서 직접, 예전과 똑같이 엄격하게** 한다.
     * 예전 다차원 배열 경로에서는 LiteRT 가 텐서 shape 가 배열 shape([1,N,C] 등)와 하나라도 다르거나
     * dtype 이 FLOAT32 가 아니면 run() 에서 IllegalArgumentException 을 던졌다 → 분석기 실패 경로 →
     * 실패 안내. 그런데 ByteBuffer 출력은 LiteRT 가 dtype 검사를 건너뛰고 용량도 "이상인지"만 본다.
     * 그대로 두면 모델이 바뀌었을 때 엉뚱한 레이아웃으로 디코딩하며 **조용히** 돈다 —
     * 빨강↔초록 뒤바뀜이 실패 안내 없이 나갈 수 있다. 그래서 같은 조건에서 같은 예외를 던진다.
     * 통과 못 하면 캐시하지 않으므로 매 프레임 다시 던진다 (예전과 같은 동작).
     */
    private fun createOutputBuffers(): OutputBuffers {
        val tensor = interpreter.getOutputTensor(0)
        val actual = tensor.shape()
        // 예전 코드가 넘기던 배열의 shape 그대로 — Array(1){Array(A){FloatArray(C)}} → [1, A, C] 등.
        val expected = when (spec.kind) {
            ModelKind.KAIRESS_YOLOV5 -> intArrayOf(1, spec.numAnchors, spec.numOutputChannels)
            ModelKind.HYBRID_P2 -> intArrayOf(1, spec.numOutputChannels, spec.numAnchors)
        }
        if (tensor.dataType() != DataType.FLOAT32 || !actual.contentEquals(expected)) {
            throw IllegalArgumentException(
                "출력 텐서 불일치: 기대 FLOAT32 ${expected.contentToString()}, " +
                    "실제 ${tensor.dataType()} ${actual.contentToString()}"
            )
        }
        var count = 1
        for (d in actual) count *= d
        return OutputBuffers(
            bytes = ByteBuffer.allocateDirect(4 * count).order(ByteOrder.nativeOrder()),
            floats = FloatArray(count),
        )
    }

    /**
     * YOLOv5: [1, 25500, 8] anchor-major. row = [cx,cy,w,h,obj, zebra,red,green]
     * confidence = objectness × class_score  (objectness 를 빼먹으면 배경도 고득점 → 오탐 폭증)
     * 리매핑: R_Signal(1)→앱0(red), G_Signal(2)→앱1(green), Zebra_Cross(0)→제외
     */
    private fun runYolov5(input: ByteBuffer, threshold: Float): PostprocessResult {
        // 평탄 [1, A, C] row-major: 예전 output[0][i][k] == out[i * C + k]
        val out = runInference(input)
        val numCh = spec.numOutputChannels

        val candidates = ArrayList<TrafficLightDetection>(64)
        var peakAll = 0f; var peakRed = 0f; var peakGreen = 0f
        var rawAboveThreshold = 0
        var crosswalkSeen = false
        var bestCrosswalkConf = 0f
        var bestCrosswalkCx = -1f
        var bestAimConf = 0f
        var bestAimCx = -1f

        for (i in 0 until spec.numAnchors) {
            val b = i * numCh   // anchor i 행의 시작 — 예전 rows[i][k] 가 out[b + k]
            val obj = out[b + 4]
            val confCrosswalk = obj * out[b + 5 + CLS_ZEBRA]
            val confRed = obj * out[b + 5 + CLS_R_SIGNAL]
            val confGreen = obj * out[b + 5 + CLS_G_SIGNAL]

            // peak 는 threshold 무관하게 전 anchor 집계 — "임계 아래라도 보고는 있는가" 진단용.
            if (confRed > peakRed) peakRed = confRed
            if (confGreen > peakGreen) peakGreen = confGreen
            if (confCrosswalk >= threshold) {
                crosswalkSeen = true
                // 가장 확신 높은 횡단보도 anchor 의 가로 중심을 정렬 안내용으로 기록.
                if (confCrosswalk > bestCrosswalkConf) {
                    bestCrosswalkConf = confCrosswalk
                    bestCrosswalkCx = decodeBbox(out[b], out[b + 1], out[b + 2], out[b + 3]).xCenter
                }
            }

            val appClassId: Int
            val score: Float
            if (confRed >= confGreen) { appClassId = APP_CLASS_RED; score = confRed }
            else { appClassId = APP_CLASS_GREEN; score = confGreen }
            if (score > peakAll) peakAll = score

            // 조준용 — 판정 임계보다 훨씬 낮은 문턱. 여기 걸려도 안내는 나가지 않는다.
            // (decodeBbox 는 이 가지에서만 도는데, 0.10 을 넘는 anchor 는 극소수라 비용 무시 가능)
            if (score >= AIM_CONFIDENCE && score > bestAimConf && confCrosswalk <= score) {
                bestAimConf = score
                bestAimCx = decodeBbox(out[b], out[b + 1], out[b + 2], out[b + 3]).xCenter
            }

            if (score < threshold) continue
            if (confCrosswalk > score) continue   // 신호등보다 횡단보도에 가까운 anchor
            rawAboveThreshold++

            candidates += TrafficLightDetection(
                classId = appClassId,
                label = APP_CLASS_NAMES[appClassId],
                confidence = score,
                bbox = decodeBbox(out[b], out[b + 1], out[b + 2], out[b + 3]),
            )
        }

        lastCrosswalkVisible = crosswalkSeen
        lastCrosswalkCenterX = if (crosswalkSeen) bestCrosswalkCx else -1f
        // 신호등 후보가 있으면 그쪽을, 없으면 횡단보도를 조준 대상으로.
        lastAimTargetCenterX = when {
            bestAimCx >= 0f -> bestAimCx
            crosswalkSeen -> bestCrosswalkCx
            else -> -1f
        }
        return PostprocessResult(nonMaxSuppression(candidates), rawAboveThreshold, peakAll, peakRed, peakGreen)
    }

    /** YOLOv11: [1, 6, 34000] channel-major. objectness 없음 (anchor-free). */
    private fun runYolov11(input: ByteBuffer, threshold: Float): PostprocessResult {
        // 평탄 [1, C, A] row-major (채널 우선): 예전 output[0][k][i] == out[k * A + i]
        val out = runInference(input)
        val n = spec.numAnchors
        // 채널 k 행의 시작 오프셋 (예전 ch[k]). 채널 4 = 빨강, 5 = 초록 (예전 ch[4]·ch[5] 와 같다).
        val offCx = 0
        val offCy = n
        val offW = 2 * n
        val offH = 3 * n
        val offRed = 4 * n
        val offGreen = 5 * n

        val candidates = ArrayList<TrafficLightDetection>(64)
        var peakAll = 0f; var peakRed = 0f; var peakGreen = 0f
        var rawAboveThreshold = 0
        var bestAimConf = 0f
        var bestAimCx = -1f

        for (i in 0 until n) {
            val scoreRed = out[offRed + i]
            val scoreGreen = out[offGreen + i]
            if (scoreRed > peakRed) peakRed = scoreRed
            if (scoreGreen > peakGreen) peakGreen = scoreGreen

            val maxScore: Float
            val maxClass: Int
            if (scoreRed >= scoreGreen) { maxScore = scoreRed; maxClass = APP_CLASS_RED }
            else { maxScore = scoreGreen; maxClass = APP_CLASS_GREEN }
            if (maxScore > peakAll) peakAll = maxScore

            // 조준용 (runYolov5 와 동일 규칙). 이 모델엔 횡단보도 클래스가 없어 폴백이 없다.
            if (maxScore >= AIM_CONFIDENCE && maxScore > bestAimConf) {
                bestAimConf = maxScore
                bestAimCx = decodeBbox(out[offCx + i], out[offCy + i], out[offW + i], out[offH + i]).xCenter
            }

            if (maxScore < threshold) continue
            rawAboveThreshold++

            candidates += TrafficLightDetection(
                classId = maxClass,
                label = APP_CLASS_NAMES[maxClass],
                confidence = maxScore,
                bbox = decodeBbox(out[offCx + i], out[offCy + i], out[offW + i], out[offH + i]),
            )
        }

        lastCrosswalkVisible = false
        lastCrosswalkCenterX = -1f
        lastAimTargetCenterX = bestAimCx
        return PostprocessResult(nonMaxSuppression(candidates), rawAboveThreshold, peakAll, peakRed, peakGreen)
    }

    /**
     * bbox 스케일 자동 감지 + **크롭 좌표계 → 원본 프레임 좌표계 환원**.
     *
     * 스케일: export 옵션에 따라 픽셀 좌표일 수도, 이미 0~1 정규화일 수도 있다.
     * raw 최댓값이 1.5 초과면 픽셀 단위로 간주 (정규화 값은 1.0 을 넘지 않는다).
     *
     * 환원: [preprocess] 가 프레임의 가운데 정사각형만 잘라 모델에 넣으므로, 모델이 돌려주는
     * 0~1 좌표는 **크롭 영역 기준**이다. 이걸 그대로 흘려보내면 박스가 실제보다 커 보여서
     * [SignalDecisionConfig.minBoxDimension] 같은 기존 임계값의 의미가 조용히 바뀐다.
     * (세로 크롭 비율이 0.5625 면 높이가 1.78배로 부풀려진다 — 안전 임계가 그만큼 헐거워지는 셈)
     * 그래서 여기서 원본 프레임 기준으로 되돌린다. 덕분에 엔진·조준 쪽은 아무것도 안 고쳐도 된다.
     */
    private fun decodeBbox(cx: Float, cy: Float, w: Float, h: Float): BoundingBox {
        val maxRaw = maxOf(maxOf(cx, cy), maxOf(w, h))
        val s = if (maxRaw > 1.5f) 1f / spec.inputSize else 1f
        return BoundingBox(
            xCenter = cropLeftNorm + cx * s * cropWidthNorm,
            yCenter = cropTopNorm + cy * s * cropHeightNorm,
            width = w * s * cropWidthNorm,
            height = h * s * cropHeightNorm,
        )
    }

    /**
     * 카메라 프레임 → 모델 입력 텐서.
     *
     * ─────────────────────────────────────────────────────────────────────────
     * 왜 '가운데 정사각 크롭' 인가 (2026-09)
     * ─────────────────────────────────────────────────────────────────────────
     * 이전에는 `createScaledBitmap(bitmap, 640, 640)` 으로 프레임 전체를 정사각형에
     * **찌그러뜨려** 넣었다. 그런데 분석 프레임은 1280×720 을 세로로 회전한 720×1280 이라,
     * 가로는 ×0.89 로 줄고 **세로는 ×0.5 로 줄었다** — 세로가 1.78배 더 눌린 것이다.
     *
     * 결과는 두 가지로 나빴다.
     *  (1) 원래도 10px 남짓이던 원거리 신호등 등기구가 세로로 반 토막 나 9px 아래로 떨어졌다.
     *      광폭 도로 건너편(30~50m)이 딱 이 구간이다.
     *  (2) YOLOv5 는 학습 때 레터박스(비율 유지)로 들어간다. 즉 모델이 한 번도 본 적 없는
     *      찌그러진 이미지를 추론에 먹이고 있었다. 작은 물체일수록 손해가 크다.
     *
     * 가운데 정사각형만 잘라 넣으면 비율이 유지되고(×0.89 등방), 같은 물체가 **세로로 약 1.7배**
     * 크게 들어간다. 버리는 건 화면 맨 위(하늘)와 맨 아래(발밑)뿐이고,
     * **가로 화각은 하나도 안 줄어든다** — 사용자가 좌우로 훑는 동작에는 영향이 없다.
     *
     * 레터박스(비율 유지 + 패딩) 대신 크롭을 고른 이유: 레터박스는 긴 변을 640 에 맞추므로
     * 물체가 오히려 더 작아진다(≈9px). 크롭은 짧은 변을 맞추므로 더 크게 들어간다(≈16px).
     */
    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val size = spec.inputSize

        // ── 1) 정사각 크롭 + 환원 계수 기록 ──
        //
        // 가로는 가운데. 세로는 **살짝 위로** 치우치게 자른다 ([CROP_TOP_BIAS]).
        //
        // 이유는 "가까운 신호등을 담기 위해"가 아니다. 실측 계산상 8m 이상 거리의 신호등은
        // 어떤 편향값에서도 프레임에 들어오고, 6m 이하는 어떤 값에서도 안 들어온다
        // (그 거리는 어차피 사용자가 고개를 들 듯 폰을 들어 올리는 상황이다).
        //
        // 진짜 이유는 **기울임 여유의 대칭성**이다. 신호등은 항상 광축보다 위에 맺히므로,
        // 크롭을 가운데 두면 여유가 "위로 7°/아래로 13°"로 엉뚱하게 비대칭이 된다.
        // 화면을 못 보는 사용자는 폰 각도를 정확히 맞출 수 없으니, 양쪽 여유가 비슷해야
        // 어느 쪽으로 틀어져도 비슷하게 버틴다. 0.35 에서 위 9.6° / 아래 10.4° 로 맞는다.
        //
        // 대가: 발 앞 바닥이 보이기 시작하는 거리가 7.6m → 9.9m 로 멀어진다.
        // 바닥은 횡단보도(Zebra_Cross) 검출용이고, 그건 조준 폴백에만 쓰이는 보조 정보다.
        // ⚠️ 2026-09 현장 테스트: 정사각 크롭 도입 후 인식률이 급락하고 초록을 빨강으로
        //    오인하는 문제가 보고됨. kairess YOLOv5 는 학습 때 전체 프레임을 640 에 넣었고,
        //    크롭은 모델이 본 적 없는 구도라 검출 분포가 어긋난다. 크롭 이전(작동하던) 방식으로
        //    되돌리되, 기기에서 A/B 비교가 가능하도록 스위치로 둔다.
        val scaled: Bitmap
        if (USE_SQUARE_CROP) {
            // 세로는 살짝 위로 치우쳐 크롭 (기울임 여유 대칭화). 자세한 근거는 CROP_TOP_BIAS 주석.
            val side = minOf(bitmap.width, bitmap.height)
            val left = (bitmap.width - side) / 2
            val slack = bitmap.height - side
            val top =
                if (bitmap.height > bitmap.width) (slack * CROP_TOP_BIAS).toInt()
                else slack / 2
            cropLeftNorm = left.toFloat() / bitmap.width
            cropTopNorm = top.toFloat() / bitmap.height
            cropWidthNorm = side.toFloat() / bitmap.width
            cropHeightNorm = side.toFloat() / bitmap.height
            val square =
                if (bitmap.width == side && bitmap.height == side) bitmap
                else Bitmap.createBitmap(bitmap, left, top, side, side)
            scaled =
                if (side == size) square
                else Bitmap.createScaledBitmap(square, size, size, true)
            // 중간 크롭 비트맵은 축소본을 만든 뒤 쓸 일이 없다. 호출자 것(bitmap)이거나
            // 그대로 scaled 로 쓰이는 경우(아래에서 반납)는 건드리지 않는다.
            if (square !== bitmap && square !== scaled) square.recycle()
        } else {
            // 크롭 이전 동작: 전체 프레임을 640×640 으로 (비율은 눌리지만 학습과 같은 방식).
            // bbox 는 원본 프레임 전체 기준이므로 환원 계수는 항등.
            cropLeftNorm = 0f
            cropTopNorm = 0f
            cropWidthNorm = 1f
            cropHeightNorm = 1f
            scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        }

        // ── 2) float32 RGB 변환 ──
        // 버퍼는 매 프레임 새로 잡지 않고 재사용한다. 예전에는 프레임마다
        // ByteBuffer 4.9MB + IntArray 1.6MB 를 새로 할당해 3fps 기준 초당 20MB 가까이
        // GC 에 던졌다. 입력 크기는 모델당 고정이라 한 번 잡으면 계속 쓸 수 있다.
        // (detect() 는 단일 분석 스레드에서만 불리므로 동기화 불필요)
        val buffer = inputBuffer ?: ByteBuffer
            .allocateDirect(4 * size * size * 3)
            .order(ByteOrder.nativeOrder())
            .also { inputBuffer = it }
        buffer.clear()

        val pixels = pixelCache ?: IntArray(size * size).also { pixelCache = it }
        try {
            scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        } finally {
            // 축소본(640×640 ≈ 1.6MB 네이티브)은 픽셀을 꺼낸 순간 끝이다. GC 를 기다리지 않고 바로 반납.
            // createScaledBitmap 은 크기가 같으면 원본을 그대로 돌려줄 수 있다 — 그건 호출자 소유라 두고 간다.
            if (scaled !== bitmap) scaled.recycle()
        }

        // 픽셀당 putFloat 3번(프레임당 약 123만 번 호출)을 없앤다: LUT 로 FloatArray 를 채우고 bulk put 한 번.
        // NORM_LUT[v] 는 예전 식 `v / 255.0f` 를 그대로 미리 계산해 둔 값이라 비트 단위로 같다.
        // 채널 순서(R,G,B)·픽셀 순서(getPixels 의 row-major)도 예전과 같다.
        // asFloatBuffer() 뷰는 buffer 의 nativeOrder 를 물려받으므로 putFloat 와 같은 바이트가 써진다.
        val floats = inputFloats ?: FloatArray(size * size * 3).also { inputFloats = it }
        var j = 0
        for (p in pixels) {
            floats[j] = NORM_LUT[(p shr 16) and 0xFF]
            floats[j + 1] = NORM_LUT[(p shr 8) and 0xFF]
            floats[j + 2] = NORM_LUT[p and 0xFF]
            j += 3
        }
        buffer.asFloatBuffer().put(floats)
        buffer.rewind()
        return buffer
    }

    private fun nonMaxSuppression(detections: List<TrafficLightDetection>): List<TrafficLightDetection> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val keep = ArrayList<TrafficLightDetection>(sorted.size)
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep += best
            sorted.removeAll { o -> o.classId == best.classId && iou(best.bbox, o.bbox) > iouThreshold }
        }
        return keep
    }

    private fun iou(a: BoundingBox, b: BoundingBox): Float {
        val x1 = max(a.left, b.left); val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right); val y2 = min(a.bottom, b.bottom)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val union = a.area + b.area - inter
        return if (union > 0f) inter / union else 0f
    }

    /**
     * ⚠️ detect() 와 **동시에** 부르면 안 된다 (run() 도중 네이티브 해제 → SIGSEGV).
     * 분석과 같은 단일 executor 에서 부르면 analyze() 와 자연히 직렬화돼 안전하고,
     * 다른 스레드에서 부를 거면 분석 executor 가 끝난 뒤(awaitTermination 성공 후)에만 부를 것.
     * 여러 번 불러도 된다(Interpreter.close() 는 멱등). 닫힌 뒤의 detect() 는
     * IllegalStateException 으로 끝나 분석기의 실패 경로로 간다(네이티브 크래시 아님).
     */
    fun close() {
        try { interpreter.close() } catch (_: Exception) {}
        // 직접 버퍼(입력 4.9MB·출력 0.8MB)는 GC 가 회수하지만, 참조를 끊어 즉시 반환되게 한다.
        inputBuffer = null
        pixelCache = null
        inputFloats = null
        outputBuffers = null
    }

    /** 출력 텐서 수신용 직접 버퍼 + 그걸 풀어 둘 평탄 배열. 둘은 항상 같은 원소 수다. */
    private class OutputBuffers(val bytes: ByteBuffer, val floats: FloatArray)

    private data class PostprocessResult(
        val detections: List<TrafficLightDetection>,
        val rawAboveThreshold: Int,
        val peakAll: Float,
        val peakRed: Float,
        val peakGreen: Float,
    )

    private data class ModelSpec(
        val kind: ModelKind,
        val assetFilename: String,
        val inputSize: Int,
        val numClasses: Int,
        val numAnchors: Int,
        val defaultConfidence: Float,
    ) {
        /** YOLOv5 는 objectness 채널이 하나 더 있다. */
        val numOutputChannels: Int
            get() = when (kind) {
                ModelKind.KAIRESS_YOLOV5 -> 4 + 1 + numClasses   // 8
                ModelKind.HYBRID_P2 -> 4 + numClasses            // 6
            }
    }

    companion object {
        private const val TAG = "TrafficLightDetector"

        /** ⭐ 사용할 모델. 이 한 줄만 바꾸면 전환된다. */
        private val ACTIVE_MODEL = ModelKind.KAIRESS_YOLOV5

        /** ACTIVE_MODEL 의 tflite 가 assets 에 없을 때 쓸, 항상 커밋된 검증 모델. */
        private val FALLBACK_MODEL = ModelKind.HYBRID_P2

        private val SPECS = mapOf(
            ModelKind.KAIRESS_YOLOV5 to ModelSpec(
                kind = ModelKind.KAIRESS_YOLOV5,
                assetFilename = "crosswalk_kairess.tflite",
                inputSize = 640,
                numClasses = 3,
                // P6 @640: 3 × (80² + 40² + 20² + 10²) = 3 × 8,500 = 25,500
                numAnchors = 25_500,
                defaultConfidence = 0.25f,
            ),
            ModelKind.HYBRID_P2 to ModelSpec(
                kind = ModelKind.HYBRID_P2,
                assetFilename = "safewalknav_tl.tflite",
                inputSize = 640,
                numClasses = 2,
                // P2 head: 160² + 80² + 40² + 20² = 34,000
                numAnchors = 34_000,
                defaultConfidence = 0.2f,
            ),
        )

        const val DIAGNOSTIC_CONFIDENCE_THRESHOLD: Float = 0.15f

        /**
         * 0~255 → 0~1 정규화 표. **예전 식 `v / 255.0f` 와 똑같은 나눗셈**이다 (Int / Float).
         * ⚠️ `v * (1f / 255f)` 로 바꾸지 말 것 — 일부 값에서 1 ulp 달라져 모델 입력이 바뀐다.
         */
        private val NORM_LUT = FloatArray(256) { it / 255.0f }

        /**
         * 조준 진동 전용 문턱. 판정 문턱(0.25)보다 낮다.
         * 조준은 틀려도 진동 박자만 어긋나고 **음성 안내는 나가지 않으므로** 공격적으로 잡는다.
         * ⚠️ 이 값을 안전 판정 경로에 쓰면 안 된다.
         */
        private const val AIM_CONFIDENCE: Float = 0.10f

        /**
         * 세로 크롭 시 버리는 픽셀 중 **위쪽에서 버릴 비율**. 0.5 면 가운데 정렬.
         *
         * 0.35 = 위에서 35%, 아래에서 65% 를 버린다 → 시야가 위로 약 2° 올라가고,
         * 30m 신호등 기준 기울임 여유가 위 9.6° / 아래 10.4° 로 거의 대칭이 된다.
         *
         * ⚠️ 실기에서 조정할 첫 번째 상수다. 평가 킷의 `조준→첫판독(초)` 과
         * `30초내 성공률` 을 보고 맞출 것. 올리면(→0.5) 바닥이 더 보이고,
         * 내리면(→0.2) 위쪽 여유가 늘지만 바닥을 잃는다.
         */
        /**
         * 전처리에 정사각 크롭을 쓸지. **기본 false = 크롭 이전(작동하던) 방식.**
         *
         * 현장 테스트에서 크롭이 인식률을 떨어뜨리고 초록→빨강 오인을 유발하는 것으로 보고돼
         * 되돌렸다. 크롭의 이점(원거리 신호 확대)을 다시 시도할 때만 true 로 바꿔
         * 반드시 실기 A/B 로 검증할 것. true 로 켤 거면 CROP_TOP_BIAS 도 함께 본다.
         */
        private const val USE_SQUARE_CROP: Boolean = false

        private const val CROP_TOP_BIAS: Float = 0.35f

        // kairess data/crosswalk.yaml → names: ['Zebra_Cross', 'R_Signal', 'G_Signal']
        // ⚠️ 이 순서가 틀리면 빨간불을 초록불로 안내한다. export 시 반드시 검증할 것.
        private const val CLS_ZEBRA = 0
        private const val CLS_R_SIGNAL = 1
        private const val CLS_G_SIGNAL = 2

        // 앱 계약 (모델 무관 고정)
        const val APP_CLASS_RED = 0
        const val APP_CLASS_GREEN = 1
        private val APP_CLASS_NAMES = listOf("red_pedestrian", "green_pedestrian")

        /**
         * assets 의 모델 파일을 메모리 매핑으로 연다.
         *
         * tensorflow-lite-support 의 `FileUtil.loadMappedFile` 과 같은 동작이다. 그 라이브러리는
         * 옛 tensorflow-lite(4KB 정렬 .so)를 끌고 들어와 Play 16KB 요건을 다시 깨뜨리므로
         * 이 한 함수만 직접 구현하고 의존성은 뺐다.
         *
         * 매핑은 채널을 닫은 뒤에도 유효하다. openFd 는 압축되지 않은 asset 에만 되는데,
         * AGP 는 .tflite 를 기본으로 비압축 처리한다 (예전 FileUtil 도 같은 전제였다).
         */
        private fun loadMappedAsset(context: Context, filename: String): MappedByteBuffer {
            context.assets.openFd(filename).use { fd ->
                FileInputStream(fd.fileDescriptor).use { input ->
                    return input.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        fd.startOffset,
                        fd.declaredLength,
                    )
                }
            }
        }
    }
}
