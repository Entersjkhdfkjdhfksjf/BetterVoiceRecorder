package dev.aarav.clearscribe.pipeline

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Denoising via sherpa-onnx's GTCRN speech-enhancement model. Replaces the
 * earlier Clear (Desert Ant Labs) integration entirely:
 *  - Reuses libsherpa-onnx-jni.so, already bundled for arm64-v8a,
 *    armeabi-v7a, and x86_64 for transcription — no new native dependency,
 *    and (unlike Clear) confirmed to actually work on 32-bit-locked Wear OS.
 *  - Model is tiny: gtcrn_simple.onnx is ~524 KB (verified via a HEAD
 *    request against the real release asset), vs Clear's 9.3 MB.
 *  - Kotlin API (OfflineSpeechDenoiser/DenoisedAudio) pulled from the real
 *    sherpa-onnx source at the same v1.13.8 tag as the transcriber, not
 *    guessed — package com.k2fsa.sherpa.onnx is load-bearing, same as
 *    SherpaMoonshineTranscriber.
 */
class GtcrnDenoiser(private val context: Context) {

    private val modelFile = File(context.filesDir, MODEL_FILENAME)

    private val denoiserLock = Mutex()
    @Volatile private var denoiser: OfflineSpeechDenoiser? = null

    @Volatile
    var isAvailable: Boolean = false
        private set

    /** Downloads gtcrn_simple.onnx if not already cached. Non-fatal on failure (see isAvailable). */
    suspend fun ensureModelReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!modelFile.exists()) {
                val tmp = File(context.cacheDir, "$MODEL_FILENAME.part")
                downloadTo(MODEL_URL, tmp)
                tmp.renameTo(modelFile)
            }
            // Touch the native loader once here so a load failure surfaces
            // at startup (visible, logged) rather than during the first
            // recording's stop-and-process step.
            buildDenoiser()
            isAvailable = true
            true
        } catch (e: Throwable) {
            Log.w(TAG, "GTCRN denoiser unavailable: ${e::class.simpleName}: ${e.message}")
            isAvailable = false
            false
        }
    }

    /** [samples]/[sampleRate]: whatever was recorded — the model handles its own resampling. */
    suspend fun clean(samples: FloatArray, sampleRate: Int): FloatArray = withContext(Dispatchers.Default) {
        val d = getOrCreateDenoiser()
        d.run(samples, sampleRate).samples
    }

    private suspend fun getOrCreateDenoiser(): OfflineSpeechDenoiser = denoiserLock.withLock {
        denoiser?.let { return it }
        buildDenoiser().also { denoiser = it }
    }

    private fun buildDenoiser(): OfflineSpeechDenoiser {
        check(modelFile.exists()) { "gtcrn_simple.onnx not downloaded yet — call ensureModelReady() first" }
        val config = OfflineSpeechDenoiserConfig(
            model = OfflineSpeechDenoiserModelConfig(
                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = modelFile.absolutePath),
                numThreads = 1,
            ),
        )
        return OfflineSpeechDenoiser(assetManager = null, config = config)
    }

    private fun downloadTo(url: String, dest: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "ClearScribe-Android/1.0")
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "GTCRN model download failed: HTTP ${connection.responseCode} for $url"
            }
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "GtcrnDenoiser"
        private const val MODEL_FILENAME = "gtcrn_simple.onnx"
        private const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx"
    }
}
