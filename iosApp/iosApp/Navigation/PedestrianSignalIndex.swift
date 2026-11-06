//
//  PedestrianSignalIndex.swift
//  iosApp
//
//  서울시 보행등 위치(공공누리 1유형, 서울특별시, 2026-02-13 갱신)를 격자 인덱스로 보관.
//  경로 탐색 시 CROSSWALK waypoint 의 신호등 유무 사전 판정에 쓴다.
//
//  ⚠️ 판정 반경·bbox 는 경험값이며 오탐률 미측정 — 긍정 안내("신호등 있음")에 쓰지 말 것.
//     부정 판정(.noneNearby)과 데이터 없음 고지(.noData)만 허용.
//

import Foundation
import CoreLocation

/// 횡단보도별 신호등 유무 상태 (횡단보도 단위 판정).
enum CrosswalkSignalStatus {
    case noneNearby   // 데이터 범위 안 + 반경 50m 보행등 0개
    case hasNearby    // 데이터 범위 안 + 1개 이상 → 현재 동작 그대로 (긍정 안내 없음)
    case noData       // 데이터 범위(서울 bbox) 밖
}

final class PedestrianSignalIndex {
    static let shared = PedestrianSignalIndex()

    /// 표본 2개(d_min 6.9/18.9m) 기준 보수적 설정. 오탐률 미측정.
    nonisolated static let matchRadiusM: Double = 50

    /// 빠른 기각용 사각형. 데이터 실제 범위(위 37.434~37.692, 경 126.769~127.183)를
    /// 완전히 포함하므로, **이 밖이면 확실히 데이터 없음**이다.
    /// ⚠️ 반대는 성립하지 않는다 — 이 안이라고 데이터가 있는 게 아니다. [isInsideCoverage] 참조.
    nonisolated static let seoulBBox = (latMin: 37.40, latMax: 37.70, lonMin: 126.70, lonMax: 127.20)

    /// 이 반경 안에 보행등이 하나도 없으면 "데이터가 닿지 않는 지역"으로 본다.
    /// 서울 평균 밀도는 약 42개/km² 라 500m 반경이면 기대값이 30개를 넘는다.
    ///
    /// ⚠️ [seoulBBox] 와 **묶여 있다.** bbox 가 빠른 기각으로 먼저 걸러내므로,
    /// 이 반경이 bbox 여백(가장 좁은 북쪽이 약 880m)을 넘으면 bbox 밖에 있는 점을
    /// 못 세게 되어 과소 계수가 되살아난다. 과소 계수는 "신호등 없음" 쪽으로 틀리는
    /// 가장 위험한 방향이다. 이 값을 올릴 거면 bbox 도 같이 넓힐 것.
    /// (testCoverageRadiusFitsInsideBBoxMargin 이 이 불변식을 지킨다)
    ///
    /// 서울 안인데 500m 내 0개인 곳(북한산·우면산·올림픽공원 내부 등)은 .noData 가 되는데,
    /// 이는 경고를 잃는 것일 뿐 없는 신호등을 있다고 하지는 않으므로 안전한 방향이다.
    nonisolated static let coverageRadiusM: Double = 500

    /// 위도 0.001° ≈ 111m, 경도 0.001° ≈ 88m(위도 37.5 기준).
    private nonisolated static let cellDeg = 0.001

    /// 셀 한 변의 **최솟값**(경도 방향). 이웃 셀 탐색 범위를 반경에서 유도할 때 쓴다.
    private nonisolated static let minCellMeters: Double = 85

    private nonisolated static let resourceName = "pedlights_seoul_20260213"

    /// key = cellKey(latIdx, lonIdx). MainActor 에서만 접근(로드 완료 시 main 에서 대입).
    private var grid: [Int: [(lat: Double, lon: Double)]] = [:]
    private var loaded = false
    private var isLoading = false
    private var pendingCompletions: [(Bool) -> Void] = []

    /// 로드 완료 후 총 점 수. 로드 전 nil. (테스트·로그 검증용)
    private(set) var loadedPointCount: Int?

    /// 첫 경로 탐색 전(POI 선택 시점)에 1회 호출. CSV 파싱은 백그라운드,
    /// 완료 콜백은 main 에서. 실패해도 크래시 없이 loaded=false 유지 → 전부 .noData 처리.
    func loadIfNeeded(completion: @escaping (Bool) -> Void) {
        if loaded { completion(true); return }
        pendingCompletions.append(completion)
        guard !isLoading else { return }
        isLoading = true

        DispatchQueue.global(qos: .userInitiated).async {
            let result = Self.parseBundledCSV()
            DispatchQueue.main.async {
                if let (grid, count) = result {
                    self.grid = grid
                    self.loadedPointCount = count
                    self.loaded = true
                } else {
                    print("🚦 [PEDLIGHT] load failed — \(Self.resourceName).csv")
                }
                self.isLoading = false
                let completions = self.pendingCompletions
                self.pendingCompletions = []
                completions.forEach { $0(self.loaded) }
            }
        }
    }

    /// 이 지점에 **데이터가 실제로 존재하는가**.
    ///
    /// ⚠️ 2026-09 수정. 예전에는 서울 bbox 사각형 하나로만 판정했다. 그런데 서울은 사각형이 아니다.
    /// 그 사각형 안에는 데이터가 **0개**인 도시들이 통째로 들어 있다 — 실측:
    ///
    ///     부천시청 0개 / 고양 화정 0개 / 과천시청 0개 / 구리시청 0개 / 성남 수정구청 0개
    ///     (대조군: 서울시청 291개, 강남역 184개 — 모두 반경 1km)
    ///
    /// 그 지역 사용자는 신호등이 멀쩡히 있는 교차로에서도 `.noneNearby` 판정을 받아
    /// **"신호등 없는 횡단보도입니다. 차량 소리를 확인하세요"** 를 들었다. 게다가 `.noData` 가
    /// 아니므로 데이터 없음 고지조차 나가지 않았다. 시각장애인에게 가장 위험한 종류의 오안내다.
    ///
    /// 그래서 "경계 안인가" 대신 **"이 근처에 데이터가 실제로 있는가"** 를 묻는다.
    /// 반경 [coverageRadiusM] 안에 보행등이 하나도 없다면, 그건 '신호등이 없는 동네'가 아니라
    /// '우리 데이터가 닿지 않는 동네'로 보는 쪽이 안전하다.
    func isInsideCoverage(lat: Double, lon: Double) -> Bool {
        guard loaded else { return false }
        // 빠른 기각 — bbox 는 데이터 실제 범위를 완전히 포함하므로 이 밖은 확실히 없다.
        let b = Self.seoulBBox
        guard lat >= b.latMin, lat <= b.latMax, lon >= b.lonMin, lon <= b.lonMax else { return false }
        return hasAnyWithin(lat: lat, lon: lon, radiusM: Self.coverageRadiusM)
    }

    /// 반경 내 보행등 수. 로드 전이면 nil.
    func countWithin(lat: Double, lon: Double, radiusM: Double) -> Int? {
        guard loaded else { return nil }
        var count = 0
        forEachNearby(lat: lat, lon: lon, radiusM: radiusM) { _ in
            count += 1
            return true   // 계속 순회
        }
        return count
    }

    /// 횡단보도 단위 판정. 로드 전이면 nil (호출부는 .noData 로 취급 + 로그).
    func status(lat: Double, lon: Double) -> CrosswalkSignalStatus? {
        guard loaded else { return nil }
        guard isInsideCoverage(lat: lat, lon: lon) else { return .noData }
        guard let n = countWithin(lat: lat, lon: lon, radiusM: Self.matchRadiusM) else {
            return nil
        }
        return n == 0 ? .noneNearby : .hasNearby
    }

    // MARK: - 격자 순회

    /// 반경 안에 점이 하나라도 있는가. 첫 점에서 즉시 중단한다.
    private func hasAnyWithin(lat: Double, lon: Double, radiusM: Double) -> Bool {
        var found = false
        forEachNearby(lat: lat, lon: lon, radiusM: radiusM) { _ in
            found = true
            return false   // 중단
        }
        return found
    }

    /// 반경 안의 점을 순회한다. `body` 가 false 를 돌려주면 즉시 멈춘다.
    ///
    /// ⚠️ 이웃 셀 탐색 범위를 **반경에서 유도**한다. 예전에는 ±1 로 고정돼 있어서,
    /// 반경이 셀 한 변(경도 기준 약 88m)을 넘으면 조용히 과소 계수했다.
    /// 과소 계수는 "신호등 없음" 쪽으로 틀리므로 가장 위험한 방향의 버그다.
    private func forEachNearby(
        lat: Double, lon: Double, radiusM: Double,
        _ body: (Double) -> Bool
    ) {
        let span = max(1, Int(ceil(radiusM / Self.minCellMeters)))
        let latIdx = Int(floor(lat / Self.cellDeg))
        let lonIdx = Int(floor(lon / Self.cellDeg))
        let origin = CLLocationCoordinate2D(latitude: lat, longitude: lon)
        for dLat in -span...span {
            for dLon in -span...span {
                let key = Self.cellKey(latIdx: latIdx + dLat, lonIdx: lonIdx + dLon)
                guard let points = grid[key] else { continue }
                for p in points {
                    let coord = CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon)
                    let d = FollowingController.haversine(origin, coord)
                    if d <= radiusM, !body(d) { return }
                }
            }
        }
    }

    // MARK: - 내부

    /// 충돌 없음: 서울 bbox 에서 latIdx 37400~37700, lonIdx 126700~127200.
    private nonisolated static func cellKey(latIdx: Int, lonIdx: Int) -> Int {
        latIdx &* 100_000 &+ lonIdx
    }

    /// 번들 CSV(lat,lon 2열, 헤더 1줄) → 격자. 파싱 실패 행은 건너뛰고 개수 로그.
    private nonisolated static func parseBundledCSV() -> ([Int: [(lat: Double, lon: Double)]], Int)? {
        guard let url = Bundle(for: PedestrianSignalIndex.self)
            .url(forResource: resourceName, withExtension: "csv"),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return nil
        }
        var grid: [Int: [(lat: Double, lon: Double)]] = [:]
        var count = 0
        var skipped = 0
        for line in text.split(separator: "\n").dropFirst() {   // 헤더 1줄 제외
            let parts = line.split(separator: ",")
            guard parts.count == 2,
                  let lat = Double(parts[0]), let lon = Double(parts[1]) else {
                skipped += 1
                continue
            }
            let key = cellKey(latIdx: Int(floor(lat / cellDeg)),
                              lonIdx: Int(floor(lon / cellDeg)))
            grid[key, default: []].append((lat: lat, lon: lon))
            count += 1
        }
        if skipped > 0 {
            print("🚦 [PEDLIGHT] CSV 파싱 스킵 \(skipped)행")
        }
        print("🚦 [PEDLIGHT] index loaded — \(count)점, \(grid.count)셀")
        return (grid, count)
    }
}
