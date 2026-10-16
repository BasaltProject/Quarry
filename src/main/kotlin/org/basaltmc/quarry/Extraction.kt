package org.basaltmc.quarry

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

public class Tools(public val java: String = "java", public val javac: String = "javac", public val heap: String = "2g")

private val dumps = listOf(
    "blocks", "shapes", "items", "block_entities", "entities", "world", "support", "map_colors", "block_sounds",
)

private fun run(command: List<String>, workDir: Path, log: Path) {
    val result = ProcessBuilder(command)
        .directory(workDir.toFile())
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start()
        .waitFor()
    check(result == 0) { "${command.first()} exited with $result, see $log" }
}

private fun classpath(jars: Unpacked): String =
    (listOf(jars.serverJar) + jars.libraries).joinToString(File.pathSeparator) { it.toString() }

/** Copies the harness sources out of the resources, since `javac` wants files. */
private fun harnessSources(into: Path): List<Path> {
    Files.createDirectories(into)
    val names = Bundler::class.java.getResourceAsStream("/harness/index.txt")!!.bufferedReader().readLines()
    return names.filter { it.isNotBlank() }.map { name ->
        val target = into.resolve(name)
        Bundler::class.java.getResourceAsStream("/harness/$name")!!.use { Files.copy(it, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        target
    }
}

/** Runs the jar's own report generator, then compiles and runs the harness; results land under [work]. */
public fun extract(bundler: Path, work: Path, tools: Tools) {
    Files.createDirectories(work)
    val jars = Bundler.unpack(bundler, work)
    println("Running the ${jars.version} report generator...")
    work.resolve("generated").toFile().deleteRecursively()
    run(
        listOf(tools.java, "-Xmx${tools.heap}", "-DbundlerMainClass=net.minecraft.data.Main", "-jar", bundler.toString(), "--reports"),
        work, work.resolve("reports.log"),
    )

    println("Compiling and running the harnesses...")
    val classes = work.resolve("classes")
    classes.toFile().deleteRecursively()
    Files.createDirectories(classes)
    val sources = harnessSources(work.resolve("harness"))
    run(
        listOf(tools.javac, "-nowarn", "-cp", classpath(jars), "-d", classes.toString()) + sources.map { it.toString() },
        work, work.resolve("javac.log"),
    )
    val out = work.resolve("dumps")
    run(
        listOf(tools.java, "-Xmx${tools.heap}", "-cp", classes.toString() + File.pathSeparator + classpath(jars), "Harness", out.toString()) + dumps,
        work, work.resolve("harness.log"),
    )
}
