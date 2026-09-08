plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    jacoco
    application
}

application {
    mainClass.set("com.shadowrun.matrix.MainKt")
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
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
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

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
    // Pre-existing findings are grandfathered via the baseline; new findings fail the build.
    baseline = file("config/detekt/baseline.xml")
    // Lint main + test sources; the generated frontend copy is not Kotlin.
    source.setFrom(files("src/main/kotlin", "src/test/kotlin"))
}

tasks.register<Exec>("npmInstall") {
    workingDir = file("frontend")
    commandLine("cmd", "/c", "npm install")
    inputs.files("frontend/package.json", "frontend/package-lock.json")
    outputs.dir("frontend/node_modules")
    outputs.file("frontend/node_modules/.package-lock.json")
}

tasks.register<Exec>("buildFrontend") {
    workingDir = file("frontend")
    commandLine("cmd", "/c", "npm run build")
    dependsOn("npmInstall")
    inputs.dir("frontend/src")
    inputs.files(
        "frontend/index.html",
        "frontend/tsconfig.json",
        "frontend/tsconfig.node.json",
        "frontend/vite.config.ts"
    )
    outputs.dir("frontend/dist")
}

tasks.register<Copy>("copyFrontendBuild") {
    from("frontend/dist")
    into(layout.buildDirectory.dir("resources/main/static"))
    dependsOn("buildFrontend")
}

tasks.named("classes") {
    dependsOn("copyFrontendBuild")
}
