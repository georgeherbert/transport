plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "transport"

include(":domain")
include(":json")
include(":http")
include(":service")
