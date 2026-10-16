package org.basaltmc.quarry

import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """usage: quarry extract <server.jar> [--work <dir>] [--java <java>] [--javac <javac>] [--heap <size>]
       quarry version <server.jar>
<server.jar> is the bundler jar Mojang distributes, not the unpacked server jar."""

public fun main(args: Array<String>) {
    if (args.size < 2 || args[0] !in setOf("extract", "version")) {
        System.err.println(USAGE)
        exitProcess(2)
    }
    val jar = Path.of(args[1])
    if (args[0] == "version") {
        println(Bundler.version(jar))
        return
    }
    var work: Path? = null
    var tools = Tools()
    var i = 2
    while (i < args.size) {
        val value = args.getOrNull(i + 1)
        when {
            args[i] == "--work" && value != null -> work = Path.of(value)
            args[i] == "--java" && value != null -> tools = Tools(value, tools.javac, tools.heap)
            args[i] == "--javac" && value != null -> tools = Tools(tools.java, value, tools.heap)
            args[i] == "--heap" && value != null -> tools = Tools(tools.java, tools.javac, value)
            else -> {
                System.err.println(USAGE)
                exitProcess(2)
            }
        }
        i += 2
    }
    extract(jar, (work ?: Path.of("build", "quarry", Bundler.version(jar))).toAbsolutePath(), tools)
}
