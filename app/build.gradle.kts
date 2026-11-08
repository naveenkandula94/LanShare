
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.lanshare"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.lanshare"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
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
    implementation("io.github.webrtc-sdk:android:144.7559.15")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
