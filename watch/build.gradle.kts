plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.peterle95.watchnotetaker.watch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.peterle95.watchnotetaker"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation("androidx.compose.material:material:1.7.8")
    implementation("androidx.wear.compose:compose-material:1.4.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    testImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.8")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.8")
}
