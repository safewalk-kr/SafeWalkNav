package com.example.safewalknav.navigation.tmap

import com.example.safewalknav.navigation.tmap.POIResult
import com.example.safewalknav.navigation.geo.distanceBetween
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * TMap REST API 클라이언트 (Ktor 기반, KMM commonMain).
 *
 * 기존 OkHttp+Gson 구현을 이식한 것이며 호출 인터페이스는 동일하다.
 *   - searchPedestrianRoute:  보행자 경로 탐색
 *   - searchPOI:              POI 키워드 검색 (목적지)
 *   - searchNearbyByKeyword:  내 위치 기준 거리순 키워드 검색 (기능1 주변 장소)
 *   - reverseGeocode(Detail): 좌표 → 주소
 *   - lastError:              마지막 호출의 사용자용 에러 메시지
 *
 * @param appKey TMap 개발자센터에서 발급받은 앱 키. Android 측에서는 BuildConfig.TMAP_APP_KEY 전달.
 *
 * NOTE: HttpClient 는 외부 주입하지 않고 내부에서 생성한다. 외부 주입을 노출하면
 *       app 모듈이 Ktor 의존성을 transitive 하게 보게 되어야 하는데, 그건 다음 단계의
 *       Logger expect/actual 패턴과 함께 정리할 예정.
 */
class TMapApiClient(
    private val appKey: String,
) {

    /** 마지막 API 오류 메시지 (UI에서 구체적 에러 표시용) */
    var lastError: String? = null
        private set

    /**
     * 진단 로그(요청·응답 미리보기·파싱 단계)를 찍을지. 기본 true(iOS 디버깅이 이 로그에 기대고 있다).
     * Android 는 릴리스에서 끈다 — 주변 검색 한 번에 70줄 넘게 찍혀 logcat 을 덮는다.
     */
    var verboseLogging: Boolean = true

    private fun log(message: String) {
        if (verboseLogging) println(message)
    }

    private val baseUrl = "https://apis.openapi.sk.com/tmap"

    private val httpClient: HttpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 10_000
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ========== 공개 API ==========

    /** 보행자 경로 탐색 */
    suspend fun searchPedestrianRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        startName: String = "출발지",
        endName: String = "목적지",
    ): TMapRoute? {
        lastError = null
        return runCatching {
            val response: HttpResponse = httpClient.post("$baseUrl/routes/pedestrian") {
                parameter("version", 1)
                contentType(ContentType.Application.Json)
                headers { append("appKey", appKey) }
                setBody(
                    """
                    {
                        "startX": "$startLon",
                        "startY": "$startLat",
                        "endX": "$endLon",
                        "endY": "$endLat",
                        "startName": "$startName",
                        "endName": "$endName",
                        "reqCoordType": "WGS84GEO",
                        "resCoordType": "WGS84GEO"
                    }
                    """.trimIndent()
                )
            }

            if (!response.status.isSuccess()) {
                lastError = "서버 오류(${response.status.value}). 다시 시도해주세요"
                return@runCatching null
            }

            parsePedestrianRoute(response.bodyAsText())
        }.getOrElse { e ->
            lastError = mapException(e)
            null
        }
    }

    /**
     * POI 검색 (목적지 검색) — 위치 기반 정렬 + 거리 필터.
     *
     * @param keyword 검색 키워드 (예: "스타벅스", "동국대")
     * @param currentLat 사용자 현재 위도 (null 이면 위치 무시 — 옛 동작)
     * @param currentLon 사용자 현재 경도 (null 이면 위치 무시)
     * @param radiusKm 검색 반경 (km). 기본 1km. 이 거리 초과 결과는 제외.
     * @param maxResults 최종 반환 개수. 기본 5. 거리 필터 후 부족하면 더 적게 반환.
     *
     * 동작:
     *   1) TMap API 에 centerLat/centerLon 전달 → 서버가 거리 기준 정렬
     *   2) 응답을 받으면 currentLat/currentLon 이 있을 때 클라이언트 측에서
     *      `radiusKm` 초과 결과를 제거하고 가까운 순으로 재정렬
     *   3) 최종 결과는 maxResults 개 이하 (5 개 미만일 수 있음 — 굳이 채우지 않음)
     *
     * 옛 호출부 호환을 위해 위치 인자는 nullable (default null).
     */
    suspend fun searchPOI(
        keyword: String,
        currentLat: Double? = null,
        currentLon: Double? = null,
        radiusKm: Float = 1.0f,
        maxResults: Int = 5,
    ): List<POIResult> {
        lastError = null
        log("🔍 [TMapApiClient.searchPOI] 시작 — keyword='$keyword' " +
                "currentLat=$currentLat currentLon=$currentLon " +
                "radiusKm=$radiusKm maxResults=$maxResults")
        return runCatching {
            val response: HttpResponse = httpClient.get("$baseUrl/pois") {
                parameter("version", 1)
                parameter("searchKeyword", keyword)
                // 거리 필터 후에도 maxResults 채울 수 있게 여유분 요청
                parameter("count", (maxResults * 2).coerceAtMost(20))
                // 위치 제공 시 서버 거리 기준 정렬
                if (currentLat != null && currentLon != null) {
                    parameter("centerLat", currentLat)
                    parameter("centerLon", currentLon)
                }
                headers { append("appKey", appKey) }
            }

            log("🔍 [TMapApiClient.searchPOI] HTTP status=${response.status.value}")

            if (!response.status.isSuccess()) {
                val errBody = runCatching { response.bodyAsText() }.getOrNull() ?: "<no body>"
                log("🔴 [TMapApiClient.searchPOI] 서버 오류 — body=${errBody.take(500)}")
                lastError = "서버 오류(${response.status.value}). 다시 시도해주세요"
                return@runCatching emptyList()
            }

            val body = response.bodyAsText()
            log("🔍 [TMapApiClient.searchPOI] 응답 수신 — length=${body.length}")
            log("🔍 [TMapApiClient.searchPOI] 응답 preview=${body.take(400)}")

            val raw = parsePOIResults(body)
            log("🔍 [TMapApiClient.searchPOI] 파싱 완료 — raw.size=${raw.size}")
            raw.forEachIndexed { i, p ->
                log("    [$i] name='${p.name}' lat=${p.lat} lon=${p.lon} addr='${p.address}'")
            }

            // 위치 미제공 시 서버 응답 그대로 반환 (옛 동작 호환)
            if (currentLat == null || currentLon == null) {
                val result = raw.take(maxResults)
                log("🔍 [TMapApiClient.searchPOI] 위치 없음 — 그대로 반환 ${result.size}개")
                return@runCatching result
            }

            // 클라이언트 측 거리 필터 + 재정렬
            val radiusMeters = radiusKm * 1000f
            val withDist = raw.map { poi ->
                poi to distanceBetween(currentLat, currentLon, poi.lat, poi.lon)
            }
            log("🔍 [TMapApiClient.searchPOI] 거리 계산 결과 (radius=${radiusMeters}m):")
            withDist.forEach { (poi, dist) ->
                val inRange = if (dist <= radiusMeters) "✅" else "❌"
                log("    $inRange ${poi.name} = ${dist.toInt()}m")
            }

            val filtered = withDist
                .filter { (_, dist) -> dist <= radiusMeters }
                .sortedBy { (_, dist) -> dist }
                .take(maxResults)
                .map { (poi, _) -> poi }
                .toList()

            log("🔍 [TMapApiClient.searchPOI] 최종 반환 ${filtered.size}개 " +
                    "(raw ${raw.size}개 중 radius ${radiusKm}km 통과)")
            filtered
        }.getOrElse { e ->
            log("🔴 [TMapApiClient.searchPOI] 예외 발생 — ${e::class.simpleName}: ${e.message}")
            if (verboseLogging) e.printStackTrace()
            lastError = mapException(e)
            emptyList()
        }
    }

    /**
     * 역지오코딩 (좌표 → 주소 문자열). 실패 시 null (부가 기능이라 조용히 실패).
     * 상세 필드가 필요하면 [reverseGeocodeDetail].
     */
    suspend fun reverseGeocode(lat: Double, lon: Double): String? =
        reverseGeocodeDetail(lat, lon)?.fullAddress

    /**
     * 역지오코딩 상세 (도로명·건물번호·건물명·법정동·지번).
     * 기능1 "현재 위치는 필동로1길 30, OO빌딩 근처입니다" 용. 실패 시 null.
     */
    suspend fun reverseGeocodeDetail(lat: Double, lon: Double): AddressInfo? = runCatching {
        val response: HttpResponse = httpClient.get("$baseUrl/geo/reversegeocoding") {
            parameter("version", 1)
            parameter("lat", lat)
            parameter("lon", lon)
            parameter("coordType", "WGS84GEO")
            parameter("addressType", "A10")
            headers { append("appKey", appKey) }
        }
        if (!response.status.isSuccess()) {
            log("🔴 [TMapApiClient.reverseGeocode] HTTP ${response.status.value}")
            return@runCatching null
        }
        parseReverseGeocode(response.bodyAsText())
    }.getOrElse { e ->
        // 취소는 오류가 아니다 — 삼키면 화면을 떠난 뒤에도 "실패" 로그가 남고, 호출자의 취소 흐름이 끊긴다.
        if (e is CancellationException) throw e
        log("🔴 [TMapApiClient.reverseGeocode] 예외 — ${e::class.simpleName}: ${e.message}")
        null
    }

    /**
     * 내 위치 기준 **거리순** 키워드 검색 (기능1 주변 장소).
     *
     * 통합검색(/pois)에 `searchtypCd=R`(거리순) + 중심 좌표 + 반경을 주면, 결과가 중심에서
     * 가까운 순으로 온다. 카테고리 하나("편의점")를 키워드로 넣어 그 업종의 가장 가까운 곳들을 받는다.
     *
     * 왜 '주변 카테고리 검색(/pois/search/around)' 이 아닌가:
     *   여러 카테고리를 한 번에 넣으면 하나가 0건일 때 나머지까지 안 나온다는 보고가 있고,
     *   파라미터 단위(반경 km/m)가 문서로 확인되지 않았다. 통합검색은 목적지 검색에서 이미 쓰고 있어
     *   동작이 확인된 경로다.
     *
     * ⚠️ 첫 실기 확인 항목: 로그의 `[nearby] keyword=... n=...` 가 0 만 나오면
     *    searchtypCd/radius 조합을 점검할 것.
     *
     * @param radiusKm TMap 반경(km, 정수). 1 = 1km. 실제 표시 반경(10~50m)은 클라이언트가 거른다.
     * @return 실패하면 null (빈 리스트 = 성공했지만 0건). 둘을 구분해야 "인터넷 확인" 안내를 할 수 있다.
     */
    suspend fun searchNearbyByKeyword(
        keyword: String,
        lat: Double,
        lon: Double,
        radiusKm: Int = 1,
        count: Int = 10,
    ): List<POIResult>? = runCatching {
        val response: HttpResponse = httpClient.get("$baseUrl/pois") {
            parameter("version", 1)
            parameter("searchKeyword", keyword)
            parameter("searchtypCd", "R")          // R = 거리순
            parameter("centerLat", lat)
            parameter("centerLon", lon)
            parameter("radius", radiusKm)
            parameter("count", count)
            parameter("reqCoordType", "WGS84GEO")
            parameter("resCoordType", "WGS84GEO")
            headers { append("appKey", appKey) }
        }
        if (!response.status.isSuccess()) {
            // 204(결과 없음)도 isSuccess 에 들어가므로 여기는 진짜 오류만 온다.
            log("🔴 [nearby] keyword=$keyword HTTP ${response.status.value}")
            return@runCatching null
        }
        val body = response.bodyAsText()
        val pois = when {
            // 결과 0건이면 TMap 은 본문 없이 204 를 주기도 한다 → 빈 리스트(성공).
            body.isBlank() -> emptyList()
            // 200 인데 POI 응답이 아님 — 지하철 와이파이 로그인 화면(HTML)이나 오류 JSON.
            // 예전에는 파싱 실패를 "0건"으로 처리해서 "찾은 장소가 없습니다"라는 거짓 안내가 나갔다.
            // 실패(null)로 돌려 "불러오지 못했습니다"로 가게 한다.
            !looksLikePoiResponse(body) -> {
                log("🔴 [nearby] keyword=$keyword POI 응답 아님 — ${body.take(120)}")
                return@runCatching null
            }
            else -> parsePOIResults(body)
        }
        log("🔍 [nearby] keyword=$keyword n=${pois.size}")
        pois
    }.getOrElse { e ->
        if (e is CancellationException) throw e
        log("🔴 [nearby] keyword=$keyword 예외 — ${e::class.simpleName}: ${e.message}")
        lastError = mapException(e)
        null
    }

    private fun looksLikePoiResponse(body: String): Boolean =
        body.trimStart().startsWith("{") && body.contains("\"searchPoiInfo\"")

    // ========== JSON 파싱 ==========

    /**
     * TMap 보행자 경로 파서 (single-pass accumulator).
     *
     * 핵심 아이디어: LineString 1개 = Segment 1개 매핑이 아니라,
     *   "Point → Point 사이의 모든 LineString" 을 하나의 RouteSegment 로 병합.
     *   (TMap 응답은 Point/LineString 교대 패턴이 아닌 경우가 있어 ─
     *    Point→LineString→LineString→Point 처럼 LineString 이 연속될 수 있음.)
     *
     * 흐름:
     *   - LineString 만나면 pendingLines 버퍼에 누적
     *   - 다음 Point 만나면 waypoint 추가 후 pendingLines flush → 단일 segment 생성
     *   - 마지막 trailing LineString 은 buggy 응답이므로 폐기 + 경고
     */
    private fun parsePedestrianRoute(text: String): TMapRoute? = runCatching {
        // 🔧 [DEBUG-TMAP-RAW] LineString properties 검증용 임시 로그.
        // 확인 끝나면 이 블록 통째로 삭제할 것.
        log("🔧 [TMap raw len=${text.length}] BEGIN")
        text.chunked(800).forEachIndexed { i, chunk ->
            log("🔧 [TMap raw #$i] $chunk")
        }
        log("🔧 [TMap raw] END")

        val root = json.parseToJsonElement(text).jsonObject
        val features = root["features"]?.jsonArray ?: return@runCatching null

        var totalDistance = 0
        var totalTime = 0
        val waypoints = mutableListOf<Waypoint>()
        val routePoints = mutableListOf<LatLng>()
        val segments = mutableListOf<RouteSegment>()

        // 다음 Point 가 등장하면 단일 segment 로 병합되는 누적 버퍼.
        val pendingLines = mutableListOf<RawLine>()

        for (feature in features) {
            val obj = feature.jsonObject
            val geometry = obj["geometry"]?.jsonObject ?: continue
            val properties = obj["properties"]?.jsonObject ?: continue
            val geometryType = properties["geometryType"]?.string()
                ?: geometry["type"]?.string()
                ?: continue

            // 첫 번째 feature에서 전체 거리/시간 추출
            if (totalDistance == 0) {
                totalDistance = properties.int("totalDistance") ?: 0
                totalTime = properties.int("totalTime") ?: 0
            }

            // Point = 안내 포인트 (교차로, 횡단보도 등)
            if (geometryType == "Point") {
                val coords = geometry["coordinates"]?.jsonArray ?: continue
                val lon = (coords[0] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: continue
                val lat = (coords[1] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: continue

                val turnType = properties.int("turnType") ?: 0
                val description = properties.string("description") ?: ""
                val distance = properties.int("totalDistance") ?: 0

                waypoints.add(
                    Waypoint(
                        lat = lat,
                        lon = lon,
                        turnType = turnType,
                        description = description,
                        distance = distance,
                        roadType = 0,  // Point 엔 roadType 없음. RouteSegment 에서 조회할 것.
                        pointType = classifyPointType(turnType, description)
                    )
                )
                val newWaypointIdx = waypoints.size - 1

                // pendingLines flush — 이전 waypoint(newWaypointIdx-1) → 방금 추가한 waypoint(newWaypointIdx) 구간.
                if (pendingLines.isNotEmpty()) {
                    if (newWaypointIdx >= 1) {
                        segments.add(
                            mergeIntoSegment(
                                fromIdx = newWaypointIdx - 1,
                                toIdx = newWaypointIdx,
                                lines = pendingLines
                            )
                        )
                    } else {
                        // 첫 Point 보다 먼저 등장한 LineString — buggy 응답. 폐기.
                        log("⚠️ [TMap parse] 첫 Point 이전 LineString ${pendingLines.size}개 폐기")
                    }
                    pendingLines.clear()
                }
            }

            // LineString = 경로 선분 (지도 폴리라인 + 도로 속성)
            // segment 는 즉시 생성하지 않고 pendingLines 에 누적한다.
            if (geometryType == "LineString") {
                val coords = geometry["coordinates"]?.jsonArray ?: continue
                val segPoints = mutableListOf<LatLng>()
                for (coord in coords) {
                    val pair = coord.jsonArray
                    val lon = (pair[0] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: continue
                    val lat = (pair[1] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: continue
                    val pt = LatLng(lat, lon)
                    segPoints.add(pt)
                    routePoints.add(pt)  // 폴리라인 그리기용 — 기존 동작 유지
                }

                // LineString properties 추출
                //   facilityType 은 응답에서 문자열로 옴 (예: "11", "15", "17") → 정수 변환
                val roadType = properties.int("roadType") ?: 0
                val facilityType = properties.string("facilityType")?.toIntOrNull()
                    ?: properties.int("facilityType") ?: -1
                val segDistance = properties.int("distance") ?: 0
                val segTime = properties.int("time") ?: 0
                val name = properties.string("name") ?: ""

                // 도로명만 사용. description 으로 fallback 하면 ", 84m" / ", 3m" 같은
                // TMap 더미 텍스트가 name 으로 들어와 TTS 가 어색해지므로 폐기.
                pendingLines.add(
                    RawLine(
                        distance = segDistance,
                        time = segTime,
                        roadType = roadType,
                        facilityType = facilityType,
                        name = name,
                        coords = segPoints.toList()
                    )
                )
            }
        }

        // 마지막 Point 뒤에 trailing LineString 이 남아있으면 buggy 응답 — 폐기.
        if (pendingLines.isNotEmpty()) {
            log("⚠️ [TMap parse] 마지막 Point 이후 trailing LineString ${pendingLines.size}개 폐기")
            pendingLines.clear()
        }

        // 첫/끝 segment 라벨 통일.
        //   - 첫 segment: TMap 이 "보행자도로" 등으로 줘도 사용자 입장에선 "출발지에서 시작" 이 자연스러움.
        //   - 마지막 segment: 도착 직전 구간은 "도착지 방향" 으로 안내.
        val labeledSegments = segments.toMutableList()
        if (labeledSegments.isNotEmpty()) {
            labeledSegments[0] = labeledSegments[0].copy(name = "출발지")
            val lastIdx = labeledSegments.lastIndex
            labeledSegments[lastIdx] = labeledSegments[lastIdx].copy(name = "도착지")
        }

        TMapRoute(
            totalDistance = totalDistance,
            totalTime = totalTime,
            waypoints = waypoints,
            routePoints = routePoints,
            segments = labeledSegments,
        )
    }.getOrNull()

    // ========== Segment 병합 헬퍼 ==========

    /** Point 사이에 누적된 LineString 1개 분. */
    private data class RawLine(
        val distance: Int,
        val time: Int,
        val roadType: Int,
        val facilityType: Int,
        val name: String,
        val coords: List<LatLng>
    )

    /**
     * 누적된 LineString 들을 단일 RouteSegment 로 병합.
     *
     *   - distance/time: 합산
     *   - 대표 sub-line: 거리 기준 최장 (짧은 connector 가 대표가 되는 것 방지)
     *   - name/roadType/facilityType: 대표의 값 사용
     *   - 폴리라인: 첫 line 통째 + 후속 line 의 시작점 중복 제거하며 concat
     *   - riskLevel: sub-line 별로 계산 후 가장 위험한 것 채택 (보수적)
     */
    private fun mergeIntoSegment(
        fromIdx: Int,
        toIdx: Int,
        lines: List<RawLine>
    ): RouteSegment {
        val totalDistance = lines.sumOf { it.distance }
        val totalTime = lines.sumOf { it.time }
        val representative = lines.maxByOrNull { it.distance } ?: lines.first()

        // 폴리라인 좌표: 인접 sub-line 의 시작점 중복 제거.
        val mergedPoints = mutableListOf<LatLng>()
        lines.forEachIndexed { i, line ->
            if (i == 0) mergedPoints.addAll(line.coords)
            else mergedPoints.addAll(line.coords.drop(1))
        }

        // 위험도(RiskScoreCalculator)는 이번 iOS MVP에서 소비되지 않아 복원하지 않음.
        // RouteSegment.riskLevel 은 기본값(NORMAL)으로 채운다.
        val mergedRisk = RiskLevel.NORMAL

        // 머지가 실제 발생한 경우만 디버그 로그 (sub-line 1 개면 노이즈 방지로 생략).
        if (lines.size > 1) {
            val sub = lines.joinToString(" + ") {
                "${it.name.ifBlank { "?" }}(${it.distance}m,road=${it.roadType})"
            }
            log("🔀 [Segment merge] wp[$fromIdx→$toIdx] ${lines.size}개 → $sub" +
                    " ⇒ 대표='${representative.name}' 총=${totalDistance}m")
        }

        return RouteSegment(
            fromWaypointIndex = fromIdx,
            toWaypointIndex = toIdx,
            distance = totalDistance,
            time = totalTime,
            roadType = representative.roadType,
            facilityType = representative.facilityType,
            name = representative.name,
            points = mergedPoints,
            riskLevel = mergedRisk
        )
    }

    private fun parsePOIResults(text: String): List<POIResult> {
        // try/catch 로 명시적 처리 — runCatching.getOrElse 로 에러를 삼키지 않고
        // 어느 단계에서 실패했는지 콘솔에 그대로 노출한다.
        return try {
            val root = json.parseToJsonElement(text).jsonObject
            log("🟢 [parsePOIResults] 1) root 파싱 OK — keys=${root.keys}")

            val searchPoiInfo = root["searchPoiInfo"]?.jsonObject
            if (searchPoiInfo == null) {
                log("🔴 [parsePOIResults] 2) 'searchPoiInfo' 키 없음 — root.keys=${root.keys}")
                return emptyList()
            }
            log("🟢 [parsePOIResults] 2) searchPoiInfo OK — keys=${searchPoiInfo.keys}")

            val poisObj = searchPoiInfo["pois"]?.jsonObject
            if (poisObj == null) {
                log("🔴 [parsePOIResults] 3) 'pois' 키 없음 — searchPoiInfo.keys=${searchPoiInfo.keys}")
                return emptyList()
            }
            log("🟢 [parsePOIResults] 3) pois OK — keys=${poisObj.keys}")

            val poiArray = poisObj["poi"]?.jsonArray
            if (poiArray == null) {
                log("🔴 [parsePOIResults] 4) 'poi' 배열 없음 — pois.keys=${poisObj.keys}")
                return emptyList()
            }
            log("🟢 [parsePOIResults] 4) poi 배열 OK — size=${poiArray.size}")

            val results = mutableListOf<POIResult>()
            for ((idx, poiElement) in poiArray.withIndex()) {
                val obj = poiElement.jsonObject
                val name = obj.string("name")
                if (name == null) {
                    log("⚠️ [parsePOIResults] poi[$idx] name 누락 — keys=${obj.keys}")
                    continue
                }

                // POI 실좌표(lat/lon) 우선, 없으면 자동차용 매핑좌표(noorLat/noorLon) fallback
                val rawLat = obj.string("lat")?.toDoubleOrNull()
                val rawLon = obj.string("lon")?.toDoubleOrNull()
                val noorLat = obj.string("noorLat")?.toDoubleOrNull()
                val noorLon = obj.string("noorLon")?.toDoubleOrNull()

                val lat = rawLat ?: noorLat
                val lon = rawLon ?: noorLon
                if (lat == null || lon == null) {
                    log("⚠️ [parsePOIResults] poi[$idx] '$name' 좌표 누락 — " +
                            "lat=$rawLat lon=$rawLon noorLat=$noorLat noorLon=$noorLon")
                    continue
                }

                val address = obj.string("upperAddrName") ?: ""
                // 입구 좌표가 없을 때 TMap 이 "0.0" 같은 값을 줄 수 있다 — 적도 좌표로 오인하지 않게 거른다.
                val frontLat = obj.string("frontLat")?.toDoubleOrNull()?.takeIf { it != 0.0 }
                val frontLon = obj.string("frontLon")?.toDoubleOrNull()?.takeIf { it != 0.0 }
                val (fLat, fLon) = if (frontLat != null && frontLon != null) frontLat to frontLon else null to null
                // 업종: 소분류 → 중분류 순. (예: lowerBizName="편의점")
                val category = obj.string("lowerBizName")?.takeIf { it.isNotBlank() }
                    ?: obj.string("middleBizName")?.takeIf { it.isNotBlank() }

                results.add(POIResult(name, lat, lon, address, fLat, fLon, category))
            }
            log("🟢 [parsePOIResults] 5) 변환 완료 — ${results.size}/${poiArray.size}개")
            results
        } catch (e: Throwable) {
            log("🔴 [parsePOIResults] 디코딩 예외 — ${e::class.simpleName}: ${e.message}")
            log("🔴 [parsePOIResults] 응답 본문 preview=${text.take(500)}")
            if (verboseLogging) e.printStackTrace()
            emptyList()
        }
    }

    private fun parseReverseGeocode(text: String): AddressInfo? = runCatching {
        val info = json.parseToJsonElement(text).jsonObject
            .get("addressInfo")?.jsonObject ?: return@runCatching null
        AddressInfo(
            fullAddress = info.string("fullAddress"),
            roadName = info.string("roadName"),
            buildingIndex = info.string("buildingIndex"),
            buildingName = info.string("buildingName"),
            legalDong = info.string("legalDong"),
            bunji = info.string("bunji"),
        )
    }.getOrNull()

    /**
     * TMap turnType 코드를 SafeWalk 포인트 유형으로 분류.
     *
     * ⚠️ 분류 우선순위 (CROSSWALK 우선 — 안전 critical):
     *   1. DESTINATION (turnType 200) — 목적지가 가장 우선
     *   2. CROSSWALK — turnType 코드(211~217) OR description 에 "횡단보도" 포함
     *      (TMap 이 "우회전 후 횡단보도" 같은 케이스에서 turnType=2 + description="횡단보도"
     *       로 줄 수 있는데, 이전 로직은 TURN 으로 잘못 분류해서 횡단보도 zone 감지가 실패했음)
     *   3. TURN — 순수 방향 전환만
     *   4. STAIRS — 계단
     *   5. WAYPOINT — 그 외
     */
    private fun classifyPointType(turnType: Int, description: String): String = when {
        turnType == 200 -> "DESTINATION"     // 목적지 도착 (최우선)
        turnType in 211..217 -> "CROSSWALK"  // 횡단보도 전용 코드 (모든 방향)
        description.contains("횡단보도") -> "CROSSWALK"  // turnType 이 TURN 이어도 description 에 횡단보도 있으면 CROSSWALK
        turnType in 1..8 -> "TURN"           // 방향 전환 (CROSSWALK 가 아닌 순수 회전)
        description.contains("계단") -> "STAIRS"
        else -> "WAYPOINT"
    }

    /** Ktor/네트워크 예외를 사용자용 메시지로 변환. OS 무관 메시지만 사용. */
    private fun mapException(e: Throwable): String {
        val msg = e.message ?: ""
        return when {
            // 기존 java.net.UnknownHostException 대체
            msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("UnresolvedAddress", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true) ->
                "인터넷 연결을 확인하세요"
            // 기존 java.net.SocketTimeoutException 대체
            msg.contains("timeout", ignoreCase = true) ->
                "서버 응답이 없습니다. 다시 시도해주세요"
            else -> "오류가 발생했습니다. 다시 시도해주세요"
        }
    }
}

// ========== JSON 헬퍼 (kotlinx.serialization 트리 탐색용) ==========
// Gson `obj.get("key").asString` 과 비슷한 사용감을 위한 확장.

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

private fun JsonElement.string(): String? =
    (this as? JsonPrimitive)?.contentOrNull
