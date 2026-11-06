import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    // 버전은 루트 build.gradle.kts 에서 관리 (Kotlin 버전과 반드시 같아야 한다)
    kotlin("plugin.serialization")
}

// 2026-08: iOS MVP용 TMap 보행자 경로 복원 → Ktor/serialization/coroutines 재도입.
// commonMain(Ktor core+json) / androidMain(okhttp) / iosMain(darwin) 엔진 분리.
// 2026-10: Kotlin 2.2.21 / compileSdk 36 로 업그레이드. 라이브러리 버전은 그대로 둔다
//          (Android 쪽 변경을 최소화하기 위함 — iOS 빌드는 Mac 에서 별도 확인 필요).

val ktorVersion = "2.3.7"
val kotlinxSerializationVersion = "1.6.2"
val coroutinesVersion = "1.7.3"

val isRunningOnMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

kotlin {
    androidTarget {
        // Kotlin 2.x: kotlinOptions{} 는 폐기 → compilerOptions{}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    // iOS 타겟은 macOS에서만 빌드 가능 — Windows/Linux에서는 등록 자체를 건너뜀
    if (isRunningOnMac) {
        listOf(
            iosX64(),
            iosArm64(),
            iosSimulatorArm64()
        ).forEach { iosTarget ->
            iosTarget.binaries.framework {
                baseName = "shared"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
            implementation("io.ktor:ktor-client-core:$ktorVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
            implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
            implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
        }
        if (isRunningOnMac) {
            iosMain.dependencies {
                implementation("io.ktor:ktor-client-darwin:$ktorVersion")
            }
        }
    }
}

android {
    namespace = "com.example.safewalknav.shared"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
