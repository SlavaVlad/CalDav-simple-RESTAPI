plugins {
    kotlin("jvm") version "2.1.10"
    id("io.ktor.plugin") version "3.2.2"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10"
}

group = "com.nano"
version = "0.0.2"

application {
    mainClass = "com.nano.ApplicationKt"
}

repositories {
    maven("https://jitpack.io")
    mavenCentral()
}

allprojects {
    repositories {
    }
}

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-netty")
    implementation("ch.qos.logback:logback-classic:1.5.13")
    implementation("com.github.bitfireAT:dav4jvm:2.2.1")
    implementation("io.ktor:ktor-client-logging:3.2.2")

    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.1.10")
}
