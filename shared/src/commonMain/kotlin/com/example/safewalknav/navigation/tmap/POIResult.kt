package com.example.safewalknav.navigation.tmap

/**
 * POI(Point of Interest) 검색 결과.
 * TMap REST API의 /tmap/pois 응답을 파싱한 항목 하나에 해당.
 *
 * frontLat / frontLon — 건물 입구 좌표(있을 때만). 도착 판정 정확도를 높이기 위해
 * POI 본체 좌표와 함께 더 가까운 쪽을 사용.
 *
 * category — TMap 업종 분류(소분류 우선, 없으면 중분류). 응답에 없으면 null.
 *   (2026-10 추가. 기본값이 있어 기존 호출부는 그대로 컴파일된다.)
 *
 * KMM commonMain — Android/iOS 공통.
 */
data class POIResult(
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String,
    val frontLat: Double? = null,
    val frontLon: Double? = null,
    val category: String? = null,
)

/**
 * 역지오코딩 결과 (TMap reversegeocoding, addressType=A10).
 * 응답 필드가 비어 올 수 있어 전부 nullable.
 */
data class AddressInfo(
    val fullAddress: String?,
    val roadName: String?,
    val buildingIndex: String?,
    val buildingName: String?,
    val legalDong: String?,
    val bunji: String?,
)
