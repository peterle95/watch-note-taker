plugins {
    kotlin("jvm") version "2.4.10"
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
