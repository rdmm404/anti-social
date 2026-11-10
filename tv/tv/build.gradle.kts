plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Point a build at another server with -PantiSocialServer=http://10.0.2.2:8080
val server = providers.gradleProperty("antiSocialServer")
    .getOrElse("https://api.yt.nautilus.voltr.org")
    .trimEnd('/')
// Debug builds can carry a device token so the emulator skips signing in: -PantiSocialToken=...
// Release builds never do, because the APK is published.
val debugToken = providers.gradleProperty("antiSocialToken").getOrElse("")

android {
    namespace = "org.voltr.antisocial.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.voltr.antisocial.tv"
        minSdk = 25 // Fire OS 6, the oldest Android-based Fire TV software still around.
        targetSdk = 36
        // CI passes these, and versionCode has to rise with every release or Android refuses the update.
        versionCode = providers.gradleProperty("versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("versionName").getOrElse("dev")
        buildConfigField("String", "SERVER_URL", "\"$server\"")
        manifestPlaceholders["cleartext"] = server.startsWith("http://")
        buildConfigField("String", "DEBUG_TOKEN", "\"\"")
    }

    signingConfigs {
        // Set by the release workflow. Every release has to carry the same key, or the TV refuses it.
        val keystore = System.getenv("ANTI_SOCIAL_KEYSTORE")
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("ANTI_SOCIAL_KEYSTORE_PASSWORD")
                keyAlias = "anti-social"
                keyPassword = System.getenv("ANTI_SOCIAL_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEBUG_TOKEN", "\"$debugToken\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without that keystore (a build on this machine), the debug key is enough. Android
            // refuses an update signed with another key, so uninstall first when switching.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses java.time and Java 10+ APIs (URLEncoder with a Charset) that
        // Fire OS lacks. The nio flavour of the desugaring library supplies them.
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":youtube"))
    implementation(project(":playback"))

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation(libs.media3.ui)
    implementation(libs.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.zxing.core)
    coreLibraryDesugaring(libs.desugar)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
