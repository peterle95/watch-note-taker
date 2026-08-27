plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val backendUrl = providers.gradleProperty("BACKEND_URL").orElse("").get()
fun secret(name: String) = providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull
val releaseStoreFile = secret("RELEASE_STORE_FILE")
val releaseStorePassword = secret("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = secret("RELEASE_KEY_ALIAS")
val releaseKeyPassword = secret("RELEASE_KEY_PASSWORD")
val releaseSecrets = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
check(releaseSecrets.count { it != null } in setOf(0, 4)) { "Configure all RELEASE_* signing values or none" }
if (gradle.startParameter.taskNames.any { it.endsWith("assembleRelease") || it.endsWith("bundleRelease") }) {
    check(releaseSecrets.all { it != null }) { "Release packaging requires RELEASE_* signing values" }
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BACKEND_URL", "\"${backendUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        manifestPlaceholders["allowCleartext"] = "false"
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        if (releaseSecrets.all { it != null }) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["allowCleartext"] = "true"
            buildConfigField("boolean", "MANUAL_TRANSCRIPTION_ENABLED", "true")
        }
        release {
            buildConfigField("boolean", "MANUAL_TRANSCRIPTION_ENABLED", "false")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
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
