plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.openhealth.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.openhealth.android"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/uniffi"))
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests; use the real implementation there.
    testImplementation("org.json:json:20240303")
}

tasks.configureEach {
    if (name.startsWith("compile") && name.endsWith("Kotlin")) {
        doFirst {
            val generated = layout.buildDirectory.dir("generated").get().asFile
            check(generated.resolve("jniLibs/arm64-v8a/liboura_core.so").exists() &&
                generated.resolve("uniffi/uniffi/oura_core/oura_core.kt").exists()) {
                "Rust core or Kotlin bindings missing. Run .\\build-core.ps1 before building the Android app."
            }
        }
    }
}
