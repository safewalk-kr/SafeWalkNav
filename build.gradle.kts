// Top-level build file
//
// 2026-10 업그레이드 — Google Play 출시 요건 대응.
//   · targetSdk 36 (2026-08-31 부터 신규 앱 필수) → AGP 8.9.1 이상 필요. 8.11.1 사용.
//   · AGP 8.11 → Gradle 8.13 이상 (gradle-wrapper.properties).
//   · Kotlin 1.9.22 는 AGP 8.1 까지만 공식 지원 → 2.2.21 (AGP 7.3.1~8.11.1 공식 지원).
//   · AGP 9.x 는 KMP 플러그인 구조가 바뀌므로(com.android.kotlin.multiplatform.library) 일부러 피한다.
plugins {
    id("com.android.application") version "8.11.1" apply false
    id("com.android.library") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21" apply false
}
