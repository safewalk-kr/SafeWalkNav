package com.example.safewalknav.navigation.nearby

import com.example.safewalknav.navigation.geo.clockHour
import com.example.safewalknav.navigation.geo.distanceBetween
import com.example.safewalknav.navigation.geo.initialBearingDeg
import com.example.safewalknav.navigation.geo.relativeBearingDeg
import com.example.safewalknav.navigation.tmap.AddressInfo
import com.example.safewalknav.navigation.tmap.POIResult
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 기능1(내 위치·주변 장소) 공용 로직 테스트.
 * 기준점: 동국대 정문 부근 (37.5582, 126.9982). 위도 1e-4° ≈ 11.1m, 경도 1e-4° ≈ 8.8m.
 */
class NearbyTest {

    private val lat0 = 37.5582
    private val lon0 = 126.9982

    // ───────── 방위각 / 시계 방향 ─────────

    @Test
    fun 방위각_정북_정동_정남_정서() {
        assertNear(0.0, initialBearingDeg(lat0, lon0, lat0 + 0.0002, lon0), 0.5)
        assertNear(90.0, initialBearingDeg(lat0, lon0, lat0, lon0 + 0.0002), 0.5)
        assertNear(180.0, initialBearingDeg(lat0, lon0, lat0 - 0.0002, lon0), 0.5)
        assertNear(270.0, initialBearingDeg(lat0, lon0, lat0, lon0 - 0.0002), 0.5)
    }

    @Test
    fun 시계방향_기본() {
        assertEquals(12, clockHour(0.0))
        assertEquals(3, clockHour(90.0))
        assertEquals(6, clockHour(180.0))
        assertEquals(9, clockHour(270.0))
        assertEquals(12, clockHour(359.0))
        assertEquals(12, clockHour(14.9))
        assertEquals(1, clockHour(15.0))   // 경계는 다음 칸
        assertEquals(11, clockHour(-30.0)) // 음수도 정규화
    }

    @Test
    fun 상대방위_내가_동쪽을_보면_북쪽은_9시() {
        val north = initialBearingDeg(lat0, lon0, lat0 + 0.0002, lon0)
        assertEquals(9, clockHour(relativeBearingDeg(north, headingDeg = 90.0)))
        // 내가 서쪽(270)을 보면 북쪽은 3시
        assertEquals(3, clockHour(relativeBearingDeg(north, headingDeg = 270.0)))
    }

    // ───────── 위치 보정 ─────────

    @Test
    fun 위치보정_나쁜_픽스는_버리고_좋은_픽스만_평균() {
        val samples = listOf(
            LocationSample(lat0 + 0.0010, lon0, 60f, 0),      // 첫 픽스: 기지국급, 110m 북쪽
            LocationSample(lat0 + 0.00002, lon0, 6f, 1000),   // 2m 북쪽
            LocationSample(lat0 - 0.00002, lon0, 6f, 2000),   // 2m 남쪽
        )
        val fix = assertNotNull(LocationFusion.fuse(samples))
        assertEquals(2, fix.sampleCount)
        assertTrue(distanceBetween(fix.lat, fix.lon, lat0, lon0) < 1f)
        // 평균했다고 오차를 줄여 보고하지 않는다
        assertEquals(6f, fix.accuracyM)
    }

    @Test
    fun 위치보정_튄_값_제거() {
        val samples = listOf(
            LocationSample(lat0, lon0, 5f, 0),
            LocationSample(lat0 + 0.00001, lon0, 5f, 1000),
            LocationSample(lat0 + 0.0003, lon0, 8f, 2000),   // 33m 튐 (자기 오차 8m 의 2배 초과)
        )
        val fix = assertNotNull(LocationFusion.fuse(samples))
        assertEquals(2, fix.sampleCount)
        assertTrue(distanceBetween(fix.lat, fix.lon, lat0, lon0) < 2f)
    }

    @Test
    fun 위치보정_서로_엇갈린_픽스는_중간점에_정밀도를_붙이지_않는다() {
        // 둘 다 "오차 5m" 라는데 55m 떨어져 있다 — 평균(중간점)은 아무 데도 아니다
        val samples = listOf(
            LocationSample(lat0, lon0, 5f, 1000),
            LocationSample(lat0 + 0.0005, lon0, 5f, 2000),   // 55m 북쪽, 더 최근
        )
        val fix = assertNotNull(LocationFusion.fuse(samples))
        // 가장 최근의 가장 정확한 픽스를 쓰고
        assertTrue(distanceBetween(fix.lat, fix.lon, lat0 + 0.0005, lon0) < 0.5f)
        // 오차는 엇갈린 만큼(≈55m) 넓혀 보고 → "정확하지 않을 수 있습니다"가 나간다
        assertTrue(fix.accuracyM > 50f, "accuracy=${fix.accuracyM}")
    }

    @Test
    fun 위치보정_빈_입력() {
        assertNull(LocationFusion.fuse(emptyList()))
        assertNull(LocationFusion.fuse(listOf(LocationSample(lat0, lon0, 0f, 0))))
    }

    @Test
    fun 조기종료_기준() {
        assertFalse(LocationFusion.isGoodEnough(listOf(LocationSample(lat0, lon0, 5f, 0))))
        // 좋은 픽스 둘이라도 1초 사이면 아직 — 멀티패스 오차는 짧은 시간 안에서 같은 쪽으로 틀려 맞아 보인다
        assertFalse(
            LocationFusion.isGoodEnough(
                listOf(LocationSample(lat0, lon0, 5f, 0), LocationSample(lat0, lon0, 7f, 1000))
            )
        )
        // 4개 이상 + 3초 이상 + 좋은 픽스 2개 → 조기 종료
        assertTrue(
            LocationFusion.isGoodEnough(
                listOf(
                    LocationSample(lat0, lon0, 20f, 0), LocationSample(lat0, lon0, 12f, 1000),
                    LocationSample(lat0, lon0, 5f, 2000), LocationSample(lat0, lon0, 7f, 3000),
                )
            )
        )
        // 4개라도 시간 폭이 3초 미만이면 아직
        assertFalse(
            LocationFusion.isGoodEnough(
                listOf(
                    LocationSample(lat0, lon0, 5f, 0), LocationSample(lat0, lon0, 5f, 500),
                    LocationSample(lat0, lon0, 5f, 1000), LocationSample(lat0, lon0, 5f, 2000),
                )
            )
        )
        // 3초가 넘어도 3개뿐이면 아직
        assertFalse(
            LocationFusion.isGoodEnough(
                listOf(LocationSample(lat0, lon0, 5f, 0), LocationSample(lat0, lon0, 5f, 2000), LocationSample(lat0, lon0, 5f, 4000))
            )
        )
        // 수·시간이 충분해도 좋은 픽스가 모자라면 아직
        assertFalse(
            LocationFusion.isGoodEnough(
                listOf(
                    LocationSample(lat0, lon0, 12f, 0), LocationSample(lat0, lon0, 15f, 1000),
                    LocationSample(lat0, lon0, 5f, 2000), LocationSample(lat0, lon0, 15f, 3000),
                )
            )
        )
    }

    // ───────── 반경 정책 ─────────

    @Test
    fun 반경_오차가_작으면_10m() {
        assertEquals(10, NearbyPolicy.baseRadius(4f))
        assertEquals(10, NearbyPolicy.baseRadius(Float.NaN))
    }

    @Test
    fun 반경_오차가_크면_오차만큼_넓히되_50m_상한() {
        assertEquals(17, NearbyPolicy.baseRadius(16.2f))
        assertEquals(50, NearbyPolicy.baseRadius(120f))
    }

    @Test
    fun 반경_결과가_모자라면_단계적으로_넓힘() {
        assertEquals(10, NearbyPolicy.chooseRadius(10, listOf(3f, 5f, 9f, 40f)))
        assertEquals(30, NearbyPolicy.chooseRadius(10, listOf(3f, 25f, 28f)))
        // 주택가처럼 가까이에 없으면 100m 까지
        assertEquals(100, NearbyPolicy.chooseRadius(10, listOf(3f, 60f, 90f)))
        // 끝까지 모자라면 최대 300m — "없다"만 반복하지 않는다
        assertEquals(300, NearbyPolicy.chooseRadius(10, listOf(3f)))
        // 시작 반경이 17 이면 17 → 30 → 50 …
        assertEquals(30, NearbyPolicy.chooseRadius(17, listOf(12f, 16f, 29f)))
    }

    // ───────── 조립 ─────────

    @Test
    fun 조립_중복제거_중심좌표_거리순() {
        // 입구(front) 좌표는 차량 진입점이라 쓰지 않는다 — 중심 좌표(55m)로 계산해야 한다
        val cu = POIResult("CU 필동점", lat0 + 0.0005, lon0, "", frontLat = lat0 + 0.00005, frontLon = lon0)
        val cuDup = cu.copy()  // 다른 카테고리 검색에서 또 걸림
        val cafe = POIResult("카페A", lat0, lon0 + 0.0001, "", category = "커피전문점")
        val far = POIResult("먼곳", lat0 + 0.002, lon0, "")

        val r = NearbyAssembler.assemble(
            lat0, lon0, accuracyM = 5f,
            poisByCategory = listOf("편의점" to listOf(cu, far), "카페" to listOf(cafe), "음식점" to listOf(cuDup)),
        )
        assertEquals(listOf("카페A", "CU 필동점", "먼곳"), r.places.map { it.name })
        assertTrue(r.places[1].distanceM in 50f..60f, "중심 좌표 기준 ≈55m, got ${r.places[1].distanceM}")
        // 응답의 업종명이 있으면 그걸, 없으면 검색 카테고리
        assertEquals("커피전문점", r.places[0].category)
        assertEquals("편의점", r.places[1].category)
        // 3곳을 채우려고 300m 까지 넓혔다 (먼곳 222m 포함)
        assertEquals(300, r.radiusM)
        assertTrue(r.expanded)
    }

    // ───────── 기준점(랜드마크) ─────────

    @Test
    fun 기준점_이름_판별() {
        assertTrue(LocationAnchors.isSubwayExit("충무로역 1번출구"))
        assertTrue(LocationAnchors.isSubwayExit("을지로3가역 12번 출구"))
        assertFalse(LocationAnchors.isSubwayExit("롯데마트 주차장 출구"))
        assertTrue(LocationAnchors.isIntersection("필동사거리"))
        assertTrue(LocationAnchors.isIntersection("퇴계로3가교차로"))
        assertFalse(LocationAnchors.isIntersection("사거리순대국"))
        assertTrue(LocationAnchors.isBusStop(POIResult("동국대입구.한국의집", lat0, lon0, "", category = "버스정류장")))
        assertFalse(LocationAnchors.isBusStop(POIResult("정류장 앞 카페", lat0, lon0, "", category = "커피전문점")))
    }

    @Test
    fun 기준점_종류별_가장가까운것_거리순_최대2개() {
        val exitNear = POIResult("충무로역 1번출구", lat0 + 0.0007, lon0, "")        // ≈78m
        val exitFar = POIResult("충무로역 8번출구", lat0 + 0.0020, lon0, "")         // ≈222m
        val stop = POIResult("동국대입구", lat0, lon0 - 0.00045, "", category = "버스정류장") // ≈40m
        val crossingTooFar = POIResult("필동사거리", lat0 - 0.0018, lon0, "")        // ≈200m > 150m
        val anchors = LocationAnchors.pick(
            lat0, lon0,
            exitCandidates = listOf(exitFar, exitNear),
            busStopCandidates = listOf(stop),
            intersectionCandidates = listOf(crossingTooFar),
        )
        assertEquals(listOf(AnchorKind.BUS_STOP, AnchorKind.SUBWAY_EXIT), anchors.map { it.kind })
        assertEquals("충무로역 1번출구", anchors[1].name)
        assertEquals("동국대입구 버스정류장", anchors[0].spokenName)
    }

    // ───────── 보행 신호등 격자 인덱스 ─────────

    private val csv = """
        lat,lon
        37.5582,126.9982
        37.5582,126.9982
        37.55835,126.9982
        37.5600,126.9982
        잘못된행
        37.5582,
    """.trimIndent()

    @Test
    fun 격자인덱스_중복좌표_제거_잘못된행_건너뜀() {
        val idx = PointGridIndex.fromCsv(csv)
        assertEquals(3, idx.size)   // 같은 좌표 2행은 하나로, 깨진 행 2개는 버림
    }

    @Test
    fun 격자인덱스_반경_거리순() {
        val idx = PointGridIndex.fromCsv(csv)
        val near = idx.nearest(lat0, lon0, 50.0)
        assertEquals(2, near.size)                    // 0m, 16.7m (200m 짜리는 밖)
        assertTrue(near[0].second < near[1].second)
        assertEquals(3, idx.countWithin(lat0, lon0, 250.0))
        // 셀(약 85m)보다 큰 반경도 과소 계수하지 않는다
        assertEquals(1, idx.countWithin(lat0 + 0.0018, lon0, 30.0))
    }

    @Test
    fun 격자인덱스_데이터가_닿지않는곳은_커버리지밖() {
        val idx = PointGridIndex.fromCsv(csv)
        assertTrue(idx.isInsideCoverage(lat0, lon0))
        // 서울 사각형 안이지만 500m 안에 점이 없음 → '신호등 없음'이 아니라 '데이터 없음'
        assertFalse(idx.isInsideCoverage(lat0 + 0.02, lon0))
        // 사각형 밖
        assertFalse(idx.isInsideCoverage(35.1, 129.0))
    }

    @Test
    fun 실제_서울보행등_파일_형식() {
        // 앱에 넣은 파일과 같은 형식(헤더 lat,lon / 소수점 6자리)이 파싱되는지
        val sample = "lat,lon\n37.605854,126.967236\n37.572614,126.987718\n"
        assertEquals(2, PointGridIndex.fromCsv(sample).size)
    }

    @Test
    fun 기준점_가까운_보행신호등이_먼저() {
        val signal = GeoPoint(lat0 + 0.0001, lon0) to 11f
        val stop = POIResult("동국대입구", lat0, lon0 - 0.00045, "", category = "버스정류장")
        val anchors = LocationAnchors.pick(lat0, lon0, emptyList(), listOf(stop), emptyList(), signal)
        assertEquals(listOf(AnchorKind.PED_SIGNAL, AnchorKind.BUS_STOP), anchors.map { it.kind })
        assertEquals("보행 신호등", anchors[0].spokenName)
        // 50m 밖 신호등은 기준점이 아니다
        val farSignal = GeoPoint(lat0 + 0.001, lon0) to 111f
        assertEquals(
            listOf(AnchorKind.BUS_STOP),
            LocationAnchors.pick(lat0, lon0, emptyList(), listOf(stop), emptyList(), farSignal).map { it.kind },
        )
    }

    @Test
    fun 방향찾기_안내문구() {
        val p = NearbyPlace("CU", "편의점", lat0, lon0, 30f)
        val s = NearbyPhrases.placeDetail(p, 2, null, beaconStarted = true)
        assertTrue(s.startsWith("CU. 2시 방향, 약 30미터."))
        assertTrue(s.contains("진동"))
        // 끄는 법까지 한 번에 알려 준다
        assertTrue(s.contains("진동을 끄려면 같은 장소를 다시 누르세요"))
        // 끄기·시간 종료 안내는 "방향 찾기" 대신 느끼는 것(진동)으로 말한다
        assertEquals("진동을 껐습니다.", NearbyPhrases.BEACON_STOPPED)
        assertTrue(NearbyPhrases.BEACON_TIMEOUT.startsWith("진동이 끝났습니다."))
    }

    // ───────── 문구 ─────────

    @Test
    fun 거리문구() {
        assertEquals("1미터 이내", NearbyPhrases.distanceText(0.4f))
        assertEquals("약 7미터", NearbyPhrases.distanceText(7.2f))
        assertEquals("약 15미터", NearbyPhrases.distanceText(13.0f))
        assertEquals("약 45미터", NearbyPhrases.distanceText(46.9f))
    }

    @Test
    fun 상세문구_방향없으면_지어내지_않음() {
        val p = NearbyPlace("CU", "편의점", lat0, lon0, 8f)
        assertEquals("CU. 2시 방향, 약 8미터.", NearbyPhrases.placeDetail(p, 2, null))
        val noDir = NearbyPhrases.placeDetail(p, null, NearbyPhrases.NoDirectionReason.UNRELIABLE)
        assertFalse(noDir.contains("시 방향"))
        assertTrue(noDir.contains("나침반"))
        // 오차 원 안이면 방향을 말하되 단서를 붙인다 — 방향 앞에 "대략"
        val rough = NearbyPhrases.placeDetail(p, 2, null, withinError = true)
        assertTrue(rough.contains("정확하지 않을 수"))
        assertTrue(rough.contains("대략 2시 방향"), rough)
        assertTrue(rough.contains("위치 오차가 커서"), rough)
        // 보고 오차는 작은데 가까워서 걸린 경우 — 이유를 사실대로("오차가 커서"라고 하지 않는다)
        val close = NearbyPhrases.placeDetail(p, 2, null, withinError = true, closeRange = true)
        assertTrue(close.contains("가까운 곳이라"), close)
        assertFalse(close.contains("오차가 커서"), close)
        // 진동을 일부러 안 켰으면 그렇다고 말한다 — 말없이 안 켜면 고장 난 줄 안다
        val withheld = NearbyPhrases.placeDetail(p, 2, null, withinError = true, beaconWithheld = true)
        assertTrue(withheld.contains("진동 안내는 하지 않습니다"), withheld)
        // 위치가 오래됐으면 방향도 거리도 말하지 않는다
        val stale = NearbyPhrases.placeDetail(p, null, NearbyPhrases.NoDirectionReason.STALE_LOCATION)
        assertFalse(stale.contains("시 방향"))
        assertFalse(stale.contains("8미터"))
        assertTrue(stale.contains("다시 찾기 버튼을 눌러 주세요"), stale)
    }

    @Test
    fun 실패문구는_누를_버튼과_도움청하기를_알려줌() {
        // "다시 시도해 주세요"로 끝내지 않는다 — 보이는 버튼 이름 그대로 부른다.
        val failures = listOf(
            NearbyPhrases.SEARCH_FAILED,
            NearbyPhrases.LOCATION_FAILED,
            NearbyPhrases.LOCATION_OFF,
            NearbyPhrases.STALE_LOCATION_HINT,
        )
        for (s in failures) {
            assertTrue(s.contains("다시 찾기 버튼을 눌러 주세요"), s)
            assertFalse(s.contains("다시 시도"), s)
            // 두 플랫폼이 같이 쓰는 문구 — 안드로이드 화면 배치 말은 넣지 않는다.
            assertFalse(s.contains("맨 아래") || s.contains("왼쪽"), s)
        }
        // 혼자 못 고치는 원인이면 누구에게 무엇을 부탁할지까지
        assertTrue(NearbyPhrases.SEARCH_FAILED.contains("주변 분께"))
        assertTrue(NearbyPhrases.LOCATION_OFF.contains("주변 분께"))
        // 진동이 화면을 떠나며 멈췄을 때 — 다시 켜는 법까지
        assertTrue(NearbyPhrases.BEACON_INTERRUPTED.contains("장소를 다시 누르세요"))
    }

    @Test
    fun 요약문구는_장소이름을_말하지_않음() {
        val p = NearbyPlace("비밀편의점", "편의점", lat0, lon0, 8f)
        val s = NearbyPhrases.resultSummary(NearbyResult(listOf(p), radiusM = 10, expanded = false))
        assertFalse(s.contains("비밀편의점"))
        assertTrue(s.contains("1곳"))
        // 많이 넓혔으면 가장 가까운 거리만 덧붙인다
        val far = NearbyPlace("먼곳", "음식점", lat0, lon0, 120f)
        val s2 = NearbyPhrases.resultSummary(NearbyResult(listOf(far), radiusM = 200, expanded = true))
        assertTrue(s2.contains("가장 가까운 곳까지 약 120미터"))
    }

    private val addr = AddressInfo(
        fullAddress = "서울특별시 중구 필동2가 82-1,서울특별시 중구 필동",
        roadName = "필동로1길", buildingIndex = "30", buildingName = "동국대학교",
        legalDong = "필동2가", bunji = "82-1",
    )

    @Test
    fun 현재위치문구는_도로명을_말하지_않고_기준점으로_말한다() {
        val stop = Anchor(AnchorKind.BUS_STOP, "동국대입구", lat0, lon0, 40f)
        val exit = Anchor(AnchorKind.SUBWAY_EXIT, "충무로역 1번출구", lat0, lon0, 78f)
        val s = NearbyPhrases.currentLocation(addr, listOf(stop, exit), 8f) { a ->
            if (a.kind == AnchorKind.BUS_STOP) 9 else null   // 출구는 방향 모름(나침반 불량 가정)
        }
        assertEquals(
            "현재 동국대학교 근처입니다. 동국대입구 버스정류장, 9시 방향 약 40미터. 충무로역 1번출구, 약 80미터.",
            s
        )
        assertFalse(s.contains("필동로"))
        assertFalse(s.contains("30"))
    }

    @Test
    fun 현재위치문구_건물없으면_동이름_아무것도없으면_솔직하게() {
        val noBuilding = addr.copy(buildingName = "")
        assertEquals("현재 필동2가입니다.", NearbyPhrases.currentLocation(noBuilding, emptyList(), 8f) { null })
        val empty = AddressInfo(null, null, null, null, null, null)
        assertEquals("현재 위치의 이름을 찾지 못했습니다.", NearbyPhrases.currentLocation(empty, emptyList(), 8f) { null })
        assertEquals("현재 위치의 이름을 찾지 못했습니다.", NearbyPhrases.currentLocation(null, emptyList(), 8f) { null })
    }

    @Test
    fun 현재위치문구_오차가_크면_경고() {
        assertFalse(NearbyPhrases.currentLocation(addr, emptyList(), 8f) { null }.contains("정확하지"))
        val poor = NearbyPhrases.currentLocation(addr, emptyList(), 35f) { null }
        assertTrue(poor.contains("정확하지"))
        // 화면 위치 말("아래")을 쓰지 않는다 — 듣는 사람에게는 위아래가 없다
        assertFalse(poor.contains("아래"), poor)
    }

    @Test
    fun 화면_제목() {
        assertEquals("동국대학교 근처", NearbyPhrases.locationHeadline(addr, emptyList()))
        val stop = Anchor(AnchorKind.BUS_STOP, "동국대입구", lat0, lon0, 40f)
        assertEquals("동국대입구 버스정류장 약 40미터", NearbyPhrases.locationHeadline(addr.copy(buildingName = null), listOf(stop)))
        assertEquals("필동2가", NearbyPhrases.locationHeadline(addr.copy(buildingName = null), emptyList()))
    }

    private fun assertNear(expected: Double, actual: Double, tol: Double) {
        assertTrue(abs(expected - actual) <= tol, "expected $expected ± $tol but was $actual")
    }
}
