plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    jacoco
}

android {
    namespace = "org.jarvis.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.jarvis.android"
        minSdk = 31           // X25519 / Ed25519 / ChaCha20-Poly1305 are all in the platform from API 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // NotificationManagerCompat — used to check whether the user has granted the bank
    // notification listener "Notification access" (Increment E, bank push -> finance draft)
    implementation("androidx.core:core-ktx:1.13.1")

    // Room — offline-first storage of pending sync items + cached server state
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager — schedules sync attempts on connectivity
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Kotlinx serialization for JSON envelope/payload
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // EncryptedSharedPreferences for storing pairing material at rest
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Bouncy Castle lightweight crypto — Ed25519 / X25519 via the org.bouncycastle.crypto
    // lightweight API (NOT JCA provider registration). Required because many real Android
    // OEM JCA providers do not expose KeyPairGenerator.getInstance("Ed25519"/"X25519").
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Health Connect — local-first health data (sleep, steps) read on-device,
    // then queued for E2E sync to the home server. No cloud account needed.
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

    // Tests
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// --- JVM unit-test coverage (JaCoCo) ---
// Measures line coverage of testDebugUnitTest only. No instrumentation/Robolectric —
// Compose screens, Activities, and anything needing a real Android runtime are not
// exercised by this report; see test README notes for the list of skipped classes.
jacoco {
    toolVersion = "0.8.12"
}

tasks.withType<Test>().configureEach {
    configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "Generates a JaCoCo line-coverage report for testDebugUnitTest."

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }

    val fileFilter = listOf(
        "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
        "**/*Test*.*", "android/**/*.*", "**/*\$serializer.class"
    )

    val kotlinClasses = fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
        exclude(fileFilter)
    }
    val javaClasses = fileTree("${layout.buildDirectory.get()}/intermediates/javac/debug/classes") {
        exclude(fileFilter)
    }

    classDirectories.setFrom(files(kotlinClasses, javaClasses))
    sourceDirectories.setFrom(files("${projectDir}/src/main/java", "${projectDir}/src/main/kotlin"))
    executionData.setFrom(fileTree(layout.buildDirectory.get()) {
        include("jacoco/testDebugUnitTest.exec")
    })
}
