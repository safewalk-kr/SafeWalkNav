import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release APK 서명 설정 — keystore.properties (gitignored) 에서 로드.
// 파일 없으면 release 빌드 시 서명 생략 (unsigned APK 산출).
// keystore.properties 양식:
//   storeFile=safewalknav-release.keystore
//   storePassword=...
//   keyAlias=safewalknav
//   keyPassword=...
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile")?.isNotBlank() == true

// API 키 — local.properties (gitignored) 에서 읽는다. 저장소에는 절대 올리지 않는다.
//   TMAP_APP_KEY=...   ← 내 위치·주변 장소(역지오코딩·주변 검색)에 사용
// 키가 없으면 빈 문자열로 빌드되고, 앱은 "설정 문제"를 음성으로 알린 뒤 그 기능만 막는다.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val tmapAppKey: String = localProperties.getProperty("TMAP_APP_KEY", "").trim()

android {
    // namespace(코드 패키지)는 그대로 두고, 스토어 식별자(applicationId)만 바꾼다.
    // 둘은 독립이라 소스 파일의 package 선언을 건드릴 필요가 없다.
    namespace = "com.example.safewalknav"
    // targetSdk 36: Google Play 신규 앱 요건(2026-08-31~).
    compileSdk = 36

    defaultConfig {
        // ⚠️ Play 는 com.example.* 을 받지 않고, 첫 업로드 이후에는 영구히 못 바꾼다.
        //    iOS 번들 ID(com.safewalknav.app)와 맞췄다.
        applicationId = "com.safewalknav.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "TMAP_APP_KEY", "\"$tmapAppKey\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        // viewBinding 제거 (2026-06-15 정리) — *Binding 클래스 사용처 0건, 전부 findViewById.
        buildConfig = true
        // mlModelBinding 제거 (2026-10) — ml/ 폴더 모델이 없어 생성 코드 0건. 모델은 assets 에서 직접 로드.
    }
}

// Kotlin 2.x: android { kotlinOptions{} } 는 폐기 → kotlin { compilerOptions{} }
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

dependencies {
    // KMM 공통 모듈 (Kotlin Multiplatform shared)
    implementation(project(":shared"))

    // Firebase 제거 (2026-07) — 카메라-only 전환 후 미사용. 텔레메트리는 향후 자체 백엔드로.

    // Android Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 위치 — 기능1(내 위치·주변 장소). Fused provider 가 GPS·Wi-Fi·기지국을 합쳐
    // 도심에서 순수 GPS 보다 오차가 작다. (TMap REST 호출은 shared 의 Ktor 가 담당)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // ===== ML / 카메라 =====

    // CameraX — 신호등 추론용 카메라 스트림.
    // 1.3.1 의 libimage_processing_util_jni.so 가 4KB 정렬이라 Play 16KB 요건에 걸렸다 (readelf 확인).
    // 1.6.x 는 내부 카메라 스택을 CameraPipe 로 통째로 바꿨으므로, 검증된 동작을 지키려고
    // Camera2 기반 마지막 계열인 1.5.x 를 쓴다.
    val cameraxVersion = "1.5.3"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // LiteRT (구 TensorFlow Lite) — 신호등 추론.
    // org.tensorflow:tensorflow-lite:2.14.0 의 libtensorflowlite_jni.so 가 4KB 정렬이라 교체.
    // LiteRT 1.x 는 패키지명(org.tensorflow.lite.*)과 Interpreter API 가 그대로라 코드 변경이 없다.
    // ⚠️ tensorflow-lite-support 는 옛 tensorflow-lite 를 끌고 들어와 4KB .so 가 다시 섞이므로 제거했다.
    //    (유일 사용처였던 FileUtil.loadMappedFile 은 TrafficLightDetector 안에서 직접 구현)
    implementation("com.google.ai.edge.litert:litert:1.4.0")

    // OpenCV 제거 (2026-06-15 정리) — 유일 사용처였던 MLCameraProbe(의존성 검증용 임시 파일) 삭제됨.
    // Room·proj4j 제거 (2026-07 정리) — 신호 공공데이터 API 폐기로 신호등 위치 로컬 캐시와
    // EPSG:5186→WGS84 좌표 변환(신호등 위치 전용)이 함께 사라짐.
}
