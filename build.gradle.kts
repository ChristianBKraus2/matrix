plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    jacoco
}

group = "com.shadowrun"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val ktorVersion = "3.1.3"

dependencies {
    implementation("org.yaml:snakeyaml:2.3")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.18")
}

tasks.test {
    useJUnitPlatform()
    exclude("**/integration/**")
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("integrationTest") {
    useJUnitPlatform()
    include("**/integration/**")
    testLogging {
        showStandardStreams = true
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required = true
    }
    executionData.setFrom(
        fileTree(layout.buildDirectory).include("jacoco/*.exec")
    )
}

jacoco {
    toolVersion = "0.8.12"
}

kotlin {
    jvmToolchain(21)
}
