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
        versionCode = 6
        versionName = "1.5.0"
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
        // The build to install on the tablet: not debuggable, signed with the
        // same fixed key so it installs over a debug build (and the other way
        // round) without losing device owner. No R8 shrinking: VoidOS is
        // small, and shrinking would need keep rules that are only checkable
        // on the tablet (device admin receiver, notification listener).
        release {
            signingConfig = signingConfigs.getByName("debug")
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
    testOptions {
        unitTests {
            // Robolectric renders the real screens on the JVM (no tablet needed)
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("voidos.shots", rootProject.file("docs/screens").absolutePath)
                it.maxHeapSize = "3g"
                // each test class in its own JVM: the screens share app-wide objects (timers, store)
                it.forkEvery = 1
                it.doFirst { rootProject.file("docs/screens/audit.txt").delete(); file("build/screens-extra/audit.txt").delete() }
            }
        }
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(bom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
