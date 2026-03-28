import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm")
    application
    `java-test-fixtures`
}

val ktorVersion: String by rootProject.extra
val coroutinesVersion: String by rootProject.extra
val result4kVersion: String by rootProject.extra
val striktVersion: String by rootProject.extra
val uiDirectory = rootProject.layout.projectDirectory.dir("ui")

dependencies {
    implementation(project(":domain"))
    implementation(project(":json"))
    implementation(project(":http"))
    implementation("dev.forkhandles:result4k:$result4kVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:$coroutinesVersion")

    testImplementation(kotlin("test-junit5"))
    testImplementation("io.strikt:strikt-core:$striktVersion")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation(testFixtures(project(":domain")))

    testFixturesImplementation(project(":domain"))
    testFixturesImplementation(project(":json"))
}

sourceSets {
    val main by getting
    named("testFixtures") {
        compileClasspath += main.output
        runtimeClasspath += main.output
    }
}

tasks.named("compileTestFixturesKotlin") {
    dependsOn("compileKotlin")
}

val testExternal by sourceSets.creating {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += output + compileClasspath
}

configurations.named(testExternal.implementationConfigurationName) {
    extendsFrom(configurations.named("testImplementation").get())
}

configurations.named(testExternal.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.named("testRuntimeOnly").get())
}

application {
    mainClass = "transport.MainKt"
}

val installUiDependencies = tasks.register<Exec>("installUiDependencies") {
    workingDir = uiDirectory.asFile
    environment("PATH", System.getenv("PATH").orEmpty())
    commandLine("npm", "ci")
    inputs.file(uiDirectory.file("package.json"))
    inputs.file(uiDirectory.file("package-lock.json"))
    outputs.dir(uiDirectory.dir("node_modules"))
}

val buildUi = tasks.register<Exec>("buildUi") {
    dependsOn(installUiDependencies)
    workingDir = uiDirectory.asFile
    environment("PATH", System.getenv("PATH").orEmpty())
    commandLine("npm", "run", "build")
    inputs.file(uiDirectory.file("index.html"))
    inputs.file(uiDirectory.file("package.json"))
    inputs.file(uiDirectory.file("package-lock.json"))
    inputs.file(uiDirectory.file("vite.config.js"))
    inputs.dir(uiDirectory.dir("src"))
    outputs.dir(uiDirectory.dir("dist"))
}

tasks.processResources {
    dependsOn(buildUi)
    from(uiDirectory.dir("dist")) {
        into("ui")
    }
}

tasks.named<Delete>("clean") {
    delete(uiDirectory.dir("dist"))
}

tasks.register<Test>("testExternal") {
    description = "Runs live service tests against the TfL API."
    group = "verification"
    testClassesDirs = testExternal.output.classesDirs
    classpath = testExternal.runtimeClasspath
    shouldRunAfter(tasks.named("test"))
}
