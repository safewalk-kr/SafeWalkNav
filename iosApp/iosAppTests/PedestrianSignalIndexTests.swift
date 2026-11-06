//
//  PedestrianSignalIndexTests.swift
//  iosAppTests
//
//  진단 v2-b(analysis/traffic_signal_check)에서 확인된 값으로 고정한 회귀 테스트.
//  좌표는 TMap 코엑스 경로의 실제 CROSSWALK waypoint, 기대 개수는 out/crosswalk_match.csv.
//

import XCTest
@testable import SafeWalkNav

final class PedestrianSignalIndexTests: XCTestCase {

    private var index: PedestrianSignalIndex!

    override func setUp() async throws {
        index = PedestrianSignalIndex.shared
        let ok = await withCheckedContinuation { cont in
            index.loadIfNeeded { cont.resume(returning: $0) }
        }
        try XCTSkipUnless(ok || index.loadedPointCount != nil, "인덱스 로드 실패")
        XCTAssertTrue(ok, "번들 CSV 로드 실패")
    }

    func testTotalPointCount() {
        XCTAssertEqual(index.loadedPointCount, 25_378)
    }

    func testCoexCrosswalk1_r50() {
        // 봉은사역 7번출구 횡단보도 (d_min 6.9m)
        XCTAssertEqual(index.countWithin(lat: 37.513957, lon: 127.059740, radiusM: 50), 3)
    }

    func testCoexCrosswalk0_r50() {
        // 동측 횡단보도 (d_min 18.9m)
        XCTAssertEqual(index.countWithin(lat: 37.511621, lon: 127.062023, radiusM: 50), 3)
    }

    func testCoexCrosswalk0_r15_zero() {
        XCTAssertEqual(index.countWithin(lat: 37.511621, lon: 127.062023, radiusM: 15), 0)
    }

    func testStatusInsideSeoul() {
        XCTAssertEqual(index.status(lat: 37.513957, lon: 127.059740), .hasNearby)
    }

    func testGimhaeOutsideCoverage() {
        // 김해시 좌표 (전국신호등표준데이터 첫 행) — 서울 bbox 밖.
        XCTAssertFalse(index.isInsideCoverage(lat: 35.278503, lon: 128.766609))
        XCTAssertEqual(index.status(lat: 35.278503, lon: 128.766609), .noData)
    }

    // MARK: - 커버리지 판정 (2026-09)

    /// bbox 사각형 안이지만 데이터가 0개인 도시들.
    ///
    /// 이전 구현은 이 지점들을 "커버리지 안"으로 보고 `.noneNearby` 를 돌려줬다.
    /// 그러면 앱이 신호등 있는 교차로에서도 "신호등이 확인되지 않는 횡단보도"라고 말하고,
    /// `.noData` 가 아니므로 면책 고지조차 나가지 않는다.
    /// 반드시 `.noData` 여야 한다.
    func testBBoxInsideButNoDataCities() {
        let cities: [(String, Double, Double)] = [
            ("부천시청",      37.5035, 126.7660),
            ("고양 화정",     37.6350, 126.8320),
            ("과천시청",      37.4292, 126.9877),
            ("구리시청",      37.5943, 127.1296),
            ("성남 수정구청",  37.4503, 127.1290),
        ]
        for (name, lat, lon) in cities {
            XCTAssertEqual(index.countWithin(lat: lat, lon: lon, radiusM: 1000), 0,
                           "\(name): 전제가 깨졌다 — 데이터가 생겼다면 이 테스트를 재검토할 것")
            XCTAssertFalse(index.isInsideCoverage(lat: lat, lon: lon), "\(name) 커버리지 밖이어야 함")
            XCTAssertEqual(index.status(lat: lat, lon: lon), .noData,
                           "\(name): .noneNearby 로 단언하면 안 된다")
        }
    }

    func testSeoulIsCovered() {
        // 서울 시내 지점들은 커버리지 안으로 판정돼야 한다.
        for (lat, lon) in [(37.5663, 126.9779), (37.513957, 127.059740), (37.5416, 126.8402)] {
            XCTAssertTrue(index.isInsideCoverage(lat: lat, lon: lon))
        }
    }

    /// 이웃 셀 탐색 범위가 반경에서 유도되는지.
    ///
    /// 예전에는 ±1 셀(경도 기준 약 88m)로 고정돼 있어서, 반경이 그보다 크면 조용히 과소 계수했다.
    /// 과소 계수는 "신호등 없음" 쪽으로 틀리므로 가장 위험한 방향이다.
    /// bbox 빠른 기각과 커버리지 반경의 불변식.
    ///
    /// bbox 는 데이터 실제 범위보다 넓지만 여백이 무한하지 않다(북쪽이 가장 좁아 약 880m).
    /// 커버리지 반경이 그 여백을 넘으면, bbox 밖에 있어서 세지 못한 점 때문에
    /// "데이터 없음"을 "신호등 없음"으로 잘못 판정하는 길이 다시 열린다.
    func testCoverageRadiusFitsInsideBBoxMargin() {
        // 데이터 실제 최대 위도 37.692283 vs bbox latMax 37.70 → 약 880m
        let marginM = (PedestrianSignalIndex.seoulBBox.latMax - 37.692283) * 111_320
        XCTAssertLessThan(
            PedestrianSignalIndex.coverageRadiusM, marginM,
            "coverageRadiusM 를 올리려면 seoulBBox 도 함께 넓혀야 한다"
        )
    }

    func testLargeRadiusSearchesEnoughCells() {
        let lat = 37.5663, lon = 126.9779   // 서울시청
        let r50 = index.countWithin(lat: lat, lon: lon, radiusM: 50) ?? -1
        let r500 = index.countWithin(lat: lat, lon: lon, radiusM: 500) ?? -1
        XCTAssertGreaterThan(r500, r50, "반경을 10배 늘렸는데 개수가 안 늘면 셀 탐색이 모자란 것")
        XCTAssertGreaterThan(r500, 0)
    }
}
