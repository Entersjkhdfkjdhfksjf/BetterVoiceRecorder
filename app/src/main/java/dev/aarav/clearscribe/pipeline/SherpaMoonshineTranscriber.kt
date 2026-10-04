package dev.aarav.clearscribe.pipeline

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Transcriber backed by sherpa-onnx (k2-fsa) running the Moonshine Tiny
 * (English, int8) model. Confirmed against the real sources:
 *  - Kotlin API: sherpa-onnx's kotlin-api source files at tag v1.13.8 (copied into
 *    com.k2fsa.sherpa.onnx — package name is load-bearing, the JNI bindings
 *    in the .so are compiled against it, so don't repackage these files).
 *  - Native libs: the official sherpa-onnx-v1.13.8-android.tar.bz2 release
 *    asset, in app/src/main/jniLibs/{arm64-v8a,x86_64}/.
 *  - Model: the official sherpa-onnx-moonshine-tiny-en-int8.tar.bz2 release
 *    asset (from the "asr-models" tag) — downloaded and extracted here on
 *    first use, same reasoning as Clear's ensureModelReady(): fetch once at
 *    launch rather than silently during the first recording's stop flow.
 *
 * Footprint: the extracted model is roughly 120 MB (four ONNX files +
 * tokens.txt), on top of ~65 MB of native libs already in the APK for
 * arm64-v8a + x86_64. That's a real amount of watch storage — noticeably
 * more than Clear's 9.3 MB — worth confirming fits before relying on this
 * over the long term.
 */
class SherpaMoonshineTranscriber(private val context: Context) : Transcriber {

    private val modelDir = File(context.filesDir, MODEL_DIR_NAME)
    private val tokensFile = File(modelDir, "tokens.txt")

    private val recognizerLock = Mutex()
    @Volatile private var recognizer: OfflineRecognizer? = null

    /** Call once at app launch, same pattern as GtcrnDenoiser.ensureModelReady(). */
    suspend fun ensureModelReady() = withContext(Dispatchers.IO) {
        if (tokensFile.exists()) return@withContext // already extracted

        modelDir.mkdirs()
        val tarBz2 = File(context.cacheDir, "$MODEL_DIR_NAME.tar.bz2")
        downloadTo(MODEL_URL, tarBz2)
        extractTarBz2(tarBz2, context.filesDir)
        tarBz2.delete()

        require(tokensFile.exists()) { "Model extraction finished but tokens.txt is missing — check $MODEL_URL" }
    }

    override suspend fun transcribe(samples16k: FloatArray): String = withContext(Dispatchers.Default) {
        val rec = getOrCreateRecognizer()
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples16k, 16_000)
            rec.decode(stream)
            rec.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    private suspend fun getOrCreateRecognizer(): OfflineRecognizer = recognizerLock.withLock {
        recognizer?.let { return it }

        check(tokensFile.exists()) { "Model not downloaded yet — call ensureModelReady() first" }

        val modelConfig = OfflineModelConfig(
            moonshine = OfflineMoonshineModelConfig(
                preprocessor = File(modelDir, "preprocess.onnx").absolutePath,
                encoder = File(modelDir, "encode.int8.onnx").absolutePath,
                uncachedDecoder = File(modelDir, "uncached_decode.int8.onnx").absolutePath,
                cachedDecoder = File(modelDir, "cached_decode.int8.onnx").absolutePath,
            ),
            tokens = tokensFile.absolutePath,
            numThreads = 2,
        )
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = modelConfig,
        )
        // assetManager = null -> newFromFile path, since the model lives in
        // filesDir (downloaded), not bundled in assets/.
        OfflineRecognizer(assetManager = null, config = config).also { recognizer = it }
    }

    private fun downloadTo(url: String, dest: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            // The actual transfer (~103 MB for Moonshine) can legitimately take
            // a while on a slow or Bluetooth-shared watch connection, so this
            // is a stall timeout (time between bytes), not a total-transfer cap.
            readTimeout = 30_000
            // GitHub's release-asset CDN has been inconsistent with the
            // default Java HttpURLConnection UA in the past — set an
            // explicit one rather than chase a CDN-side rejection blind.
            setRequestProperty("User-Agent", "ClearScribe-Android/1.0")
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "Model download failed: HTTP ${connection.responseCode} for $url"
            }
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
        } catch (e: java.net.SocketTimeoutException) {
            throw java.io.IOException(
                "Model download timed out (no data for 30s) — check the watch has a working " +
                    "internet connection, not just a Bluetooth link to the phone. URL: $url",
                e,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun extractTarBz2(tarBz2: File, destRoot: File) {
        BZip2CompressorInputStream(tarBz2.inputStream().buffered()).use { bz2 ->
            TarArchiveInputStream(bz2).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    // Entries are like "sherpa-onnx-moonshine-tiny-en-int8/preprocess.onnx"
                    // or ".../test_wavs/0.wav" — skip the sample wavs, we don't need them.
                    if (!entry.isDirectory && !entry.name.contains("/test_wavs/")) {
                        val outFile = File(destRoot, entry.name)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
    }

    companion object {
        private const val MODEL_DIR_NAME = "sherpa-onnx-moonshine-tiny-en-int8"
        private const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2"
    }
}
