plugins {
    kotlin("jvm")
    `java-test-fixtures`
}

val coroutinesVersion: String by rootProject.extra
val result4kVersion: String by rootProject.extra
val striktVersion: String by rootProject.extra

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:$coroutinesVersion")
    implementation("dev.forkhandles:result4k:$result4kVersion")

    testImplementation(kotlin("test-junit5"))
    testImplementation("io.strikt:strikt-core:$striktVersion")

    testFixturesImplementation("io.strikt:strikt-core:$striktVersion")
    testFixturesImplementation("dev.forkhandles:result4k:$result4kVersion")
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
