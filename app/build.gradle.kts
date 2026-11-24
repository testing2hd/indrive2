plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.ridecalc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.ridecalc"
        // takeScreenshot() in AccessibilityService needs Android 11 (API 30)
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // On-device OCR, Latin model bundled in the APK (no download, works offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
