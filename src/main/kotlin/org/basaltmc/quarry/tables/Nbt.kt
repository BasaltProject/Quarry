package org.basaltmc.quarry.tables

import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Just enough of a gzipped NBT reader to list the block palette of a structure template. */
internal object Nbt {
    fun readGzipped(data: InputStream): Map<String, Any?> {
        val input = DataInputStream(GZIPInputStream(data))
        val tag = input.readByte().toInt()
        input.readUTF()
        @Suppress("UNCHECKED_CAST")
        return payload(input, tag) as Map<String, Any?>
    }

    private fun payload(input: DataInputStream, tag: Int): Any? = when (tag) {
        1 -> input.readByte()
        2 -> input.readShort()
        3 -> input.readInt()
        4 -> input.readLong()
        5 -> input.readFloat()
        6 -> input.readDouble()
        7, 11, 12 -> {
            val count = input.readInt()
            input.skipNBytes(count.toLong() * mapOf(7 to 1, 11 to 4, 12 to 8).getValue(tag))
            null
        }
        8 -> input.readUTF()
        9 -> {
            val element = input.readByte().toInt()
            List(input.readInt()) { payload(input, element) }
        }
        10 -> {
            val compound = LinkedHashMap<String, Any?>()
            while (true) {
                val child = input.readByte().toInt()
                if (child == 0) break
                compound[input.readUTF()] = payload(input, child)
            }
            compound
        }
        else -> throw IllegalArgumentException("unknown NBT tag $tag")
    }
}
