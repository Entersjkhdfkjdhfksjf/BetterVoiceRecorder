package dev.aarav.clearscribe.record

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads a raw 16-bit little-endian mono PCM file into -1f..1f samples. */
fun readPcm16AsFloat(file: File): FloatArray {
    val bytes = file.readBytes()
    val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val out = FloatArray(shorts.remaining())
    for (i in out.indices) out[i] = shorts.get(i) / 32768f
    return out
}

/** Very small nearest-neighbour resampler. Fine for speech; not for music. */
fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
    if (fromRate == toRate) return samples
    val ratio = toRate.toDouble() / fromRate.toDouble()
    val outLength = (samples.size * ratio).toInt()
    return FloatArray(outLength) { i ->
        val srcIndex = (i / ratio).toInt().coerceIn(0, samples.size - 1)
        samples[srcIndex]
    }
}

/** Writes -1f..1f mono samples out as a 16-bit PCM WAV file. */
fun writeWav(samples: FloatArray, sampleRate: Int, outFile: File) {
    val byteRate = sampleRate * 2
    val dataSize = samples.size * 2
    RandomAccessFile(outFile, "rw").use { raf ->
        raf.setLength(0)
        // RIFF header
        raf.writeBytes("RIFF")
        raf.write(intToLeBytes(36 + dataSize))
        raf.writeBytes("WAVE")
        // fmt chunk
        raf.writeBytes("fmt ")
        raf.write(intToLeBytes(16))                 // chunk size
        raf.write(shortToLeBytes(1))                 // PCM
        raf.write(shortToLeBytes(1))                 // mono
        raf.write(intToLeBytes(sampleRate))
        raf.write(intToLeBytes(byteRate))
        raf.write(shortToLeBytes(2))                 // block align
        raf.write(shortToLeBytes(16))                // bits per sample
        // data chunk
        raf.writeBytes("data")
        raf.write(intToLeBytes(dataSize))
        val buf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val clamped = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            buf.putShort(clamped)
        }
        raf.write(buf.array())
    }
}

private fun intToLeBytes(v: Int): ByteArray =
    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

private fun shortToLeBytes(v: Int): ByteArray =
    ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
