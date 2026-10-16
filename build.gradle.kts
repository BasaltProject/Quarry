plugins {
    kotlin("jvm") version "2.4.21"
    application
}

group = "org.basaltmc"
version = "26.3.0"

kotlin {
    jvmToolchain(25)
    explicitApi()
}

application {
    mainClass.set("org.basaltmc.quarry.MainKt")
    applicationName = "quarry"
}
