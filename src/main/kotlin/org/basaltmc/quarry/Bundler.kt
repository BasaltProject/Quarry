package org.basaltmc.quarry

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

public class Unpacked(public val version: String, public val serverJar: Path, public val libraries: List<Path>)

public object Bundler {
    /** The game version a bundler jar carries, from its `META-INF/versions.list`. */
    public fun version(bundler: Path): String = ZipFile(bundler.toFile()).use { jar ->
        val entry = jar.getEntry("META-INF/versions.list")
            ?: error("$bundler is not a bundler jar: no META-INF/versions.list")
        val line = jar.getInputStream(entry).bufferedReader().readLine()
        line.split('\t').getOrNull(1) ?: error("malformed META-INF/versions.list")
    }

    /** The real server jar and its libraries, where the bundler would put them itself. */
    public fun unpack(bundler: Path, work: Path): Unpacked {
        var server: Path? = null
        val libraries = mutableListOf<Path>()
        ZipFile(bundler.toFile()).use { jar ->
            for (entry in jar.entries()) {
                val isVersion = entry.name.startsWith("META-INF/versions/")
                val isLibrary = entry.name.startsWith("META-INF/libraries/")
                if (!(isVersion || isLibrary) || !entry.name.endsWith(".jar")) continue
                val folder = if (isVersion) "versions" else "libraries"
                val prefix = if (isVersion) "META-INF/versions/" else "META-INF/libraries/"
                val target = work.resolve(folder).resolve(entry.name.removePrefix(prefix))
                Files.createDirectories(target.parent)
                jar.getInputStream(entry).use { Files.copy(it, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                if (isVersion) server = target else libraries.add(target)
            }
        }
        val serverJar = server ?: error("$bundler is not a bundler jar: no META-INF/versions/*/server-*.jar")
        return Unpacked(version(bundler), serverJar, libraries.sorted())
    }
}
