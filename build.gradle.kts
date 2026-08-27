plugins {
    kotlin("jvm") version "2.4.10"
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

group = "com.peterle95.watchnotetaker"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
