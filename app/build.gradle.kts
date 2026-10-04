plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.aarav.clearscribe"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.aarav.clearscribe"
        minSdk = 30 // Wear OS 3+ baseline; Clear itself needs API 24+
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Clear, LiteRT, and sherpa-onnx's native libs are only bundled for
        // arm64-v8a and x86_64 (what we actually copied in). A dependency
        // incidentally pulled in armeabi-v7a/x86 versions of ONE unrelated
        // lib (androidx.graphics.path), leaving those ABI folders partially
        // populated. If a device's system picks one of those as its primary
        // install ABI (some Wear OS watches default to 32-bit even on a
        // 64-bit chip, for memory savings), every other native lib fails to
        // load with "library not found" — since Android only searches the
        // one ABI directory it committed to at install time, never merges
        // across them. Restricting abiFilters removes armeabi-v7a/x86 as
        // install options entirely, forcing arm64-v8a (or x86_64 in the
        // emulator) where everything is actually present.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }
}

dependencies {
    // --- Desert Ant Labs: Clear (speech enhancement) ---
    // Confirmed version from the SDK snippet you received; docs page currently shows 3.1.0,
    // so if 3.5.0 fails to resolve, fall back to 3.1.0 and check Maven Central for the latest.
    implementation("ai.desertant:clear:3.5.0")

    // --- Wear OS / Compose ---
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")
    implementation("androidx.compose.material:material:1.6.8")
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    // Ongoing Activity API — surfaces the recording on the watch face, the
    // app launcher's Recents, and (on One UI Watch 8+) the Galaxy Watch Now Bar.
    implementation("androidx.wear:wear-ongoing:1.0.0")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- Local storage (Room) ---
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // --- Testing ---
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
