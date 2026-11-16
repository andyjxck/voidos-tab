plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.andysocial.voidos"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.andysocial.voidos"
        minSdk = 29
        targetSdk = 29
        versionCode = 1
        versionName = "1.0.0"
        // Only the languages we ship, keeps the APK small for a 2 GB tablet
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        // Fixed key committed on purpose: every build (local or GitHub) has the
        // same signature, so `adb install -r -t` can update VoidOS while it is
        // device owner. Never use this key for a store release.
        getByName("debug") {
            storeFile = file("voidos-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // targetSdk 29 is deliberate (the tablet runs Android 10 and is never
        // published to Play), so don't fail builds over it.
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi")
        abortOnError = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.core:core-ktx:1.13.1")
}
