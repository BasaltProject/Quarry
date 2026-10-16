package org.basaltmc.quarry

import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """usage: quarry version <server.jar>
       quarry unpack <server.jar> <dir>
       quarry generate <reports dir> <dumps dir> <server jar> <out dir> <version> <.clang-format>
Extraction itself is a Gradle task: ./gradlew extract -Pquarry.jar=<server.jar>
<server.jar> is the bundler jar Mojang distributes, not the unpacked server jar."""

public fun main(args: Array<String>) {
    when {
        args.size == 2 && args[0] == "version" -> println(Bundler.version(Path.of(args[1])))
        args.size == 3 && args[0] == "unpack" -> {
            val unpacked = Bundler.unpack(Path.of(args[1]), Path.of(args[2]))
            println("Unpacked ${unpacked.version}: ${unpacked.serverJar}")
        }
        args.size == 7 && args[0] == "generate" -> {
            val written = org.basaltmc.quarry.tables.Generate.write(
                Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), args[5], Path.of(args[6]),
            )
            written.forEach { println("Wrote $it") }
        }
        else -> {
            System.err.println(USAGE)
            exitProcess(2)
        }
    }
}
