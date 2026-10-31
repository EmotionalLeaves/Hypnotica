import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Release signing.
 *
 * The repo ships a public keystore (keystore/hypnotica-release.jks, password "hypnotica")
 * so that a build straight out of CI installs and updates on a phone with no setup.
 * It is NOT secret and must never be used for a Play Store listing.
 *
 * To use your own key instead, set these repository secrets and the workflow will
 * export them as environment variables:
 *   KEYSTORE_B64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
 */
val localKeystore: File = rootProject.file("keystore/hypnotica-release.jks")
val envKeystorePath: String? = System.getenv("HYPNOTICA_KEYSTORE")
val signingStoreFile: File = if (!envKeystorePath.isNullOrBlank()) file(envKeystorePath) else localKeystore
val signingStorePassword: String = System.getenv("HYPNOTICA_KEYSTORE_PASSWORD") ?: "hypnotica"
val signingKeyAlias: String = System.getenv("HYPNOTICA_KEY_ALIAS") ?: "hypnotica"
val signingKeyPassword: String = System.getenv("HYPNOTICA_KEY_PASSWORD") ?: "hypnotica"

android {
    namespace = "app.hypnotica"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.hypnotica"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("HYPNOTICA_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("HYPNOTICA_VERSION_NAME") ?: "0.1.0"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            if (signingStoreFile.exists()) {
                storeFile = signingStoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            // Kept off on purpose: the renderer and the Lua bridge both reach code
            // reflectively, and a stripped build is not worth the install-time risk
            // for a sideloaded personal app.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingStoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + "-opt-in=kotlin.RequiresOptIn"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric reads the app's resources for the screen pictures.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // The smoke test runs 400,000 particles and a 1024-square grid.
            it.maxHeapSize = "2g"
            it.testLogging { showStandardStreams = true }
            // The screen pictures run only when asked for (-Pscreens), in their
            // own CI job: they are slow and they are for looking at, not a gate.
            if (project.hasProperty("screens")) {
                it.filter.includeTestsMatching("app.hypnotica.screens.*")
                it.systemProperty("roborazzi.test.record", "true")
                // -Psims=id,id draws only those in SimPicturesTest.
                (project.findProperty("sims") as String?)?.let { ids -> it.systemProperty("sims", ids) }
                (project.findProperty("moments") as String?)?.let { m -> it.systemProperty("moments", m) }
                (project.findProperty("params") as String?)?.let { v -> it.systemProperty("params", v) }
            } else {
                it.exclude("app/hypnotica/screens/**")
            }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")

    // Recipe scripting. Pure Java, no NDK.
    implementation("org.luaj:luaj-jse:3.0.1")

    // Connecting a YouTube channel (Settings → YouTube): Google sign-in and
    // the access token, from Play services. The upload itself is plain HTTPS.
    implementation("com.google.android.gms:play-services-auth:21.3.0")

    testImplementation("junit:junit:4.13.2")
    // Screen pictures (see ScreensTest): Compose rendered on the JVM.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
