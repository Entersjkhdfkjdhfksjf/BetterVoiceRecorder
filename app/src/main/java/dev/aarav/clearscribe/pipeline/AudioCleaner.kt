package dev.aarav.clearscribe.pipeline

import ai.desertant.clear.Clear
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CleanResult(
    val samples48k: FloatArray,
    val measuredTruePeakDbfs: Double?,
)

/**
 * Runs Desert Ant Labs' Clear model (denoise + dereverb + loudness) over a
 * finished recording.
 *
 * STILL UNRESOLVED: the field holding the enhanced samples themselves isn't
 * named anywhere in Desert Ant's public docs (checked the full current
 * /docs/clear/ page — it shows `result.measuredTruePeakDbfs` and, for
 * stereo, `stereo.channelCount`, but never the actual audio-data property),
 * and the AAR itself isn't inspectable from here (Maven Central isn't
 * reachable). Rather than guess a third time, [clean] below logs the real
 * field names via reflection the first time it runs. Record something, then
 * `adb logcat -s AudioCleaner`, and the log line will show exactly what's
 * on the Result object — swap that into [clean] once you have it.
 */
class AudioCleaner(private val context: Context) {

    /**
     * Fetches the Clear model over the network if it isn't cached yet.
     *
     * Without this, the model downloads lazily inside the first call to
     * [clean] instead — i.e. silently, the first time you stop a recording,
     * on whatever network the watch has at that moment. Calling this once at
     * app launch (see ClearScribeApp) turns that into a visible "downloading
     * model" state instead of an unexplained delay or failure mid-recording.
     *
     * VERIFY: `isDownloaded()` / `download()` are confirmed on sibling SDKs
     * in the same family (Emo's docs show exactly this
     * `if (!emo.isDownloaded()) { emo.download() }` shape) but weren't shown
     * directly for Clear. If these method names don't exist on `Clear`,
     * check ai.desertant.clear's public API — the shape should be the same
     * since it's the same underlying core.
     */
    suspend fun ensureModelReady(): Unit = withContext(Dispatchers.IO) {
        Clear(context).use { clear ->
            if (!clear.isDownloaded()) {
                clear.download()
            }
        }
    }

    suspend fun clean(samples48k: FloatArray): CleanResult = withContext(Dispatchers.Default) {
        Clear(context).use { clear ->
            val result = clear.enhance(samples48k, 48_000.0)
            logResultShape(result)

            val extracted = extractSamples(result)
            if (extracted == null) {
                Log.w(TAG, "Could not find an audio field on Result — returning RAW (uncleaned) audio. See the field dump above and wire the real one in.")
            }
            CleanResult(
                samples48k = extracted ?: samples48k,
                measuredTruePeakDbfs = result.measuredTruePeakDbfs,
            )
        }
    }

    /** Logs every declared field name + type on Result, once, so the real name is obvious from Logcat. */
    private fun logResultShape(result: Any) {
        val fields = result::class.java.declaredFields
            .joinToString { "${it.name}: ${it.type.simpleName}" }
        Log.i(TAG, "Clear Result fields -> $fields")
    }

    /**
     * Tries the field-name candidates the SDK's naming conventions suggest
     * (mono "samples", a 2D "channels" array indexed [0], etc.) via
     * reflection, so this works the moment the real name is confirmed from
     * the log above without another guess-and-recompile cycle. Returns null
     * if none match, in which case [clean] falls back to raw audio.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractSamples(result: Any): FloatArray? {
        val klass = result::class.java
        for (name in listOf("samples", "audio", "wav", "pcm", "output")) {
            runCatching {
                val f = klass.getDeclaredField(name).apply { isAccessible = true }
                (f.get(result) as? FloatArray)?.let { return it }
            }
        }
        runCatching {
            val f = klass.getDeclaredField("channels").apply { isAccessible = true }
            when (val v = f.get(result)) {
                is Array<*> -> (v.firstOrNull() as? FloatArray)?.let { return it }
                is List<*> -> (v.firstOrNull() as? FloatArray)?.let { return it }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "AudioCleaner"
    }
}
