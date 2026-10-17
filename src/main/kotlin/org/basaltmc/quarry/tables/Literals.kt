package org.basaltmc.quarry.tables

import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.readLines

/** A string as a C++ literal; the tables never hold a quote or a backslash, and a change that adds one is a bug. */
internal fun cstr(text: String): String {
    require('"' !in text && '\\' !in text) { "not a plain C++ literal: $text" }
    return "\"$text\""
}

internal fun cbool(value: Boolean): String = if (value) "true" else "false"

/** What `Float.toString` prints for the float nearest [value]; the JVM does it, as it did when the game wrote it. */
internal fun javaFloat(value: Double): String = value.toFloat().toString()

internal fun javaDouble(value: Double): String = value.toString()

/** A set of block registry ids as a base64 bitset, far shorter than the list once it is large. */
internal fun blockSet(ids: Iterable<Int>): String {
    val list = ids.toList()
    val bits = ByteArray(list.max() / 8 + 1)
    for (block in list) {
        bits[block / 8] = (bits[block / 8].toInt() or (1 shl (block % 8))).toByte()
    }
    return Base64.getEncoder().encodeToString(bits)
}

private const val INDEX_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/** Two characters per value, which is what the shape and support indexes are made of. */
internal fun encodeIndex(values: List<Int>): String {
    val base = INDEX_ALPHABET.length
    val out = StringBuilder(values.size * 2)
    for (value in values) {
        out.append(INDEX_ALPHABET[value / base]).append(INDEX_ALPHABET[value % base])
    }
    return out.toString()
}

internal fun readTsv(path: Path): List<List<String>> =
    path.readLines().filter { it.isNotEmpty() }.map { it.split('\t') }

internal fun stripNamespace(name: String): String = name.removePrefix("minecraft:")

/** `{number, "name"}` for each pair. */
internal fun idPairs(pairs: List<Pair<Int, String>>): List<String> =
    pairs.map { (number, name) -> "{$number, ${cstr(name)}}" }
