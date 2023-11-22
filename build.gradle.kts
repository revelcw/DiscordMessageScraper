plugins {
    kotlin("jvm") version "1.9.0"
    kotlin("plugin.serialization") version "1.5.31"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    // kotlin serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.0")

//    implementation("org.mongodb:mongodb-driver-kotlin-coroutine:4.11.0")

    implementation("org.litote.kmongo:kmongo-coroutine:4.11.0")

    implementation("net.dv8tion:JDA:5.0.0-beta.18")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(8)
}

application {
    mainClass.set("MainKt")
}