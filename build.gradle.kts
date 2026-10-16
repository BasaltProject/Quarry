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

// What leaves the jar: the bundler is unpacked here, the reports and the dumps are written here.
val work = layout.buildDirectory.dir("quarry")
val vanilla = work.map { it.dir("vanilla") }

// The harnesses call the game's own classes, so they compile against the unpacked server jar and its libraries.
val dump: SourceSet by sourceSets.creating

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    "dumpCompileOnly"(fileTree(vanilla) { include("**/*.jar") })
}

val bundler = providers.gradleProperty("quarry.jar")

val unpackBundler by tasks.registering(JavaExec::class) {
    description = "Unpacks the bundler jar given with -Pquarry.jar=<server.jar>."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.basaltmc.quarry.MainKt")
    argumentProviders.add { listOf("unpack", bundler.get(), vanilla.get().asFile.path) }
    outputs.dir(vanilla)
}

tasks.named("compileDumpKotlin") { dependsOn(unpackBundler) }

val reports by tasks.registering(Exec::class) {
    description = "Runs the jar's own report generator."
    dependsOn(unpackBundler)
    workingDir(work)
    doFirst {
        delete(work.get().dir("generated"))
        work.get().asFile.mkdirs()
    }
    commandLine(
        "java", "-Xmx2g", "-DbundlerMainClass=net.minecraft.data.Main", "-jar",
        bundler.getOrElse(""), "--reports",
    )
    outputs.dir(work.map { it.dir("generated") })
}

val dumps = listOf(
    "blocks", "shapes", "items", "block_entities", "entities", "world", "support", "map_colors", "block_sounds",
)

val runDumps by tasks.registering(JavaExec::class) {
    description = "Runs the harnesses and writes one .tsv per dump."
    dependsOn(tasks.named("dumpClasses"))
    classpath = dump.runtimeClasspath + files(fileTree(vanilla) { include("**/*.jar") })
    mainClass.set("org.basaltmc.quarry.dump.HarnessKt")
    maxHeapSize = "2g"
    argumentProviders.add { listOf(work.get().dir("dumps").asFile.path) + dumps }
    outputs.dir(work.map { it.dir("dumps") })
}

tasks.register("extract") {
    group = "quarry"
    description = "Reports and dumps for the jar given with -Pquarry.jar=<server.jar>."
    dependsOn(reports, runDumps)
}

val tablesOut = providers.gradleProperty("quarry.out")
val tablesStyle = providers.gradleProperty("quarry.style")

tasks.register<JavaExec>("generate") {
    group = "quarry"
    description = "Writes server-registry's tables under -Pquarry.out=<dir> and -Pquarry.style=<.clang-format>, from the reports and dumps of extract."
    dependsOn("extract")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.basaltmc.quarry.MainKt")
    argumentProviders.add {
        val serverJar = vanilla.get().asFile.walkTopDown().first { it.name.startsWith("server-") && it.extension == "jar" }
        listOf(
            "generate", work.get().dir("generated/reports").asFile.path, work.get().dir("dumps").asFile.path,
            serverJar.path, tablesOut.get(), providers.gradleProperty("quarry.version").get(), tablesStyle.get(),
        )
    }
}
