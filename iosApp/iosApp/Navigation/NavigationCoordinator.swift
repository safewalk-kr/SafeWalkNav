//
//  NavigationCoordinator.swift
//  iosApp
//
//  앱 전체 화면 흐름을 관장하는 상태기계.
//  .safetyNotice → .destinationInput → .guiding → .arrived → (.destinationInput)
//  신호등 화면은 더 이상 phase(.crossing)가 아니라 하단 2탭(selectedTab)으로 분기한다.
//  횡단 중에도 phase 는 .guiding 을 유지하고, 탭만 자동/수동으로 전환한다(§3·§4).
//
//  ⚠️ 5단계(골격): 전이 메서드는 phase 만 바꾸고 실제 로직(경로탐색·추종·횡단보도 감지)은
//  6단계에서 채운다. 여기서는 상태 라우팅과 detector start/stop 제어만 담당.
//
//  NOTE: tMapClient.searchPOI / searchPedestrianRoute 는 Kotlin suspend →
//  Swift 에서 async(try await) 로 호출된다. 관련 진입 메서드는 async 로 잡는다.
//

import Foundation
import Combine
import CoreLocation
import shared    // TMapApiClient, POIResult, TMapRoute (KMM)

/// 앱 화면 흐름 단계.
enum NavPhase {
    case safetyNotice
    case destinationInput
    case guiding
    case arrived
}

@MainActor
final class NavigationCoordinator: ObservableObject {

    /// 현재 화면 단계. AppRootView 가 이 값으로 라우팅한다.
    /// 초기값은 안전 고지 1회 동의 여부에 따라 결정(init 에서 주입).
    @Published var phase: NavPhase

    // MARK: - 탭 상태 (§3·§4) — 카메라 화면 표시 여부를 selectedTab 하나로 일원화
    /// 현재 선택된 탭 (0 = 내비게이션, 1 = 신호등). MainTabView 가 관찰한다.
    /// 자동 전환은 이 값을 직접 대입하고, 사용자 조작은 selectTabByUser(_:) 로 들어온다.
    @Published private(set) var selectedTab: Int = 0
    /// 사용자가 직접 탭을 조작했는가 — 자동 전환보다 우선한다(§4 규칙 2·3).
    /// View 에서 읽을 필요가 없으므로 @Published 로 노출하지 않는다.
    private var userOverride: Bool = false
    /// 탭 1 진입이 자동(횡단보도 zone)이었는지 — 자동 복귀 판정용(§4 규칙 5·6).
    private var enteredSignalTabAutomatically: Bool = false

    // MARK: - 안내 상태 (GuidingView 표시용)
    /// 탐색된 경로. §4-3 FollowingController 가 소비.
    @Published private(set) var currentRoute: TMapRoute?
    /// 목적지 이름(안내 화면 표시).
    @Published private(set) var destinationName: String?
    /// 목적지까지 남은 거리 문구(§4-3에서 갱신). nil 이면 "안내 중" 표시.
    @Published private(set) var remainingText: String?
    /// 다음 횡단보도까지 거리 문구(§4-3에서 갱신). 남은 횡단보도 없으면 nil.
    @Published private(set) var nextCrosswalkText: String?

    /// 「안내 시작」 버튼을 눌러 실제 추종을 시작했는가.
    /// false = 안내 준비(버튼·지도 표시, 추종 미시작), true = 안내 중(전체 화면 StatusPanel).
    /// .guiding 진입 시 false 로 리셋, startGuidance()에서 true.
    @Published private(set) var guidanceStarted: Bool = false

    /// 목적지 좌표(§4-3 도착 판정용).
    private(set) var destinationCoord: CLLocationCoordinate2D?

    /// CROSSWALK waypoint 인덱스(route.waypoints 기준) → 신호등 상태. 경로 탐색 성공 시 채움.
    /// ⚠️ .hasNearby 는 긍정 안내에 쓰지 않는다(매칭 반경은 경험값, 오탐률 미측정).
    @Published private(set) var crosswalkSignalStatus: [Int: CrosswalkSignalStatus] = [:]
    /// 경로상 n번째 CROSSWALK → route.waypoints 인덱스 (FollowingController ordinal 매핑용).
    private var crosswalkWaypointIndices: [Int] = []

    // MARK: - 주입 의존성
    private let tts: TtsManager
    private let locationTracker: LocationTracker
    private let stt: SttManager
    private let tMapClient: TMapApiClient
    // NOTE(§5): TrafficLightDetector lifecycle 은 이제 탭 1(SignalScreen)의 onAppear/onDisappear
    //           단일 지점이 소유한다. 코디네이터는 detector 를 더 이상 직접 제어하지 않는다.

    // MARK: - §4-3 추종
    private var following: FollowingController!
    /// 마지막으로 음성 안내한 남은거리(m) — "약 N미터" 스팸 방지.
    private var lastSpokenRemaining: Int?
    /// 마지막으로 화면에 표시한 남은거리(m) — 10m 임계 갱신용(증감 양방향).
    private var lastDisplayedRemaining: Int?

    init(
        tts: TtsManager,
        locationTracker: LocationTracker,
        stt: SttManager,
        tMapClient: TMapApiClient,
        initialPhase: NavPhase = .safetyNotice
    ) {
        self.tts = tts
        self.locationTracker = locationTracker
        self.stt = stt
        self.tMapClient = tMapClient
        self.phase = initialPhase

        self.following = FollowingController(
            locationTracker: locationTracker,
            callbacks: .init(
                enterCrossing:      { [weak self] ordinal in self?.enterCrossing(crosswalkOrdinal: ordinal) },
                exitCrossing:       { [weak self] in self?.exitCrossing() },
                arrive:             { [weak self] in self?.arrive() },
                updateRemaining:    { [weak self] m in self?.updateRemaining(m) },
                updateNextCrosswalk:{ [weak self] m in self?.updateNextCrosswalk(m) }
            )
        )
    }

    // MARK: - 전이 (5단계 stub — phase 전환만)

    /// 안전 고지 동의 → 목적지 입력으로.
    func acknowledgeSafety() {
        // TODO(6단계): 목적지 입력 진입 시 TTS "목적지를 말씀해 주세요" 안내.
        phase = .destinationInput
    }

    /// 목적지 선택됨 → 경로 탐색 후 안내 시작(§4-2).
    /// tMapClient.searchPedestrianRoute 는 suspend → async 로 호출.
    func onDestinationChosen(_ poi: POIResult) async {
        // 0) 앱 설정(API 키) 무결성 확인 — 키가 없으면 경로 탐색이 불가능하므로
        //    크래시 대신 음성으로 안내하고 중단한다(§4-1).
        guard Secrets.isConfigured else {
            tts.speak("앱 설정에 문제가 있어 길 안내를 시작할 수 없습니다. 앱을 다시 설치해 주세요.", display: true)
            return
        }

        // 0-1) 보행등 인덱스 선로드 — CSV 파싱(수십 ms)이 GPS 픽스·TMap 왕복보다 빨리 끝나
        //      경로 성공 시점에는 status() 가 대부분 로드 완료 상태. 미완료면 .noData 로 처리(아래).
        PedestrianSignalIndex.shared.loadIfNeeded { _ in }

        // 1) 출발 좌표 — 첫 GPS 픽스가 없으면 최대 ~5초 폴링.
        locationTracker.start()
        var start = locationTracker.currentLocation
        if start == nil {
            for _ in 0..<10 {                          // 0.5s × 10 = 5s
                try? await Task.sleep(nanoseconds: 500_000_000)
                if let c = locationTracker.currentLocation { start = c; break }
            }
        }
        guard let startCoord = start else {
            tts.speak("현재 위치를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.", display: true)
            reset()
            return
        }

        // 2) 목적지 좌표 — 건물 입구(front) 좌표 우선.
        let destLat = poi.frontLat?.doubleValue ?? poi.lat
        let destLon = poi.frontLon?.doubleValue ?? poi.lon

        // 3) 경로 탐색.
        tts.speak("경로를 탐색합니다.", display: true)
        let route: TMapRoute?
        do {
            route = try await tMapClient.searchPedestrianRoute(
                startLat: startCoord.latitude,
                startLon: startCoord.longitude,
                endLat: destLat,
                endLon: destLon,
                startName: "출발지",
                endName: poi.name
            )
        } catch {
            tts.speak("경로를 찾지 못했습니다. 목적지를 다시 확인해 주세요.", display: true)
            reset()
            return
        }

        // 4) 실패 처리.
        guard let route, !route.waypoints.isEmpty else {
            tts.speak("경로를 찾지 못했습니다. 목적지를 다시 확인해 주세요.", display: true)
            reset()
            return
        }

        // 4-1) 횡단보도별 신호등 상태 부착 — 서울시 보행등 데이터 기준 (횡단보도 단위 판정).
        //      인덱스 미로드면 .noData 취급(부정 판정 금지 — 없다고 단정하지 않는다).
        crosswalkSignalStatus = [:]
        crosswalkWaypointIndices = []
        for (i, wp) in route.waypoints.enumerated() where wp.pointType == "CROSSWALK" {
            crosswalkWaypointIndices.append(i)
            let status = PedestrianSignalIndex.shared.status(lat: wp.lat, lon: wp.lon)
            if status == nil { print("🚦 [PEDLIGHT] index not loaded — idx=\(i) → noData") }
            crosswalkSignalStatus[i] = status ?? .noData
            let n50 = PedestrianSignalIndex.shared.countWithin(
                lat: wp.lat, lon: wp.lon, radiusM: PedestrianSignalIndex.matchRadiusM
            )
            print("🚦 [PEDLIGHT] idx=\(i) n50=\(n50.map(String.init) ?? "-") status=\(crosswalkSignalStatus[i]!)")
        }

        // 5) 요약 음성.
        let distanceM = Int(route.totalDistance)
        let count = route.waypoints.filter { $0.pointType == "CROSSWALK" }.count
        let distanceText: String
        if distanceM < 1000 {
            distanceText = "\((distanceM / 10) * 10)미터"
        } else {
            distanceText = String(format: "%.1f킬로미터", Double(distanceM) / 1000.0)
        }
        // 데이터 커버리지 밖 횡단보도가 하나라도 있으면 1회 고지.
        // .noneNearby 개수는 말하지 않는다 — 나머지에 신호등이 있다는 암시(긍정 안내)가 되므로.
        let hasNoData = crosswalkSignalStatus.values.contains(.noData)
        let notice = hasNoData ? " 이 지역은 신호등 정보가 확인되지 않습니다." : ""
        tts.speak("도착지까지 \(distanceText), 횡단보도는 \(count)개입니다.\(notice) 경로 안내를 시작하겠습니다. 안내 시작 버튼을 누르세요.", display: true)

        // 6) 상태 저장 후 안내 준비 화면으로.
        //    추종(following.start)은 여기서 시작하지 않고 startGuidance()(버튼 탭)로 미룬다.
        let destCoord = CLLocationCoordinate2D(latitude: destLat, longitude: destLon)
        self.currentRoute = route
        self.destinationName = poi.name
        self.destinationCoord = destCoord
        // 준비 화면에서도 총 거리를 보여준다(§3). following.start 이후에는 §4-3 갱신값이 덮어쓴다.
        self.remainingText = "목적지까지 약 \(distanceText)"
        self.nextCrosswalkText = nil
        self.lastSpokenRemaining = nil
        self.lastDisplayedRemaining = nil
        self.guidanceStarted = false
        phase = .guiding
    }

    /// 「안내 시작」 버튼 탭(§4-1) → 실제 추종 시작.
    /// 이 시점부터 위치 업데이트가 남은거리·횡단보도 안내로 이어진다.
    func startGuidance() {
        guard phase == .guiding, !guidanceStarted else { return }   // 중복 탭 방지
        guard let route = currentRoute, let dest = destinationCoord else { return }
        guidanceStarted = true
        tts.speak("경로 안내를 시작합니다.", display: true)
        following.start(route: route, destination: dest)
    }

    /// 경로상 횡단보도 진입(FollowingController 콜백) → 신호등 탭으로 자동 전환.
    /// phase 는 .guiding 을 유지하고 탭만 바꾼다(§3-2). detector 는 탭 1 onAppear 가 시작(§5).
    /// 발화만 신호등 상태로 분기 — 탭 전환·반경·인덱스 로직은 그대로.
    func enterCrossing(crosswalkOrdinal: Int) {
        guard phase == .guiding else { return }
        // §4 규칙 2 — 사용자가 이미 수동 조작했다면 자동 전환하지 않는다(탭에 갇힘 방지).
        guard !userOverride else { return }
        // §4 규칙 1 — 자동 전환. .noneNearby 만 문구 분기, 나머지는 기존 발화 유지.
        // 카메라 탭 전환은 모든 상태에서 유지 (신호등이 정말 없더라도 카메라로 확인 기회를 남긴다).
        let waypointIdx = crosswalkWaypointIndices.indices.contains(crosswalkOrdinal)
            ? crosswalkWaypointIndices[crosswalkOrdinal] : -1
        let status = crosswalkSignalStatus[waypointIdx] ?? .noData
        let phrase: String
        switch status {
        // ⚠️ 문구는 **데이터가 말하는 것**만 말한다. "신호등 없는 횡단보도입니다" 는
        //    사실 단언인데, 근거는 2026-02 시점 서울시 목록 하나뿐이고 오탐률은 측정된 적이 없다.
        //    신설·누락·갱신 지연이면 신호등이 있는 교차로에서 차 소리만 듣고 건너게 된다.
        //    "확인되지 않는다" 는 틀렸을 때도 사용자를 위험한 확신으로 밀지 않는다.
        case .noneNearby: phrase = "신호등이 확인되지 않는 횡단보도입니다. 차량 소리와 주변을 확인하세요."
        case .hasNearby, .noData: phrase = "횡단보도입니다. 신호를 확인하세요."
        }
        tts.speak(phrase, display: true)
        selectedTab = 1
        enteredSignalTabAutomatically = true
    }

    /// 횡단보도 이탈(FollowingController 콜백) → 조건부 자동 복귀.
    func exitCrossing() {
        guard phase == .guiding else { return }
        // §4 규칙 5 — 자동으로 들어왔던 경우에만 탭 0으로 복귀(사용자 진입은 존중, 규칙 6).
        if enteredSignalTabAutomatically {
            selectedTab = 0
            enteredSignalTabAutomatically = false
        }
        // §4 규칙 5·6 공통 — override 리셋(다음 zone 진입 시 자동 전환 재허용).
        userOverride = false
    }

    // MARK: - 탭 전환 (§4)

    /// 사용자가 탭바로 직접 탭을 선택했을 때(MainTabView 의 Binding.set) — 규칙 3·4.
    /// 사용자 조작은 자동 전환보다 우선하므로 userOverride 를 세운다.
    func selectTabByUser(_ index: Int) {
        guard index != selectedTab else { return }
        userOverride = true                       // 규칙 3·4
        if index == 1 {
            enteredSignalTabAutomatically = false // 규칙 4 — 수동 진입은 자동 복귀 대상 아님
            // §4-1 수동 전환 — 1회 안내. 자동 전환 발화("횡단보도입니다…")와 구분된다.
            tts.speak("신호등 확인 화면입니다.", priority: .high)
        }
        selectedTab = index
    }

    /// 탭 플래그 초기화 + 탭 0 강제 복귀(§4 규칙 7). arrive/reset 에서 호출.
    /// selectedTab = 0 이 되면 탭 1의 onDisappear 가 detector 를 정지시킨다(§5).
    private func resetTabState() {
        userOverride = false
        enteredSignalTabAutomatically = false
        selectedTab = 0
    }

    /// 목적지 도착(FollowingController 콜백).
    func arrive() {
        following.stop()
        // §5 — detector 는 직접 멈추지 않는다. resetTabState 가 selectedTab = 0 을 강제하면
        //        탭 1 onDisappear 가 정지시킨다(규칙 7 선행 완료 전제).
        resetTabState()
        guidanceStarted = false
        crosswalkSignalStatus = [:]
        crosswalkWaypointIndices = []
        // §6 예외 — 도착 안내는 탭 1에서도 반드시 발화한다.
        tts.speakImmediately("목적지에 도착했습니다.", display: true)
        phase = .arrived
    }

    /// 목적지까지 남은 직선거리 갱신(FollowingController 콜백).
    /// 화면 문구는 마지막 표시값과 10m 이상 차이날 때만 갱신(GPS 흔들림에 의한 표시 출렁임 방지),
    /// 음성은 ~50m 단위로만. VoiceOver accessibilityValue 는 remainingText 를 그대로 읽으므로 함께 갱신된다.
    private func updateRemaining(_ meters: Int) {
        let rounded = (meters / 10) * 10
        if lastDisplayedRemaining.map({ abs(meters - $0) >= 10 }) ?? true {
            lastDisplayedRemaining = meters
            remainingText = "목적지까지 약 \(rounded)미터"
        }
        if let last = lastSpokenRemaining, last - meters < 50 { return }
        lastSpokenRemaining = meters
        // §6-1 — 신호등 탭에 있는 동안 경로 안내 음성 억제(신호 안내가 우선). 상태 갱신은 유지.
        //         phase 는 횡단 중에도 .guiding 이므로 selectedTab 로 판정한다(조건 삭제 아님, 교체).
        if selectedTab != 1 {
            tts.speak("목적지까지 약 \(rounded)미터", display: false)
        }
    }

    /// 다음 횡단보도까지 남은거리 갱신(화면 표시 전용, 음성 없음).
    private func updateNextCrosswalk(_ meters: Int?) {
        guard let meters else { nextCrosswalkText = nil; return }
        nextCrosswalkText = "다음 횡단보도까지 약 \((meters / 10) * 10)미터"
    }

    /// 처음(목적지 입력)으로 복귀.
    func reset() {
        following.stop()
        // §5 — 직접 정지 대신 탭 0 강제 복귀(§4 규칙 7)로 탭 1 onDisappear 가 detector 를 멈춘다.
        resetTabState()
        guidanceStarted = false
        crosswalkSignalStatus = [:]
        crosswalkWaypointIndices = []
        currentRoute = nil
        destinationName = nil
        remainingText = nil
        nextCrosswalkText = nil
        destinationCoord = nil
        lastSpokenRemaining = nil
        lastDisplayedRemaining = nil
        phase = .destinationInput
    }
}
