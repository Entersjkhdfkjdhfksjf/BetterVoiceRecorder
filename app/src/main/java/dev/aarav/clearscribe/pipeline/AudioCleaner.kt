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

            val (fieldUsed, extracted) = extractSamples(result)
            val inputPeak = samples48k.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
            val outputPeak = extracted?.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
            Log.i(
                TAG,
                "field used: $fieldUsed | truePeakDbfs=${result.measuredTruePeakDbfs} | " +
                    "input peak amplitude=$inputPeak, output peak amplitude=$outputPeak " +
                    "(0.0-1.0 scale; if output is near 0 while input isn't, that field is wrong or empty)"
            )
            if (extracted == null) {
                Log.w(TAG, "No candidate field matched a non-empty FloatArray — returning RAW (uncleaned) audio.")
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
     * reflection. Returns the field name actually used (or null) alongside
     * the array, so the log is unambiguous about which path ran — not just
     * that *a* field exists, but that *this specific one* was picked and
     * whether it actually held signal (see the peak-amplitude log above).
     */
    private fun extractSamples(result: Any): Pair<String?, FloatArray?> {
        val klass = result::class.java
        for (name in listOf("samples", "audio", "wav", "pcm", "output")) {
            runCatching {
                val f = klass.getDeclaredField(name).apply { isAccessible = true }
                (f.get(result) as? FloatArray)?.takeIf { it.isNotEmpty() }?.let { return name to it }
            }
        }
        runCatching {
            val f = klass.getDeclaredField("channels").apply { isAccessible = true }
            when (val v = f.get(result)) {
                is Array<*> -> (v.firstOrNull() as? FloatArray)?.takeIf { it.isNotEmpty() }?.let { return "channels[0]" to it }
                is List<*> -> (v.firstOrNull() as? FloatArray)?.takeIf { it.isNotEmpty() }?.let { return "channels[0]" to it }
            }
        }
        return null to null
    }

    companion object {
        private const val TAG = "AudioCleaner"
    }
}
