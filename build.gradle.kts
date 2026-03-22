import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.3.0" apply false
    kotlin("plugin.serialization") version "2.3.0" apply false
}

group = "transport"
version = "1.0-SNAPSHOT"

extra["ktorVersion"] = "3.4.1"
extra["coroutinesVersion"] = "1.10.2"
extra["result4kVersion"] = "2.25.4.0"
extra["striktVersion"] = "0.35.1"
extra["kotlinxSerializationVersion"] = "1.10.0"

subprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

tasks.register("testExternal") {
    group = "verification"
    description = "Runs all external tests."
    dependsOn(
        subprojects.map { subproject ->
            subproject.tasks.matching { task -> task.name == "testExternal" }
        }
    )
}
