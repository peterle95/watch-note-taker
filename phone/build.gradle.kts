plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.peterle95.watchnotetaker.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.peterle95.watchnotetaker"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) } }

dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
}
