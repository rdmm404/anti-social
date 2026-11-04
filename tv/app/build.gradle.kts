plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Point a build at another server with -PantiSocialServer=http://10.0.2.2:8484
val server = providers.gradleProperty("antiSocialServer")
    .getOrElse("https://yt.nautilus.voltr.org")
    .trimEnd('/')

android {
    namespace = "org.voltr.antisocial.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.voltr.antisocial.tv"
        minSdk = 25 // Fire OS 6, the oldest Android-based Fire TV software still around.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "SERVER_URL", "\"$server\"")
        manifestPlaceholders["cleartext"] = server.startsWith("http://")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Sideloaded onto one TV, so the machine's debug key is enough. Keep using the
            // same machine, or uninstall first: Android refuses an update with another key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val media3 = "1.11.1"
    val okhttp = "4.12.0" // Coil 2 is built against OkHttp 4.

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("com.squareup.okhttp3:okhttp:$okhttp")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttp")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
