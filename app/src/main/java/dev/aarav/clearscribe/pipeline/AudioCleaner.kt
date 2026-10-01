package dev.aarav.clearscribe.pipeline

import ai.desertant.clear.Clear
import android.content.Context
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
 * VERIFY BEFORE FIRST RUN: the confirmed docs call is
 *   Clear(context).use { it.enhance(samples, 48_000.0) }
 * returning a result whose fields include `measuredTruePeakDbfs`. The field
 * that holds the enhanced samples themselves was not shown in the docs
 * snippet I had — check the `Clear.enhance` return type in Android Studio
 * (Ctrl/Cmd-click into it) and fix the two `TODO` accesses below before
 * relying on this class. Likely candidates based on the Swift/JS SDKs'
 * naming (`result`, `wav`, `.samples`, `.channels[0]`): try `result.samples`
 * first.
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
            CleanResult(
                samples48k = TODO("confirm the enhanced-samples field on Clear's Result class"),
                measuredTruePeakDbfs = result.measuredTruePeakDbfs,
            )
        }
    }
}
