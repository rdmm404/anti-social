plugins {
    id("com.android.library")
}

android {
    namespace = "org.voltr.antisocial.playback"
    compileSdk = 37

    defaultConfig {
        minSdk = 25
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
    api(project(":core"))
    api(libs.media3.exoplayer)
    implementation(libs.media3.dash)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
