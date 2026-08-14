plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


kotlin {
    jvmToolchain(21)
}

// GroqClientTest uses the JDK's built-in com.sun.net.httpserver (jdk.httpserver module) to spin
// up a real loopback HTTP server. That module is not part of the default module set the Kotlin
// compiler resolves for classpath-style compilation, so "com.sun.net.httpserver.*" is reported as
// unresolved unless the module is added explicitly. Configuring this on every KotlinCompile task
// (not just the top-level kotlin{} block) makes sure it reaches compileDebugUnitTestKotlin, the
// task that actually fails. This only widens compiler module visibility — no code is changed.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xadd-modules=jdk.httpserver")
    }
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

android { namespace = "com.sa.aidesktop"; compileSdk = 35
    defaultConfig { applicationId = "com.sa.aidesktop"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0"; ndk { abiFilters += "arm64-v8a" } }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // GroqClient uses org.json (already part of the Android platform at runtime — no new
    // production dependency). Local JVM unit tests run against the android.jar stub, whose
    // org.json classes throw "not mocked" by default, so this test-only artifact is added
    // purely so GroqClientTest can exercise real JSON parsing. It never ships in the app.
    testImplementation("org.json:json:20240303")
}
