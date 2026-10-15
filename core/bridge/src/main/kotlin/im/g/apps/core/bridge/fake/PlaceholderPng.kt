package im.g.apps.core.bridge.fake

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

private const val MAX_SIDE = 64

/** Writes a solid-colour RGB PNG keeping the aspect ratio of [width] x [height], scaled down to fit 64 px. */
internal fun writePlaceholderPng(file: File, width: Int, height: Int, seed: Int) {
    val scale = maxOf(1.0, maxOf(width, height) / MAX_SIDE.toDouble())
    val w = maxOf(1, (width / scale).toInt())
    val h = maxOf(1, (height / scale).toInt())
    val r = 0x40 + (seed and 0x7F)
    val g = 0x40 + (seed shr 8 and 0x7F)
    val b = 0x40 + (seed shr 16 and 0x7F)

    val raw = ByteArrayOutputStream()
    repeat(h) {
        raw.write(0)
        repeat(w) {
            raw.write(r)
            raw.write(g)
            raw.write(b)
        }
    }
    val compressed = ByteArrayOutputStream()
    DeflaterOutputStream(compressed).use { it.write(raw.toByteArray()) }

    val header = ByteArrayOutputStream()
    DataOutputStream(header).run {
        writeInt(w)
        writeInt(h)
        write(byteArrayOf(8, 2, 0, 0, 0))
    }

    DataOutputStream(file.outputStream().buffered()).use { out ->
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        out.chunk("IHDR", header.toByteArray())
        out.chunk("IDAT", compressed.toByteArray())
        out.chunk("IEND", ByteArray(0))
    }
}

private fun DataOutputStream.chunk(type: String, data: ByteArray) {
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    writeInt(data.size)
    write(typeBytes)
    write(data)
    val crc = CRC32().apply {
        update(typeBytes)
        update(data)
    }
    writeInt(crc.value.toInt())
}
