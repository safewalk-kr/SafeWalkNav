//
//  TtsManager.swift
//  iosApp
//
//  음성 안내(TTS) 매니저
//  - AVSpeechSynthesizer 래핑
//  - 한국어 음성으로 안내 메시지 출력
//  - 같은 메시지 반복 방지 (debounce)
//

import Foundation
import AVFoundation
import Combine

/// 화면 표시 여부를 utterance에 함께 싣기 위한 래퍼
final class DisplayUtterance: AVSpeechUtterance {
    var shouldDisplay: Bool = false
}

/// 시각장애인용 음성 안내 매니저
final class TtsManager: NSObject, ObservableObject {

    // MARK: - Published State
    /// 현재 음성 출력 중인지 여부 (UI에서 확인 가능)
    @Published private(set) var isSpeaking: Bool = false

    /// 화면에 표시할 현재 "안내 멘트" (display:true 로 호출된 발화만 갱신)
    /// - 다음 안내 발화 전까지 값 유지
    /// - 안내 종료 시 clearDisplayText() 로 초기화
    @Published private(set) var displayText: String = ""

    // MARK: - Private Properties
    /// ⚠️ `var` 인 이유: mediaServicesWereReset(오디오 데몬 재시작) 시 인스턴스 자체가
    /// 무효가 되므로 새로 만들어야 한다. 그 외에는 재할당하지 않는다.
    private var synthesizer = AVSpeechSynthesizer()

    /// 오디오 세션 알림 구독 토큰. 블록 기반 addObserver 는 self 가 아니라 이 토큰에 묶인다.
    private var sessionObservers: [NSObjectProtocol] = []

    /// 직전 세션 확보 실패 메시지. 같은 실패를 매 발화마다 로그로 찍지 않기 위한 것.
    private var lastSessionError: String?

    /// 직전에 말한 텍스트 (중복 방지용)
    private var lastSpokenText: String = ""

    /// 직전에 말한 시각
    private var lastSpeakTime: Date = .distantPast

    /// 같은 메시지를 다시 말하기까지의 최소 간격 (초)
    private let minRepeatInterval: TimeInterval = 3.0

    // MARK: - Init
    override init() {
        super.init()
        synthesizer.delegate = self
        ensurePlaybackSession(reason: "init")
        observeSessionDisruptions()
    }

    deinit {
        // ⚠️ 블록 기반 addObserver 는 self 가 아니라 **토큰**에 등록된다.
        //    removeObserver(self) 로는 지워지지 않으므로 토큰을 들고 있다가 해제한다.
        sessionObservers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    // MARK: - Audio Session

    /// **말할 수 있는 상태**로 세션을 보장한다. 설정·복구가 한 함수다.
    ///
    /// ⚠️ `setActive(true)` 만으로는 부족하다. 카테고리까지 다시 걸어야 한다 —
    /// [SttManager] 가 마이크를 열 때 세션을 `.record/.measurement` 로 바꾸는데,
    /// 시작 실패 경로에서는 `.playback` 으로 되돌리지 못하고 빠져나간다.
    /// `.record` 인 세션에서 `AVSpeechSynthesizer` 는 **소리를 내지 않는다.**
    /// 그리고 `synthesizer.speak` 는 던지지도 않으므로 실패가 드러나지도 않는다.
    /// 말하려는 시점에는 언제나 `.playback` 이어야 한다.
    private func ensurePlaybackSession(reason: String) {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(
                .playback,
                mode: .voicePrompt,         // 음성 안내용 모드 (덕킹 자동 처리)
                options: [.mixWithOthers, .duckOthers]
            )
            try session.setActive(true)
            lastSessionError = nil
        } catch {
            // 매 발화(12초 heartbeat 등)마다 같은 실패를 찍지 않는다 —
            // 화면 꺼진 채 걷는 동안 로그가 계속 쌓인다.
            let desc = error.localizedDescription
            if lastSessionError != desc {
                lastSessionError = desc
                print("[TtsManager] 오디오 세션 확보 실패(\(reason)): \(desc)")
            }
        }
    }

    /// 놓친 안내가 중복 필터에 막히지 않도록 dedup 상태를 비운다.
    /// 인터럽션으로 삼켜진 발화를 호출자가 곧바로 재시도할 때 필요하다.
    private func clearDedup() {
        lastSpokenText = ""
        lastSpeakTime = .distantPast
    }

    /// 오디오 세션이 뺏겼다가 돌아왔을 때 되살린다.
    ///
    /// ⚠️ 이게 없으면 **전화 한 통으로 남은 보행 내내 안내가 사라진다.**
    /// 전화·Siri·다른 앱이 세션을 가져가면 iOS 가 우리 세션을 비활성화하는데,
    /// 예전에는 init 에서 딱 한 번 setActive(true) 하고 끝이라 되살릴 주체가 없었다.
    /// `synthesizer.speak` 는 던지지도 않고 조용히 삼키므로 **실패가 드러나지도 않는다.**
    /// 폰을 주머니에 넣고 걷는 사용자에게는 화면의 displayText 도 대안이 못 된다.
    ///
    /// mediaServicesWereReset 은 오디오 데몬이 재시작된 경우로,
    /// 이때는 세션뿐 아니라 합성기 인스턴스까지 무효가 되므로 새로 만든다.
    private func observeSessionDisruptions() {
        let center = NotificationCenter.default

        let onInterruption = center.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main,
            using: { [weak self] note in
                guard
                    let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                    let type = AVAudioSession.InterruptionType(rawValue: raw),
                    type == .ended
                else { return }
                guard let self else { return }
                // .shouldResume 은 일부러 보지 않는다. 그건 '미디어 재생을 이어갈까'라는
                // 힌트이고, 보행 안내는 미디어가 아니다. 횡단보도 앞에 선 사용자에게는
                // 끼어든 앱의 의사와 무관하게 세션이 돌아와야 한다.
                self.ensurePlaybackSession(reason: "interruption ended")
                self.clearDedup()
            }
        )

        let onReset = center.addObserver(
            forName: AVAudioSession.mediaServicesWereResetNotification,
            object: nil,
            queue: .main,
            using: { [weak self] _ in
                guard let self else { return }
                // 옛 합성기는 didFinish 를 영영 안 보내므로 isSpeaking 을 직접 내린다.
                self.isSpeaking = false
                self.synthesizer = AVSpeechSynthesizer()
                self.synthesizer.delegate = self
                self.ensurePlaybackSession(reason: "mediaServicesWereReset")
                self.clearDedup()
                print("[TtsManager] mediaServicesWereReset — 합성기·세션 재생성")
            }
        )

        sessionObservers = [onInterruption, onReset]
    }

    // MARK: - Public API

    /// 텍스트를 한국어 음성으로 출력
    /// - Parameters:
    ///   - text: 말할 내용
    ///   - priority: 호환용 인자. 현재는 큐잉 정책 통일로 .high 도 끊지 않고 뒤에 붙는다.
    func speak(_ text: String, priority: Priority = .normal, display: Bool = false) {
        // 1. 빈 문자열은 무시
        guard !text.isEmpty else { return }

        // 1-1. 화면 표시 대상이면, 오디오 dedup 여부와 무관하게 화면 텍스트 먼저 갱신
        //      (같은 멘트를 반복 발화해도 화면은 그대로 유지되도록 dedup 보다 앞에서 처리)
        if display {
            DispatchQueue.main.async { self.displayText = text }
        }

        // 2. 같은 메시지 반복 방지
        if shouldSkipDuplicate(text: text) {
            return
        }

        // 3. 큐잉 정책 — 현재 발화 중이면 native 큐 뒤에 붙는다.
        //    AVSpeechSynthesizer 는 직전 utterance 의 didFinish 후에야 다음 utterance 를 시작하므로
        //    멘트가 중간에 잘리지 않는다. priority 인자는 호환을 위해 남기되 끊지 않는다.
        //    (자기 목소리가 마이크로 들어가는 걸 막아야 하는 STT 진입 등은 명시적 stop() 사용)

        // 3-1. 방어적 재활성화 — 인터럽션 알림을 놓쳤거나 STT 가 세션을 .record 로
        //      바꿔둔 채 돌려주지 못한 경우에도 여기서 복구된다. 이미 활성이면 사실상 무비용.
        ensurePlaybackSession(reason: "speak")

        // 4. 발화 — 표시 플래그를 utterance 에 실어 보내 didStart 시점에 화면 갱신.
        let utterance = DisplayUtterance(string: text)
        utterance.shouldDisplay = display
        utterance.voice = AVSpeechSynthesisVoice(language: "ko-KR")
        utterance.rate = 0.5            // 0.0(느림) ~ 1.0(빠름), 기본 0.5
        utterance.pitchMultiplier = 1.0 // 0.5 ~ 2.0, 1.0이 기본
        utterance.volume = 1.0

        synthesizer.speak(utterance)

        // 5. 상태 갱신
        lastSpokenText = text
        lastSpeakTime = Date()
    }

    /// 선점 발화 — 현재 발화와 큐를 끊고 즉시 출력.
    /// 회전 직전(IMMINENT) "지금 …" 처럼 타이밍 생명선 안내에만 사용.
    ///
    /// 일반 speak() 와 차이:
    ///   - 3초 중복 필터(shouldSkipDuplicate)를 우회 — 직전은 무조건 발화.
    ///   - stopSpeaking(.immediate) 로 현재+큐 비움 (대기 안내가 있으면 같이 사라지는 점 인지).
    func speakImmediately(_ text: String, display: Bool = false) {
        guard !text.isEmpty else { return }

        if display {
            DispatchQueue.main.async { self.displayText = text }
        }

        // 큐 전체 비우기 — 대기 중인 횡단보도 등 안내가 있으면 함께 사라진다.
        synthesizer.stopSpeaking(at: .immediate)

        // 도착·점멸 같은 즉시 안내일수록 세션이 죽어 있으면 안 된다.
        ensurePlaybackSession(reason: "speakImmediately")

        let utterance = DisplayUtterance(string: text)
        utterance.shouldDisplay = display
        utterance.voice = AVSpeechSynthesisVoice(language: "ko-KR")
        utterance.rate = 0.5
        utterance.pitchMultiplier = 1.0
        utterance.volume = 1.0
        synthesizer.speak(utterance)

        lastSpokenText = text
        lastSpeakTime = Date()
    }

    /// 현재 음성 즉시 중단
    func stop() {
        if synthesizer.isSpeaking {
            synthesizer.stopSpeaking(at: .immediate)
        }
    }

    /// 화면 표시용 안내 멘트 초기화 (안내 종료/취소 시 호출)
    func clearDisplayText() {
        DispatchQueue.main.async { self.displayText = "" }
    }

    // MARK: - Private Helpers

    /// 같은 메시지를 너무 빨리 다시 말하려는지 확인
    private func shouldSkipDuplicate(text: String) -> Bool {
        let now = Date()
        let isSameText = (text == lastSpokenText)
        let timeSinceLast = now.timeIntervalSince(lastSpeakTime)

        return isSameText && timeSinceLast < minRepeatInterval
    }
}

// MARK: - Priority
extension TtsManager {
    enum Priority {
        case normal  // 큐에 쌓임
        case high    // 즉시 출력 (기존 발화 중단)
    }
}

// MARK: - AVSpeechSynthesizerDelegate
extension TtsManager: AVSpeechSynthesizerDelegate {
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer,
                           didStart utterance: AVSpeechUtterance) {
        DispatchQueue.main.async {
            self.isSpeaking = true
            if let u = utterance as? DisplayUtterance, u.shouldDisplay {
                self.displayText = u.speechString   // 발화가 실제로 시작될 때만 갱신
            }
        }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer,
                           didFinish utterance: AVSpeechUtterance) {
        DispatchQueue.main.async {
            self.isSpeaking = false
        }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer,
                           didCancel utterance: AVSpeechUtterance) {
        DispatchQueue.main.async {
            self.isSpeaking = false
        }
    }
}
