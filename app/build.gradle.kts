plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.example.usbplayer"
    compileSdk = 34
    ndkVersion = "26.1.10909125"
    defaultConfig {
        applicationId = "com.example.usbplayer"
        minSdk = 26; targetSdk = 34
        // versionCode растёт с каждым запуском сборки на GitHub, чтобы новая версия всегда ставилась поверх старой
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run; versionName = "0.3.$run"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // Постоянный ключ подписи (app.p12 создаётся из signing.b64 в workflow): иначе у каждой сборки
    // на GitHub свой случайный debug-ключ и Android не даёт обновить приложение поверх.
    val keystoreFile = file("app.p12")
    signingConfigs {
        if (keystoreFile.exists()) create("fixed") {
            storeFile = keystoreFile; storePassword = "usbplayer"; keyAlias = "usbplayer"; keyPassword = "usbplayer"
        }
    }
    buildTypes {
        getByName("debug") { if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("fixed") }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    val m3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$m3")
    implementation("androidx.media3:media3-session:$m3")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("com.google.guava:guava:33.2.1-android")
}
