plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val backendUrl = providers.gradleProperty("BACKEND_URL").orElse("").get()

android {
    namespace = "com.peterle95.watchnotetaker.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.peterle95.watchnotetaker"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BACKEND_URL", "\"${backendUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        debug {
            buildConfigField("boolean", "MANUAL_TRANSCRIPTION_ENABLED", "true")
        }
        release { buildConfigField("boolean", "MANUAL_TRANSCRIPTION_ENABLED", "false") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) } }

dependencies {
    implementation(project(":"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    testImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.8")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.8")
}
