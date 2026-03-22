plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

val kotlinxSerializationVersion: String by rootProject.extra

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:$kotlinxSerializationVersion")
}
