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

/**
 * Reads a WAV file written by [writeWav] (16-bit PCM mono) into -1f..1f
 * samples, for waveform display. Scans for the "data" chunk rather than
 * assuming the fixed 44-byte header our own writer happens to produce, so
 * this still works if the file gained extra chunks some other way.
 */
fun readWavAsFloat(file: File): FloatArray {
    val bytes = file.readBytes()
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var pos = 12 // past "RIFF"<size>"WAVE"
    var dataOffset = -1
    var dataSize = 0
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4, Charsets.US_ASCII)
        val size = buf.getInt(pos + 4)
        if (id == "data") {
            dataOffset = pos + 8
            dataSize = size
            break
        }
        pos += 8 + size + (size and 1) // chunks are word-aligned
    }
    if (dataOffset < 0) return FloatArray(0)

    val shorts = ByteBuffer.wrap(bytes, dataOffset, dataSize.coerceAtMost(bytes.size - dataOffset))
        .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val out = FloatArray(shorts.remaining())
    for (i in out.indices) out[i] = shorts.get(i) / 32768f
    return out
}

/** Downsamples [samples] into [buckets] peak-amplitude values (0f..1f) for waveform drawing. */
fun computeWaveformPeaks(samples: FloatArray, buckets: Int): FloatArray {
    if (samples.isEmpty() || buckets <= 0) return FloatArray(0)
    val perBucket = (samples.size.toDouble() / buckets).coerceAtLeast(1.0)
    return FloatArray(buckets) { b ->
        val start = (b * perBucket).toInt().coerceIn(0, samples.size - 1)
        val end = (((b + 1) * perBucket).toInt()).coerceIn(start + 1, samples.size)
        var peak = 0f
        for (i in start until end) {
            val a = kotlin.math.abs(samples[i])
            if (a > peak) peak = a
        }
        peak
    }
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
