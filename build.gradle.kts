plugins {
    kotlin("jvm") version "2.2.0"
    jacoco
}

group = "com.shadowrun"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.yaml:snakeyaml:2.3")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    testImplementation(kotlin("test"))
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
