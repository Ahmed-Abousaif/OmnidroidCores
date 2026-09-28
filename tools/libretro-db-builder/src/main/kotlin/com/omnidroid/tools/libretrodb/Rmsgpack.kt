package com.omnidroid.tools.libretrodb

import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Reader for RetroArch's rmsgpack, which is MessagePack without extensions. */
internal class RmsgpackReader(
    private val input: InputStream,
) {
    fun readValue(): Any? {
        val type = input.read()
        if (type < 0) throw EOFException("Unexpected end of rdb")
        return when {
            type <= 0x7f -> type.toLong()
            type in 0x80..0x8f -> readMap(type and 0x0f)
            type in 0x90..0x9f -> readArray(type and 0x0f)
            type in 0xa0..0xbf -> readString(type and 0x1f)
            type >= 0xe0 -> (type - 256).toLong()
            type == 0xc0 -> null
            type == 0xc2 -> false
            type == 0xc3 -> true
            type == 0xc4 -> readBytes(readUnsigned(1).toInt())
            type == 0xc5 -> readBytes(readUnsigned(2).toInt())
            type == 0xc6 -> readBytes(readUnsigned(4).toInt())
            type == 0xcc -> readUnsigned(1)
            type == 0xcd -> readUnsigned(2)
            type == 0xce -> readUnsigned(4)
            type == 0xcf -> readUnsigned(8)
            type == 0xd0 -> readSigned(1)
            type == 0xd1 -> readSigned(2)
            type == 0xd2 -> readSigned(4)
            type == 0xd3 -> readSigned(8)
            type == 0xd9 -> readString(readUnsigned(1).toInt())
            type == 0xda -> readString(readUnsigned(2).toInt())
            type == 0xdb -> readString(readUnsigned(4).toInt())
            type == 0xdc -> readArray(readUnsigned(2).toInt())
            type == 0xdd -> readArray(readUnsigned(4).toInt())
            type == 0xde -> readMap(readUnsigned(2).toInt())
            type == 0xdf -> readMap(readUnsigned(4).toInt())
            type == 0xca -> {
                skip(4)
                null
            }
            type == 0xcb -> {
                skip(8)
                null
            }
            else -> error("Unsupported rmsgpack type 0x${type.toString(16)}")
        }
    }

    private fun readMap(size: Int): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>(size)
        repeat(size) {
            val key = readValue() as? String ?: error("rdb map key was not a string")
            map[key] = readValue()
        }
        return map
    }

    private fun readArray(size: Int): List<Any?> {
        return List(size) { readValue() }
    }

    private fun readString(length: Int): String {
        return String(readBytes(length), StandardCharsets.UTF_8)
    }

    private fun readBytes(length: Int): ByteArray {
        if (length < 0) error("Negative rmsgpack length")
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) throw EOFException("Unexpected end of rdb")
            offset += read
        }
        return bytes
    }

    private fun readUnsigned(bytes: Int): Long {
        var value = 0L
        repeat(bytes) {
            val next = input.read()
            if (next < 0) throw EOFException("Unexpected end of rdb")
            value = (value shl 8) or next.toLong()
        }
        return value
    }

    private fun readSigned(bytes: Int): Long {
        val unsigned = readUnsigned(bytes)
        val bits = bytes * 8
        val signBit = 1L shl (bits - 1)
        return if (unsigned and signBit != 0L) unsigned - (1L shl bits) else unsigned
    }

    private fun skip(count: Int) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left.toLong())
            if (skipped <= 0) {
                if (input.read() < 0) throw EOFException("Unexpected end of rdb")
                left -= 1
            } else {
                left -= skipped.toInt()
            }
        }
    }
}
