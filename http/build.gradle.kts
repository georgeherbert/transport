import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm")
}

val ktorVersion: String by rootProject.extra
val coroutinesVersion: String by rootProject.extra
val kotlinxSerializationVersion: String by rootProject.extra
val result4kVersion: String by rootProject.extra
val striktVersion: String by rootProject.extra

dependencies {
    implementation(project(":domain"))
    implementation(project(":json"))
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:$kotlinxSerializationVersion")
    implementation("dev.forkhandles:result4k:$result4kVersion")

    testImplementation(kotlin("test-junit5"))
    testImplementation("io.strikt:strikt-core:$striktVersion")
    testImplementation(testFixtures(project(":domain")))
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

tasks.named<Test>("test") {
    jvmArgs("--add-modules", "jdk.httpserver")
}

tasks.register<Test>("testExternal") {
    description = "Runs live HTTP adapter tests against the TfL API."
    group = "verification"
    testClassesDirs = testExternal.output.classesDirs
    classpath = testExternal.runtimeClasspath
    shouldRunAfter(tasks.named("test"))
}
